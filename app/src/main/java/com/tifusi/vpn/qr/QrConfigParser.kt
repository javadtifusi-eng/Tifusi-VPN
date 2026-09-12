package com.tifusi.vpn.qr

import android.util.Base64
import com.tifusi.vpn.vpn.Ikev2AuthType
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/**
 * Turns a scanned QR payload into a [VpnProfile] draft. Accepted forms:
 *
 * 1. A Tifusi JSON object, raw or base64-encoded (see README for the field list).
 * 2. The same JSON wrapped as `tifusi://import?data=<base64url>`.
 * 3. A standard wg-quick WireGuard config, which is what the official WireGuard QR codes carry.
 *
 * The result is a draft only: it always goes through the edit screen and [com.tifusi.vpn.vpn.VpnProfileValidator]
 * before it is saved, so a scanned certificate is checked exactly like a manually entered one.
 */
object QrConfigParser {

    private const val URI_PREFIX = "tifusi://import"

    fun parse(raw: String): VpnProfile? {
        val text = raw.trim()
        if (text.startsWith("[Interface]", ignoreCase = true)) {
            return parseWireGuard(text)
        }
        val json = decodeJson(text) ?: return null
        return runCatching { fromJson(json) }.getOrNull()
    }

    private fun decodeJson(text: String): JSONObject? {
        val body = if (text.startsWith(URI_PREFIX)) {
            URLDecoder.decode(text.substringAfter("data=", ""), "UTF-8")
        } else {
            text
        }

        val jsonText = if (body.trimStart().startsWith("{")) {
            body
        } else {
            // Panels differ on standard vs URL-safe base64, so accept both.
            listOf(Base64.DEFAULT, Base64.URL_SAFE).firstNotNullOfOrNull { flags ->
                runCatching { String(Base64.decode(body, flags), Charsets.UTF_8) }
                    .getOrNull()
                    ?.takeIf { it.trimStart().startsWith("{") }
            }
        } ?: return null

        return runCatching { JSONObject(jsonText) }.getOrNull()
    }

    private fun fromJson(json: JSONObject): VpnProfile? {
        val protocol = when (json.str("protocol")?.uppercase()) {
            "IKEV2", "IKEV2-IPSEC", "IPSEC" -> VpnProtocol.IKEV2
            "WIREGUARD", "WG" -> VpnProtocol.WIREGUARD
            "L2TP", "L2TP-IPSEC" -> VpnProtocol.L2TP
            "PPTP" -> VpnProtocol.PPTP
            else -> return null
        }
        val server = json.str("server") ?: return null

        return VpnProfile(
            id = UUID.randomUUID().toString(),
            name = json.str("name") ?: server,
            protocol = protocol,
            serverAddress = server,
            countryName = json.str("country"),
            countryFlagEmoji = json.str("flag"),
            ikev2AuthType = parseAuthType(json.str("auth")),
            remoteIdentifier = json.str("remote_id"),
            localIdentifier = json.str("local_id"),
            presharedKey = json.str("psk"),
            serverRootCaCertPem = json.str("ca_cert"),
            userCertPem = json.str("client_cert"),
            userPrivateKeyPem = json.str("client_key"),
            pkcs12Base64 = json.str("p12"),
            pkcs12Password = json.str("p12_password"),
            username = json.str("username"),
            password = json.str("password"),
            l2tpIpsecPresharedKey = json.str("l2tp_psk") ?: json.str("ipsec_psk"),
            wireGuardPrivateKey = json.str("wg_private_key"),
            wireGuardPeerPublicKey = json.str("wg_peer_public_key"),
            wireGuardPresharedKey = json.str("wg_preshared_key"),
            wireGuardAddress = json.str("wg_address"),
            wireGuardDnsServers = json.str("wg_dns"),
            wireGuardEndpointPort = json.optInt("wg_port", 0).takeIf { it > 0 },
            wireGuardAllowedIps = json.str("wg_allowed_ips") ?: "0.0.0.0/0, ::/0",
        )
    }

    private fun parseAuthType(value: String?): Ikev2AuthType = when (value?.lowercase()) {
        "certificate", "cert", "rsa" -> Ikev2AuthType.CERTIFICATE
        "username_password", "eap", "mschapv2" -> Ikev2AuthType.USERNAME_PASSWORD
        else -> Ikev2AuthType.PSK
    }

    private fun parseWireGuard(text: String): VpnProfile? {
        var section = ""
        val iface = mutableMapOf<String, String>()
        val peer = mutableMapOf<String, String>()

        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (line.startsWith("[")) {
                    section = line.lowercase()
                    return@forEach
                }
                // Split on the first '=' only: base64 keys end in '='.
                val key = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=', "").trim()
                when (section) {
                    "[interface]" -> iface[key] = value
                    // Only the first peer is used; a personal tunnel has one server.
                    "[peer]" -> if (key !in peer) peer[key] = value
                }
            }

        val endpoint = peer["endpoint"] ?: return null
        val (host, port) = splitEndpoint(endpoint) ?: return null

        return VpnProfile(
            id = UUID.randomUUID().toString(),
            name = host,
            protocol = VpnProtocol.WIREGUARD,
            serverAddress = host,
            wireGuardPrivateKey = iface["privatekey"],
            wireGuardAddress = iface["address"],
            wireGuardDnsServers = iface["dns"],
            wireGuardPeerPublicKey = peer["publickey"],
            wireGuardPresharedKey = peer["presharedkey"],
            wireGuardEndpointPort = port,
            wireGuardAllowedIps = peer["allowedips"] ?: "0.0.0.0/0, ::/0",
        )
    }

    /** Handles both `host:port` and bracketed IPv6 `[::1]:port`. */
    private fun splitEndpoint(endpoint: String): Pair<String, Int>? {
        return if (endpoint.startsWith("[")) {
            val host = endpoint.substringAfter('[').substringBefore(']')
            val port = endpoint.substringAfter("]:", "").toIntOrNull() ?: return null
            host to port
        } else {
            val separator = endpoint.lastIndexOf(':')
            if (separator <= 0) return null
            val port = endpoint.substring(separator + 1).toIntOrNull() ?: return null
            endpoint.substring(0, separator) to port
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
}
