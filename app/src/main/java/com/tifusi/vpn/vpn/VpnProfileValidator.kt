package com.tifusi.vpn.vpn

/**
 * Pre-flight checks run when a profile is saved or imported — before it is ever handed to the
 * platform. Catching a bad certificate here means the user sees a precise reason at import time
 * rather than a generic "couldn't connect" later.
 */
object VpnProfileValidator {

    fun validate(profile: VpnProfile): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        if (profile.serverAddress.isBlank()) {
            issues += ValidationIssue.MissingServerAddress
        }

        when (profile.protocol) {
            VpnProtocol.IKEV2 -> validateIkev2(profile, issues)
            VpnProtocol.WIREGUARD -> validateWireGuard(profile, issues)
            VpnProtocol.L2TP -> {
                if (profile.username.isNullOrBlank()) issues += ValidationIssue.MissingUsername
                if (profile.password.isNullOrBlank()) issues += ValidationIssue.MissingPassword
            }
            VpnProtocol.PPTP -> {
                if (profile.username.isNullOrBlank()) issues += ValidationIssue.MissingUsername
                if (profile.password.isNullOrBlank()) issues += ValidationIssue.MissingPassword
            }
        }

        return issues
    }

    private fun validateIkev2(profile: VpnProfile, issues: MutableList<ValidationIssue>) {
        // Ikev2VpnProfile always uses the server address as the remote IKE identity and has no API
        // to set it separately, so a Remote ID that differs could never match the server.
        val remoteId = profile.remoteIdentifier?.trim()
        if (!remoteId.isNullOrEmpty() && !remoteId.equals(profile.serverAddress.trim(), ignoreCase = true)) {
            issues += ValidationIssue.RemoteIdDiffersFromServer
        }

        when (profile.ikev2AuthType) {
            Ikev2AuthType.PSK -> {
                if (profile.presharedKey.isNullOrBlank()) issues += ValidationIssue.MissingPresharedKey
                if (profile.localIdentifier.isNullOrBlank()) issues += ValidationIssue.MissingLocalId
            }

            Ikev2AuthType.USERNAME_PASSWORD -> {
                if (profile.username.isNullOrBlank()) issues += ValidationIssue.MissingUsername
                if (profile.password.isNullOrBlank()) issues += ValidationIssue.MissingPassword
                checkServerCa(profile, issues, required = false)
            }

            Ikev2AuthType.CERTIFICATE -> {
                checkClientCredentials(profile, issues)
                checkServerCa(profile, issues, required = true)
            }
        }
    }

    private fun checkServerCa(
        profile: VpnProfile,
        issues: MutableList<ValidationIssue>,
        required: Boolean,
    ) {
        val pem = profile.serverRootCaCertPem?.takeIf { it.isNotBlank() }
        if (pem == null) {
            // A p12 bundle may already carry the CA, so only complain when neither source has one.
            val bundleMayCarryCa = !profile.pkcs12Base64.isNullOrBlank()
            if (required && !bundleMayCarryCa) {
                issues += ValidationIssue.MissingServerCa
            } else if (!required) {
                issues += ValidationIssue.NoServerCaPinned
            }
            return
        }

        try {
            val cert = CertificateStore.parseCertificate(pem)
            CertificateStore.validate(cert, expectCa = true)
        } catch (e: CertificateProblem) {
            issues += ValidationIssue.BadServerCa(e)
        }
    }

    private fun checkClientCredentials(profile: VpnProfile, issues: MutableList<ValidationIssue>) {
        val hasBundle = !profile.pkcs12Base64.isNullOrBlank()
        val hasPemPair = !profile.userCertPem.isNullOrBlank() && !profile.userPrivateKeyPem.isNullOrBlank()

        if (!hasBundle && !hasPemPair) {
            issues += ValidationIssue.MissingClientCertificate
            return
        }

        try {
            val userCert = if (hasBundle) {
                CertificateStore.parsePkcs12(profile.pkcs12Base64!!, profile.pkcs12Password).userCert
            } else {
                CertificateStore.parsePrivateKeyPem(profile.userPrivateKeyPem!!)
                CertificateStore.parseCertificate(profile.userCertPem!!)
            }
            CertificateStore.validate(userCert, expectCa = false)
            // The local IKE identity falls back to the certificate CN, so one of the two must exist.
            if (profile.localIdentifier.isNullOrBlank() && CertificateStore.commonName(userCert) == null) {
                issues += ValidationIssue.MissingLocalId
            }
        } catch (e: CertificateProblem) {
            issues += ValidationIssue.BadClientCertificate(e)
        }
    }

    private fun validateWireGuard(profile: VpnProfile, issues: MutableList<ValidationIssue>) {
        if (profile.wireGuardPrivateKey.isNullOrBlank()) issues += ValidationIssue.MissingWireGuardPrivateKey
        if (profile.wireGuardPeerPublicKey.isNullOrBlank()) issues += ValidationIssue.MissingWireGuardPeerKey
        if (profile.wireGuardAddress.isNullOrBlank()) issues += ValidationIssue.MissingWireGuardAddress
    }
}

sealed class ValidationIssue(val isBlocking: Boolean) {
    object MissingServerAddress : ValidationIssue(true)
    object RemoteIdDiffersFromServer : ValidationIssue(true)
    object MissingLocalId : ValidationIssue(true)
    object MissingPresharedKey : ValidationIssue(true)
    object MissingUsername : ValidationIssue(true)
    object MissingPassword : ValidationIssue(true)
    object MissingClientCertificate : ValidationIssue(true)
    object MissingServerCa : ValidationIssue(true)
    object MissingWireGuardPrivateKey : ValidationIssue(true)
    object MissingWireGuardPeerKey : ValidationIssue(true)
    object MissingWireGuardAddress : ValidationIssue(true)

    /** Connecting is still possible against a publicly issued server certificate. */
    object NoServerCaPinned : ValidationIssue(false)

    data class BadServerCa(val problem: CertificateProblem) : ValidationIssue(true)
    data class BadClientCertificate(val problem: CertificateProblem) : ValidationIssue(true)
}
