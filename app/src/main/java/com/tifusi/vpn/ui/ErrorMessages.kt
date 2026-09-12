package com.tifusi.vpn.ui

import android.content.Context
import com.tifusi.vpn.R
import com.tifusi.vpn.vpn.CertificateProblem
import com.tifusi.vpn.vpn.ValidationIssue
import com.tifusi.vpn.vpn.VpnFailure
import java.text.DateFormat

/** Maps typed validation/certificate failures onto the localized strings. */
fun ValidationIssue.localized(context: Context): String = when (this) {
    ValidationIssue.MissingServerAddress -> context.getString(R.string.error_missing_server_address)
    ValidationIssue.MissingLocalId -> context.getString(R.string.error_missing_local_id)
    ValidationIssue.MissingPresharedKey -> context.getString(R.string.error_missing_preshared_key)
    ValidationIssue.MissingUsername -> context.getString(R.string.error_missing_username)
    ValidationIssue.MissingPassword -> context.getString(R.string.error_missing_password)
    ValidationIssue.MissingClientCertificate -> context.getString(R.string.error_missing_client_certificate)
    ValidationIssue.MissingServerCa -> context.getString(R.string.error_missing_server_ca)
    ValidationIssue.NoServerCaPinned -> context.getString(R.string.error_no_server_ca_pinned)
    ValidationIssue.MissingWireGuardPrivateKey -> context.getString(R.string.error_missing_wg_private_key)
    ValidationIssue.MissingWireGuardPeerKey -> context.getString(R.string.error_missing_wg_peer_key)
    ValidationIssue.MissingWireGuardAddress -> context.getString(R.string.error_missing_wg_address)
    is ValidationIssue.BadServerCa -> problem.localized(context)
    is ValidationIssue.BadClientCertificate -> problem.localized(context)
}

fun VpnFailure.localized(context: Context): String = when (this) {
    is VpnFailure.Certificate -> problem.localized(context)
    VpnFailure.NegotiationFailed -> context.getString(R.string.error_negotiation_failed)
    VpnFailure.Timeout -> context.getString(R.string.error_timeout)
    is VpnFailure.Unknown -> context.getString(R.string.error_unknown, detail ?: "-")
}

fun CertificateProblem.localized(context: Context): String {
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return when (this) {
        CertificateProblem.NotAPemCertificate -> context.getString(R.string.cert_error_not_pem)
        CertificateProblem.NotAnX509Certificate -> context.getString(R.string.cert_error_not_x509)
        CertificateProblem.NotAPemPrivateKey -> context.getString(R.string.cert_error_not_pem_key)
        CertificateProblem.Pkcs1KeyNotSupported -> context.getString(R.string.cert_error_pkcs1)
        CertificateProblem.UnsupportedKeyAlgorithm -> context.getString(R.string.cert_error_unsupported_algorithm)
        CertificateProblem.WrongPkcs12Password -> context.getString(R.string.cert_error_wrong_p12_password)
        CertificateProblem.Pkcs12HasNoPrivateKey -> context.getString(R.string.cert_error_p12_no_key)
        CertificateProblem.Pkcs12HasNoClientCertificate -> context.getString(R.string.cert_error_p12_no_cert)
        CertificateProblem.NotACaCertificate -> context.getString(R.string.cert_error_not_ca)
        is CertificateProblem.Expired ->
            context.getString(R.string.cert_error_expired, dateFormat.format(notAfter))
        is CertificateProblem.NotYetValid ->
            context.getString(R.string.cert_error_not_yet_valid, dateFormat.format(notBefore))
        is CertificateProblem.Unparseable ->
            context.getString(R.string.cert_error_unparseable, detail ?: "-")
    }
}
