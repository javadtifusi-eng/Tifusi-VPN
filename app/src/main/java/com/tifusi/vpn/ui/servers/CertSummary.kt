package com.tifusi.vpn.ui.servers

import com.tifusi.vpn.vpn.CertificateProblem
import com.tifusi.vpn.vpn.CertificateStore
import java.util.Date

/** What the form shows under an imported certificate: who it is for, expiry, or what is wrong. */
data class CertSummary(
    val subject: String?,
    val notAfter: Date?,
    val problem: CertificateProblem?,
)

fun summarizeCertificate(pem: String?, expectCa: Boolean): CertSummary? {
    if (pem.isNullOrBlank()) return null
    return try {
        val cert = CertificateStore.parseCertificate(pem)
        val problem = try {
            CertificateStore.validate(cert, expectCa)
            null
        } catch (e: CertificateProblem) {
            e
        }
        CertSummary(cert.subjectX500Principal.name, cert.notAfter, problem)
    } catch (e: CertificateProblem) {
        CertSummary(null, null, e)
    }
}

fun summarizePkcs12(base64: String?, password: String?): CertSummary? {
    if (base64.isNullOrBlank()) return null
    return try {
        val contents = CertificateStore.parsePkcs12(base64, password)
        val problem = try {
            CertificateStore.validate(contents.userCert, expectCa = false)
            null
        } catch (e: CertificateProblem) {
            e
        }
        CertSummary(contents.userCert.subjectX500Principal.name, contents.userCert.notAfter, problem)
    } catch (e: CertificateProblem) {
        CertSummary(null, null, e)
    }
}

fun privateKeyProblem(pem: String?): CertificateProblem? {
    if (pem.isNullOrBlank()) return null
    return try {
        CertificateStore.parsePrivateKeyPem(pem)
        null
    } catch (e: CertificateProblem) {
        e
    }
}
