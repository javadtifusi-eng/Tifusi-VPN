package com.tifusi.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Ikev2VpnProfile
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnManager
import android.net.VpnProfileState
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Wraps android.net.VpnManager + Ikev2VpnProfile, the public API Android exposes (since API 30)
 * for a normal app to provision and drive an IKEv2/IPsec VPN without going through
 * Settings > VPN by hand.
 */
@RequiresApi(Build.VERSION_CODES.R)
class Ikev2VpnManager(private val context: Context) {

    private val vpnManager: VpnManager
        get() = context.getSystemService(VpnManager::class.java)

    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(ConnectivityManager::class.java)

    /**
     * Builds the IKEv2 profile and provisions it. Returns a consent [Intent] that the caller
     * must launch via startActivityForResult/ActivityResultLauncher when the system needs the
     * user to approve this app as a VPN provider (same one-time dialog as any VPN app). Returns
     * null when consent is already granted and the tunnel can be started immediately.
     */
    fun provision(profile: VpnProfile): Intent? {
        val ikeProfile = buildIkev2Profile(profile)
        return vpnManager.provisionVpnProfile(ikeProfile)
    }

    fun connect() {
        vpnManager.startProvisionedVpnProfile()
    }

    fun disconnect() {
        vpnManager.stopProvisionedVpnProfile()
    }

    private fun buildIkev2Profile(profile: VpnProfile): Ikev2VpnProfile {
        // Loaded up front because the default local identity comes from the client certificate.
        val credentials = if (profile.ikev2AuthType == Ikev2AuthType.CERTIFICATE) {
            loadClientCredentials(profile)
        } else {
            null
        }

        // The second Builder argument is the *local* (client) IKE identity. Ikev2VpnProfile has no
        // remote-identity setter: the platform always uses the server address as the remote ID.
        val builder = Ikev2VpnProfile.Builder(connectAddress(profile), localIdentity(profile, credentials))

        // A pinned server CA is what makes a self-signed server certificate trustable. When it is
        // absent the platform falls back to the system CA store, which only works for a publicly
        // issued server certificate.
        val serverCa = profile.serverRootCaCertPem
            ?.takeIf { it.isNotBlank() }
            ?.let { pem ->
                CertificateStore.parseCaCertificate(pem).also {
                    CertificateStore.validate(it, expectCa = true)
                }
            }

        when (profile.ikev2AuthType) {
            Ikev2AuthType.PSK -> builder.setAuthPsk(
                (profile.presharedKey ?: "").toByteArray(Charsets.UTF_8)
            )

            Ikev2AuthType.USERNAME_PASSWORD -> builder.setAuthUsernamePassword(
                profile.username ?: "",
                profile.password ?: "",
                serverCa,
            )

            Ikev2AuthType.CERTIFICATE -> {
                val clientCredentials = requireNotNull(credentials)
                builder.setAuthDigitalSignature(
                    clientCredentials.userCert,
                    clientCredentials.privateKey,
                    // Prefer the explicitly imported CA; otherwise use the one bundled in the p12.
                    serverCa ?: clientCredentials.caCert,
                )
            }
        }

        return builder.build()
    }

    private fun localIdentity(profile: VpnProfile, credentials: CertificateStore.Pkcs12Contents?): String {
        profile.localIdentifier?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return when (profile.ikev2AuthType) {
            Ikev2AuthType.USERNAME_PASSWORD -> profile.username.orEmpty()
            // Servers such as strongSwan match the client ID against its certificate, so the CN
            // is the natural default.
            Ikev2AuthType.CERTIFICATE ->
                credentials?.userCert?.let(CertificateStore::commonName).orEmpty()
            // Tifusi Panel's PSK mode puts no constraint on the client identity, so any stable
            // value works; the panel includes a username even in PSK mode.
            Ikev2AuthType.PSK -> profile.username?.takeIf { it.isNotBlank() } ?: DEFAULT_PSK_IDENTITY
        }
    }

    /**
     * Because the server address doubles as the remote IKE identity, a Remote ID that differs
     * from the address can only be honoured by connecting to the Remote ID itself. Tifusi Panel's
     * own iOS profile does exactly this (RemoteAddress = remote_id), and the Remote ID it hands out
     * is the server's certificate domain.
     */
    private fun connectAddress(profile: VpnProfile): String =
        profile.remoteIdentifier?.trim()?.takeIf { it.isNotEmpty() } ?: profile.serverAddress.trim()

    /**
     * Resolves the client certificate and key from whichever form the user supplied: a PKCS#12
     * bundle (the usual panel export) or a separate PEM certificate + PKCS#8 key.
     */
    private fun loadClientCredentials(profile: VpnProfile): CertificateStore.Pkcs12Contents {
        profile.pkcs12Base64?.takeIf { it.isNotBlank() }?.let { bundle ->
            val contents = CertificateStore.parsePkcs12(bundle, profile.pkcs12Password)
            CertificateStore.validate(contents.userCert, expectCa = false)
            contents.caCert?.let { CertificateStore.validate(it, expectCa = true) }
            return contents
        }

        val certPem = profile.userCertPem?.takeIf { it.isNotBlank() }
            ?: throw CertificateProblem.Pkcs12HasNoClientCertificate
        val keyPem = profile.userPrivateKeyPem?.takeIf { it.isNotBlank() }
            ?: throw CertificateProblem.Pkcs12HasNoPrivateKey

        val userCert = CertificateStore.parseCertificate(certPem)
        CertificateStore.validate(userCert, expectCa = false)
        return CertificateStore.Pkcs12Contents(
            userCert = userCert,
            privateKey = CertificateStore.parsePrivateKeyPem(keyPem),
            caCert = null,
        )
    }

    /** Observes whether an active network is currently carrying VPN traffic. */
    fun observeConnectionState(onChange: (connected: Boolean) -> Unit): ConnectivityManager.NetworkCallback {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(true)
            override fun onLost(network: Network) = onChange(false)
        }
        connectivityManager.registerNetworkCallback(request, callback)
        return callback
    }

    fun stopObserving(callback: ConnectivityManager.NetworkCallback) {
        connectivityManager.unregisterNetworkCallback(callback)
    }

    /**
     * The platform's own view of the provisioned profile. Only available from Android 13; older
     * versions return null and callers fall back to the network callback.
     */
    fun platformState(): Ikev2PlatformState? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val profileState = vpnManager.provisionedVpnProfileState ?: return null
        return when (profileState.state) {
            VpnProfileState.STATE_CONNECTED -> Ikev2PlatformState.CONNECTED
            VpnProfileState.STATE_CONNECTING -> Ikev2PlatformState.CONNECTING
            VpnProfileState.STATE_FAILED -> Ikev2PlatformState.FAILED
            else -> Ikev2PlatformState.DISCONNECTED
        }
    }

    companion object {
        private const val DEFAULT_PSK_IDENTITY = "tifusi-vpn"

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}

enum class Ikev2PlatformState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED,
}
