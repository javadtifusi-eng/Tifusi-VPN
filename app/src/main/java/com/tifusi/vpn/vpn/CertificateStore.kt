package com.tifusi.vpn.vpn

import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Parsing and validation for the IKEv2 credential material.
 *
 * Certificate problems are the single most common reason an IKEv2 tunnel silently fails to come
 * up, and the platform error surfaced to the app is generic. So everything is parsed and checked
 * eagerly here — at save time, not at connect time — and failures are reported as a typed
 * [CertificateProblem] the UI can render in Persian and English.
 */
object CertificateStore {

    private const val PEM_CERT_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PEM_RSA_KEY_BEGIN = "-----BEGIN RSA PRIVATE KEY-----"

    fun parseCertificate(pem: String): X509Certificate {
        val cleaned = pem.trim()
        if (!cleaned.contains(PEM_CERT_BEGIN)) {
            throw CertificateProblem.NotAPemCertificate
        }
        val factory = CertificateFactory.getInstance("X.509")
        val parsed = try {
            factory.generateCertificate(ByteArrayInputStream(cleaned.toByteArray()))
        } catch (e: Exception) {
            throw CertificateProblem.Unparseable(e.message)
        }
        return parsed as? X509Certificate ?: throw CertificateProblem.NotAnX509Certificate
    }

