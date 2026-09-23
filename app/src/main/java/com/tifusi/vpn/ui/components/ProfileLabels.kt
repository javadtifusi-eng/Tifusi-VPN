package com.tifusi.vpn.ui.components

import com.tifusi.vpn.vpn.VlessLink
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol

/** What the tab says: the transport a VLESS link actually uses, not just "VLESS". */
internal fun protocolLabel(profile: VpnProfile): String = when (profile.protocol) {
    VpnProtocol.IKEV2 -> "IKEv2"
    VpnProtocol.HYSTERIA2 -> "HY2"
    VpnProtocol.VLESS -> runCatching { VlessLink.parse(profile.vlessLink.orEmpty()) }.getOrNull()?.let { link ->
        when {
            link.security == VlessLink.SECURITY_REALITY -> "REALITY"
            link.network == VlessLink.NETWORK_WS -> "WS"
            link.network == VlessLink.NETWORK_GRPC -> "gRPC"
            link.network == VlessLink.NETWORK_XHTTP -> "XHTTP"
            link.network == VlessLink.NETWORK_HTTPUPGRADE -> "HTTPU"
            link.security == VlessLink.SECURITY_TLS -> "TLS"
            else -> "VLESS"
        }
    } ?: "VLESS"
}

/** The first flag emoji (a pair of regional indicator letters) in a server's name. */
internal fun flagIn(text: String): String? {
    val cps = text.codePoints().toArray()
    for (i in 0 until cps.size - 1) {
        if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) return String(cps, i, 2)
    }
    return null
}

/** The server's flag: the profile's own, or the first one in its name. */
internal fun VpnProfile.flag(): String? = countryFlagEmoji ?: flagIn(name)

/** The server's name without the flag it may start with. */
internal fun VpnProfile.plainName(): String =
    (countryName ?: flag()?.let { name.replace(it, "") } ?: name).trim().ifBlank { serverAddress }
