package com.tifusi.vpn.vpn

/**
 * Pre-flight checks run when a profile is saved or imported — before it is ever handed to the
 * platform. Catching a bad certificate here means the user sees a precise reason at import time
 * rather than a generic "couldn't connect" later.
 */
object VpnProfileValidator {

    fun validate(profile: VpnProfile): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // A VLESS server's address lives inside its link, which validateVless checks.
        if (profile.protocol != VpnProtocol.VLESS && profile.serverAddress.isBlank()) {
            issues += ValidationIssue.MissingServerAddress
        }

        when (profile.protocol) {
            VpnProtocol.IKEV2 -> validateIkev2(profile, issues)
            VpnProtocol.L2TP -> {
                if (profile.username.isNullOrBlank()) issues += ValidationIssue.MissingUsername
                if (profile.password.isNullOrBlank()) issues += ValidationIssue.MissingPassword
            }
            VpnProtocol.PPTP -> {
                if (profile.username.isNullOrBlank()) issues += ValidationIssue.MissingUsername
                if (profile.password.isNullOrBlank()) issues += ValidationIssue.MissingPassword
            }
            VpnProtocol.VLESS -> validateVless(profile, issues)
        }

        return issues
    }

    private fun validateIkev2(profile: VpnProfile, issues: MutableList<ValidationIssue>) {
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
            val cert = CertificateStore.parseCaCertificate(pem)
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

    private fun validateVless(profile: VpnProfile, issues: MutableList<ValidationIssue>) {
        val raw = profile.vlessLink?.takeIf { it.isNotBlank() }
        if (raw == null) {
            issues += ValidationIssue.MissingVlessLink
            return
        }
        try {
            val link = VlessLink.parse(raw)
            // The bundled core rejects allowInsecure outright, so the link is used with certificate
            // checks on; say so now rather than let a self-signed server fail mysteriously later.
            if (link.allowInsecure && link.security == VlessLink.SECURITY_TLS) {
                issues += ValidationIssue.VlessInsecureIgnored
            }
        } catch (e: VlessLinkProblem) {
            issues += ValidationIssue.BadVlessLink(e)
        }
    }
}

sealed class ValidationIssue(val isBlocking: Boolean) {
    object MissingServerAddress : ValidationIssue(true)
    object MissingLocalId : ValidationIssue(true)
    object MissingPresharedKey : ValidationIssue(true)
    object MissingUsername : ValidationIssue(true)
    object MissingPassword : ValidationIssue(true)
    object MissingClientCertificate : ValidationIssue(true)
    object MissingServerCa : ValidationIssue(true)
    object MissingVlessLink : ValidationIssue(true)

    /** The link sets allowInsecure, which is ignored: the server certificate is still verified. */
    object VlessInsecureIgnored : ValidationIssue(false)

    /** Connecting is still possible against a publicly issued server certificate. */
    object NoServerCaPinned : ValidationIssue(false)

    data class BadServerCa(val problem: CertificateProblem) : ValidationIssue(true)
    data class BadClientCertificate(val problem: CertificateProblem) : ValidationIssue(true)
    data class BadVlessLink(val problem: VlessLinkProblem) : ValidationIssue(true)
}
