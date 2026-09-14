package com.tifusi.vpn.vpn

import java.net.URLDecoder

/**
 * A parsed `vless://` share link, as the panel puts them in app.json's `vless` array:
 * `vless://<uuid>@<host>:<port>?type=tcp&security=reality&sni=...&pbk=...&sid=...#<remark>`.
 *
 * The profile stores the raw link, not these fields: the link is the panel's contract and also what
 * users paste, so re-parsing it on every connect keeps one source of truth. Pure JVM code on purpose,
 * so the parser runs in plain unit tests.
 */
data class VlessLink(
    val uuid: String,
    /** Host exactly as in the link, without IPv6 brackets. */
    val address: String,
    val port: Int,
    val encryption: String = "none",
    val flow: String? = null,
    /** Normalised: tcp, ws, grpc, httpupgrade or xhttp. */
    val network: String = NETWORK_TCP,
    val headerType: String? = null,
    /** Normalised: none, tls or reality. */
    val security: String = SECURITY_NONE,
    val sni: String? = null,
    val fingerprint: String? = null,
    val publicKey: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null,
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,
    /** gRPC `multi`/`gun`, or the xhttp mode (auto, packet-up, stream-up, stream-one). */
    val mode: String? = null,
    val alpn: List<String> = emptyList(),
    val allowInsecure: Boolean = false,
    val remark: String? = null,
) {
    /**
     * The TLS/REALITY server name: the link's `sni`, else the transport host header, else the server
     * address when it is a domain. REALITY cannot work without one.
     */
    val serverName: String?
        get() = sni ?: host?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: address.takeUnless { isIpLiteral(it) }

    companion object {
        const val SCHEME = "vless://"

        const val NETWORK_TCP = "tcp"
        const val NETWORK_WS = "ws"
        const val NETWORK_GRPC = "grpc"
        const val NETWORK_HTTPUPGRADE = "httpupgrade"
        const val NETWORK_XHTTP = "xhttp"

        const val SECURITY_NONE = "none"
        const val SECURITY_TLS = "tls"
        const val SECURITY_REALITY = "reality"

        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        // Xray maps any other 1-30 byte string to a UUIDv5, so panels may hand those out as ids too.
        private const val MAX_CUSTOM_ID_BYTES = 30

        /** Throws [VlessLinkProblem] with the first thing that is wrong, so the user sees one clear reason. */
        fun parse(raw: String): VlessLink {
            val link = raw.trim()
            if (!link.startsWith(SCHEME, ignoreCase = true)) throw VlessLinkProblem.NotAVlessLink
            var rest = link.substring(SCHEME.length)

            val remark = rest.substringAfter('#', "").let(::decode).trim().takeIf { it.isNotEmpty() }
            rest = rest.substringBefore('#')
            val query = parseQuery(rest.substringAfter('?', ""))
            rest = rest.substringBefore('?').trimEnd('/')

            val at = rest.lastIndexOf('@')
            if (at <= 0) throw VlessLinkProblem.MissingUuid
            val uuid = decode(rest.substring(0, at)).trim()
            if (uuid.isEmpty()) throw VlessLinkProblem.MissingUuid
            if (!UUID.matches(uuid) &&
                (uuid.toByteArray(Charsets.UTF_8).size > MAX_CUSTOM_ID_BYTES || uuid.any { it.isWhitespace() })
            ) {
                throw VlessLinkProblem.BadUuid
            }

            val (address, portText) = splitHostPort(rest.substring(at + 1))
            if (address.isEmpty()) throw VlessLinkProblem.MissingAddress
            val port = portText?.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw VlessLinkProblem.BadPort

            val network = when (val type = query["type"]?.lowercase().orEmpty()) {
                "", "tcp", "raw" -> NETWORK_TCP
                "ws", "websocket" -> NETWORK_WS
                "grpc", "gun" -> NETWORK_GRPC
                "httpupgrade" -> NETWORK_HTTPUPGRADE
                "xhttp", "splithttp" -> NETWORK_XHTTP
                else -> throw VlessLinkProblem.UnsupportedNetwork(type)
            }
            val headerType = query["headerType"]
            // Only plain TCP is supported; the HTTP header disguise is a legacy option nobody issues.
            if (network == NETWORK_TCP && headerType != null && headerType != "none") {
                throw VlessLinkProblem.UnsupportedHeaderType(headerType)
            }

            val security = when (val value = query["security"]?.lowercase().orEmpty()) {
                "", "none" -> SECURITY_NONE
                "tls" -> SECURITY_TLS
                "reality" -> SECURITY_REALITY
                else -> throw VlessLinkProblem.UnsupportedSecurity(value)
            }

            val insecureKeys = listOf("allowInsecure", "insecure", "allow_insecure")
            val parsed = VlessLink(
                uuid = uuid,
                address = address,
                port = port,
                encryption = query["encryption"] ?: "none",
                flow = query["flow"],
                network = network,
                headerType = headerType,
                security = security,
                sni = query["sni"] ?: query["peer"],
                fingerprint = query["fp"],
                publicKey = query["pbk"],
                shortId = query["sid"],
                spiderX = query["spx"],
                path = query["path"],
                host = query["host"],
                serviceName = query["serviceName"],
                mode = query["mode"],
                alpn = query["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                allowInsecure = insecureKeys.any { query[it] == "1" || query[it].equals("true", ignoreCase = true) },
                remark = remark,
            )

            if (security == SECURITY_REALITY) {
                if (parsed.publicKey == null) throw VlessLinkProblem.MissingRealityPublicKey
                if (parsed.serverName == null) throw VlessLinkProblem.MissingRealityServerName
            }
            // The bundled core refuses to load such an outbound (Xray infra/conf/xray.go,
            // validateOutboundTransportSecurity): user id and traffic would cross the internet in clear.
            if (security == SECURITY_NONE && parsed.encryption == "none" && !isPrivateAddress(address)) {
                throw VlessLinkProblem.PlaintextNotAllowed
            }
            return parsed
        }

        /** LAN, loopback and CGNAT addresses, where the core allows VLESS without TLS. */
        fun isPrivateAddress(host: String): Boolean {
            val lower = host.lowercase()
            if (lower == "localhost" || lower.endsWith(".local")) return true
            if (lower.contains(':')) {
                return lower == "::1" || lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:")
            }
            val octets = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
            val (a, b) = octets[0] to octets[1]
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254)
        }

        fun isIpLiteral(host: String): Boolean {
            if (host.contains(':')) return true
            val match = IPV4.matchEntire(host) ?: return false
            return match.groupValues.drop(1).all { it.toInt() in 0..255 }
        }

        private fun splitHostPort(authority: String): Pair<String, String?> {
            if (authority.startsWith('[')) {
                val close = authority.indexOf(']')
                if (close < 0) throw VlessLinkProblem.MissingAddress
                val port = authority.substring(close + 1).removePrefix(":").takeIf { it.isNotEmpty() }
                return authority.substring(1, close) to port
            }
            val colon = authority.lastIndexOf(':')
            if (colon < 0) return authority to null
            return authority.substring(0, colon) to authority.substring(colon + 1)
        }

        /** Keys are case-sensitive (serviceName, headerType); a repeated key keeps its first value. */
        private fun parseQuery(query: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            query.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val key = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))
                if (key.isNotEmpty() && value.isNotEmpty() && key !in result) result[key] = value
            }
            return result
        }

        /**
         * Percent-decoding only. URLDecoder alone would turn '+' into a space, which corrupts paths and
         * base64-like values; share links encode a space as %20.
         */
        private fun decode(value: String): String = try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            value
        }
    }
}

/** Why a pasted or subscribed `vless://` link cannot be used. */
sealed class VlessLinkProblem(message: String) : Exception(message) {
    object NotAVlessLink : VlessLinkProblem("Not a vless:// link")
    object MissingUuid : VlessLinkProblem("No user id before '@'")
    object BadUuid : VlessLinkProblem("User id is not a UUID")
    object MissingAddress : VlessLinkProblem("No server address")
    object BadPort : VlessLinkProblem("Missing or invalid port")
    data class UnsupportedNetwork(val network: String) : VlessLinkProblem("Unsupported transport type=$network")
    data class UnsupportedSecurity(val security: String) : VlessLinkProblem("Unsupported security=$security")
    data class UnsupportedHeaderType(val headerType: String) : VlessLinkProblem("Unsupported headerType=$headerType")
    object MissingRealityPublicKey : VlessLinkProblem("REALITY link has no public key (pbk)")
    object MissingRealityServerName : VlessLinkProblem("REALITY link has no server name (sni)")
    object PlaintextNotAllowed : VlessLinkProblem("security=none to a public server is refused by the core")
}
