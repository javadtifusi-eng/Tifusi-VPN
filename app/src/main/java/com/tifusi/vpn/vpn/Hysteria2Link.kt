package com.tifusi.vpn.vpn

/**
 * A `hysteria2://` (or `hy2://`) share link, in the form the panel and the official client use:
 * `hysteria2://auth@host:port/?sni=…&obfs=salamander&obfs-password=…&insecure=0#remark`.
 *
 * [port] is kept as text because Hysteria accepts a port range there (`5000-6000`, or a list), which
 * it hops across; the client is handed it exactly as written.
 */
data class Hysteria2Link(
    val auth: String,
    val address: String,
    val port: String,
    val sni: String?,
    val obfsPassword: String?,
    val insecure: Boolean,
    val pinSha256: String?,
    val remark: String?,
) {
    /** What the client dials, with an IPv6 literal bracketed again. */
    val server: String get() = (if (address.contains(':')) "[$address]" else address) + ":" + port

    companion object {
        private val SCHEMES = listOf("hysteria2://", "hy2://")
        private val PORT_SPEC = Regex("""^\d{1,5}([-,]\d{1,5})*$""")

        fun isHysteria2(raw: String): Boolean = SCHEMES.any { raw.trim().startsWith(it, ignoreCase = true) }

        /** Throws [Hysteria2LinkProblem] with the first thing that is wrong. */
        fun parse(raw: String): Hysteria2Link {
            val link = raw.trim()
            val scheme = SCHEMES.firstOrNull { link.startsWith(it, ignoreCase = true) }
                ?: throw Hysteria2LinkProblem.NotAHysteria2Link
            var rest = link.substring(scheme.length)

            val remark = rest.substringAfter('#', "").let(ShareLinkSyntax::decode).trim().takeIf { it.isNotEmpty() }
            rest = rest.substringBefore('#')
            val query = ShareLinkSyntax.parseQuery(rest.substringAfter('?', ""))
            rest = rest.substringBefore('?').removeSuffix("/")

            val at = rest.lastIndexOf('@')
            if (at <= 0) throw Hysteria2LinkProblem.MissingAuth
            // The whole userinfo is the password; a "user:pass" form is sent to the server as is.
            val auth = ShareLinkSyntax.decode(rest.substring(0, at))
            if (auth.isBlank()) throw Hysteria2LinkProblem.MissingAuth

            val (address, portText) = ShareLinkSyntax.splitHostPort(rest.substring(at + 1))
            if (address.isNullOrEmpty()) throw Hysteria2LinkProblem.MissingAddress
            // Hysteria's own default when a link names no port.
            val port = portText ?: "443"
            if (!PORT_SPEC.matches(port) || port.split('-', ',').any { it.toInt() !in 1..65535 }) {
                throw Hysteria2LinkProblem.BadPort
            }

            val obfs = query["obfs"]?.lowercase()
            if (obfs != null && obfs != "salamander") throw Hysteria2LinkProblem.UnsupportedObfs(obfs)
            val obfsPassword = query["obfs-password"]
            if (obfs == "salamander" && obfsPassword.isNullOrEmpty()) throw Hysteria2LinkProblem.MissingObfsPassword

            return Hysteria2Link(
                auth = auth,
                address = address,
                port = port,
                sni = query["sni"],
                obfsPassword = if (obfs == "salamander") obfsPassword else null,
                insecure = query["insecure"]?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false,
                pinSha256 = query["pinSHA256"],
                remark = remark,
            )
        }
    }
}

/** Why a pasted or subscribed `hysteria2://` link cannot be used. */
sealed class Hysteria2LinkProblem(message: String) : Exception(message) {
    object NotAHysteria2Link : Hysteria2LinkProblem("Not a hysteria2:// link")
    object MissingAuth : Hysteria2LinkProblem("No password before '@'")
    object MissingAddress : Hysteria2LinkProblem("No server address")
    object BadPort : Hysteria2LinkProblem("Invalid port")
    data class UnsupportedObfs(val obfs: String) : Hysteria2LinkProblem("Unsupported obfs=$obfs")
    object MissingObfsPassword : Hysteria2LinkProblem("obfs=salamander without obfs-password")
}
