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
        // Android defaults the remote ID to the server address when it is omitted, which only
        // matches if the server certificate's SAN actually carries that address.
        if (profile.remoteIdentifier.isNullOrBlank()) {
            issues += ValidationIssue.MissingRemoteId
        }

        when (profile.ikev2AuthType) {
            Ikev2AuthType.PSK -> {
                if (profile.presharedKey.isNullOrBlank()) issues += ValidationIssue.MissingPresharedKey
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
            if (hasBundle) {
                val contents = CertificateStore.parsePkcs12(
                    profile.pkcs12Base64!!,
                    profile.pkcs12Password,
                )
                CertificateStore.validate(contents.userCert, expectCa = false)
            } else {
                val cert = CertificateStore.parseCertificate(profile.userCertPem!!)
                CertificateStore.validate(cert, expectCa = false)
                CertificateStore.parsePrivateKeyPem(profile.userPrivateKeyPem!!)
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
    object MissingRemoteId : ValidationIssue(true)
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
