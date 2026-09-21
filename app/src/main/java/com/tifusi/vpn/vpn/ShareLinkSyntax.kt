package com.tifusi.vpn.vpn

import java.net.URLDecoder

/** The URI syntax vless:// and hysteria2:// share links have in common. */
internal object ShareLinkSyntax {

    /** Host and port text, brackets stripped from an IPv6 literal; null host when a bracket is unclosed. */
    fun splitHostPort(authority: String): Pair<String?, String?> {
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return null to null
            val port = authority.substring(close + 1).removePrefix(":").takeIf { it.isNotEmpty() }
            return authority.substring(1, close) to port
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to null
        return authority.substring(0, colon) to authority.substring(colon + 1)
    }

    /** Keys are case-sensitive (serviceName, headerType); a repeated key keeps its first value. */
    fun parseQuery(query: String): Map<String, String> {
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
    fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }
}
