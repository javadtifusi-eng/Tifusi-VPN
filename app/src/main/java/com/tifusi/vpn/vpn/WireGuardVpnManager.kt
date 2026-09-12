package com.tifusi.vpn.vpn

import android.content.Context
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

/**
 * Drives a WireGuard tunnel through the official GoBackend. Unlike IKEv2 this does not depend on
 * a platform VPN API version — the tunnel is implemented by the bundled userspace backend, so it
 * works the same on every supported device.
 */
class WireGuardVpnManager(context: Context) {

    private val backend: Backend = GoBackend(context.applicationContext)
    private var activeTunnel: TifusiTunnel? = null

    fun connect(profile: VpnProfile) {
        val tunnel = TifusiTunnel(profile.name)
        activeTunnel = tunnel
        backend.setState(tunnel, Tunnel.State.UP, buildConfig(profile))
    }

    fun disconnect() {
        activeTunnel?.let { backend.setState(it, Tunnel.State.DOWN, null) }
    }

    fun isConnected(): Boolean =
        activeTunnel?.let { backend.getState(it) == Tunnel.State.UP } ?: false

    /** Cumulative rx/tx byte counters for the live tunnel, or null when nothing is up. */
    fun statistics(): TrafficStats? {
        val tunnel = activeTunnel ?: return null
        if (backend.getState(tunnel) != Tunnel.State.UP) return null
        val stats = backend.getStatistics(tunnel)
        return TrafficStats(
            rxBytes = stats.totalRx(),
            txBytes = stats.totalTx(),
        )
    }

    private fun buildConfig(profile: VpnProfile): Config {
        val endpointPort = profile.wireGuardEndpointPort ?: DEFAULT_WIREGUARD_PORT

        // The library parses its own canonical wg-quick format, which avoids reimplementing
        // key/address/allowed-IP parsing by hand.
        val quickConfig = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${profile.wireGuardPrivateKey}")
            appendLine("Address = ${profile.wireGuardAddress}")
            profile.wireGuardDnsServers?.takeIf { it.isNotBlank() }?.let {
                appendLine("DNS = $it")
            }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${profile.wireGuardPeerPublicKey}")
            profile.wireGuardPresharedKey?.takeIf { it.isNotBlank() }?.let {
                appendLine("PresharedKey = $it")
            }
            // IPv6 literals must be bracketed or the port is parsed as part of the address.
            val host = profile.serverAddress.let { if (it.contains(':')) "[$it]" else it }
            appendLine("Endpoint = $host:$endpointPort")
            appendLine("AllowedIPs = ${profile.wireGuardAllowedIps}")
            appendLine("PersistentKeepalive = $PERSISTENT_KEEPALIVE_SECONDS")
        }

        return Config.parse(ByteArrayInputStream(quickConfig.toByteArray()))
    }

    private class TifusiTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    companion object {
        private const val DEFAULT_WIREGUARD_PORT = 51820
        private const val PERSISTENT_KEEPALIVE_SECONDS = 25
    }
}

data class TrafficStats(val rxBytes: Long, val txBytes: Long)