    /**
     * Accepts a certificate file as either PEM text or raw DER — .crt/.cer files exported from
     * Windows or the Samsung certificate installer are usually DER — and returns it as PEM.
     */
    fun certificateBytesToPem(bytes: ByteArray): String {
        val asText = String(bytes, Charsets.US_ASCII)
        if (asText.contains(PEM_CERT_BEGIN)) {
            parseCertificate(asText)
            return asText.trim()
        }
        val cert = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
        } catch (e: Exception) {
            throw CertificateProblem.Unparseable(e.message)
        } ?: throw CertificateProblem.NotAnX509Certificate
        return toPem(cert)
    }

    fun toPem(certificate: X509Certificate): String {
        val body = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
            .chunked(64)
            .joinToString("\n")
        return "$PEM_CERT_BEGIN\n$body\n-----END CERTIFICATE-----"
    }

    /**
     * Unpacks a PKCS#12 bundle (.p12/.pfx), the format strongSwan and most panels export client
     * credentials in. Returns the client certificate, its private key, and any CA certificates
     * that shipped inside the bundle.
     */
    fun parsePkcs12(base64Bundle: String, password: String?): Pkcs12Contents {
        val bytes = try {
            Base64.decode(base64Bundle, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw CertificateProblem.Unparseable(e.message)
        }

        val keyStore = KeyStore.getInstance("PKCS12")
        val passwordChars = (password ?: "").toCharArray()
        try {
            keyStore.load(ByteArrayInputStream(bytes), passwordChars)
        } catch (e: Exception) {
            // An IOException from KeyStore.load on PKCS#12 almost always means a bad password.
            throw CertificateProblem.WrongPkcs12Password
        }

        val keyAlias = keyStore.aliases().toList().firstOrNull { keyStore.isKeyEntry(it) }
            ?: throw CertificateProblem.Pkcs12HasNoPrivateKey

        val privateKey = keyStore.getKey(keyAlias, passwordChars) as? PrivateKey
            ?: throw CertificateProblem.Pkcs12HasNoPrivateKey
        val userCert = keyStore.getCertificate(keyAlias) as? X509Certificate
            ?: throw CertificateProblem.Pkcs12HasNoClientCertificate

        // The chain's trailing entries are the issuing CA(s); the last one is the root we pin.
        val chain = keyStore.getCertificateChain(keyAlias).orEmpty()
            .filterIsInstance<X509Certificate>()
        val caCert = chain.lastOrNull()?.takeIf { it != userCert }

        return Pkcs12Contents(userCert = userCert, privateKey = privateKey, caCert = caCert)
    }

    fun parsePrivateKeyPem(pem: String): PrivateKey {
        val cleaned = pem.trim()
        if (cleaned.contains(PEM_RSA_KEY_BEGIN)) {
            // PKCS#1. Android's KeyFactory only ingests PKCS#8, and converting PKCS#1 here
            // would mean hand-rolling DER wrapping — reject with actionable guidance instead.
            throw CertificateProblem.Pkcs1KeyNotSupported
        }
        if (!cleaned.contains(PEM_KEY_BEGIN)) {
            throw CertificateProblem.NotAPemPrivateKey
        }
        val body = cleaned
            .substringAfter(PEM_KEY_BEGIN)
            .substringBefore("-----END PRIVATE KEY-----")
            .replace("\\s".toRegex(), "")
        val der = try {
            Base64.decode(body, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw CertificateProblem.Unparseable(e.message)
        }
        val spec = java.security.spec.PKCS8EncodedKeySpec(der)
        // strongSwan issues RSA keys by default but EC is increasingly common; try both.
        for (algorithm in listOf("RSA", "EC")) {
            try {
                return java.security.KeyFactory.getInstance(algorithm).generatePrivate(spec)
            } catch (e: Exception) {
                // try next algorithm
            }
        }
        throw CertificateProblem.UnsupportedKeyAlgorithm
    }

    /**
     * Checks the things that make a structurally valid certificate still fail at connect time:
     * expiry window and — for the server CA — that it really is a CA certificate.
     */
    fun validate(certificate: X509Certificate, expectCa: Boolean, now: Date = Date()) {
        try {
            certificate.checkValidity(now)
        } catch (e: CertificateExpiredException) {
            throw CertificateProblem.Expired(certificate.notAfter)
        } catch (e: CertificateNotYetValidException) {
            throw CertificateProblem.NotYetValid(certificate.notBefore)
        }

        if (expectCa) {
            // basicConstraints < 0 means the extension is absent or marks a non-CA certificate.
            // A self-signed server certificate still works as its own trust anchor, so only a
            // leaf issued by someone else is rejected — that is the "picked the server cert
            // instead of the CA" mistake.
            val selfSigned = certificate.subjectX500Principal == certificate.issuerX500Principal
            if (certificate.basicConstraints < 0 && !selfSigned) {
                throw CertificateProblem.NotACaCertificate
            }
        }
    }

    data class Pkcs12Contents(
        val userCert: X509Certificate,
        val privateKey: PrivateKey,
        val caCert: X509Certificate?,
    )
}

/** Typed certificate failures, so the UI can show a precise bilingual message. */
sealed class CertificateProblem(message: String) : Exception(message) {
    object NotAPemCertificate : CertificateProblem("Missing BEGIN CERTIFICATE header")
    object NotAnX509Certificate : CertificateProblem("Not an X.509 certificate")
    object NotAPemPrivateKey : CertificateProblem("Missing BEGIN PRIVATE KEY header")
    object Pkcs1KeyNotSupported : CertificateProblem("PKCS#1 key must be converted to PKCS#8")
    object UnsupportedKeyAlgorithm : CertificateProblem("Unsupported private key algorithm")
    object WrongPkcs12Password : CertificateProblem("Wrong PKCS#12 password or corrupt bundle")
    object Pkcs12HasNoPrivateKey : CertificateProblem("PKCS#12 bundle contains no private key")
    object Pkcs12HasNoClientCertificate : CertificateProblem("PKCS#12 bundle contains no client certificate")
    object NotACaCertificate : CertificateProblem("Certificate is not a CA certificate")
    data class Expired(val notAfter: Date) : CertificateProblem("Certificate expired on $notAfter")
    data class NotYetValid(val notBefore: Date) : CertificateProblem("Certificate is not valid until $notBefore")
    data class Unparseable(val detail: String?) : CertificateProblem("Unparseable: $detail")
}
