package com.tifusi.vpn.qr

import android.util.Base64
import com.tifusi.vpn.vpn.Ikev2AuthType
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import org.json.JSONObject
import java.util.UUID

sealed interface ImportResult {
    data class Profile(val profile: VpnProfile) : ImportResult

    /** A Tifusi Panel subscription URL: real, but it carries no IKEv2/L2TP settings itself. */
    object SubscriptionLink : ImportResult

    object Unrecognized : ImportResult
}

/**
 * Turns a scanned QR code or pasted text into a [VpnProfile] draft. Accepted forms:
 *
 * 1. Tifusi Panel's import QR, shown in each IKEv2/L2TP card on the subscription page. The
 *    contract is defined panel-side in `backend/app/subscription/info_page.py`:
 *    `tifusi-vpn://import?data=<base64url, no padding, of JSON>` with
 *    `{"v":1, "type":"ikev2"|"l2tp", "server", "remote_id"?, "username", "password", "psk"?, "certificate"?}`.
 * 2. The text the panel's Copy button produces (`Server: …` / `Remote ID: …` / `Username: …` …).
 * 3. A standard wg-quick WireGuard config, which is what WireGuard QR codes carry.
 *
 * The result is a draft only: it always goes through the edit screen and the validator before it
 * is saved, so an imported certificate is checked exactly like a manually entered one.
 */
object QrConfigParser {

    private const val PANEL_SCHEME = "tifusi-vpn://import"
    private val SUBSCRIPTION_URL = Regex("""^https?://\S+/sub/[^/\s?#]+/?(?:[?#]\S*)?$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ImportResult {
        val text = raw.trim()
        if (SUBSCRIPTION_URL.matches(text)) return ImportResult.SubscriptionLink

        val profile = when {
            text.startsWith(PANEL_SCHEME, ignoreCase = true) -> parsePanelUri(text)
            text.startsWith("[Interface]", ignoreCase = true) -> parseWireGuard(text)
            text.startsWith("Server:", ignoreCase = true) -> parsePanelCopyText(text)
            else -> null
        }
        return profile?.let { ImportResult.Profile(it) } ?: ImportResult.Unrecognized
    }

    private fun parsePanelUri(uri: String): VpnProfile? {
        val data = uri.substringAfter("data=", "").substringBefore('&').trim()
        if (data.isEmpty()) return null

        val json = runCatching {
            // Android's decoder treats the missing '=' padding as optional.
            JSONObject(String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        }.getOrNull() ?: return null
        // "certificate" was added under v1 as an optional field, so v1 is the only version to expect.
        if (json.optInt("v", 1) != 1) return null

        val server = json.str("server") ?: return null
        return when (json.str("type")?.lowercase()) {
            "ikev2" -> ikev2Profile(
                server = server,
                remoteId = json.str("remote_id"),
                psk = json.str("psk"),
                username = json.str("username"),
                password = json.str("password"),
                caCertificatePem = json.str("certificate"),
            )
            "l2tp" -> l2tpProfile(
                server = server,
                psk = json.str("psk"),
                username = json.str("username"),
                password = json.str("password"),
            )
            else -> null
        }
    }

    /**
     * The panel's Copy button text. Its IKEv2 cards always include a `Remote ID:` line (the panel
     * falls back to the host address) and its L2TP cards never do, which is how the two are told
     * apart. The admin view writes `PSK: —` when there is no PSK.
     */
    private fun parsePanelCopyText(text: String): VpnProfile? {
        val fields = text.lineSequence()
            .mapNotNull { line ->
                val key = line.substringBefore(':', "").trim().lowercase()
                // Split on the first ':' only: passwords and IPv6 addresses may contain more.
                val value = line.substringAfter(':', "").trim()
                if (key.isEmpty() || value.isEmpty() || value == "—") null else key to value
            }
            .toMap()

        val server = fields["server"] ?: return null
        val remoteId = fields["remote id"]
        return if (remoteId != null) {
            ikev2Profile(
                server = server,
                remoteId = remoteId,
                psk = fields["psk"],
                username = fields["username"],
                password = fields["password"],
                caCertificatePem = null,
            )
        } else {
            l2tpProfile(server, fields["psk"], fields["username"], fields["password"])
        }
    }

    private fun ikev2Profile(
        server: String,
        remoteId: String?,
        psk: String?,
        username: String?,
        password: String?,
        caCertificatePem: String?,
    ) = VpnProfile(
        id = UUID.randomUUID().toString(),
        name = remoteId ?: server,
        protocol = VpnProtocol.IKEV2,
        serverAddress = server,
        remoteIdentifier = remoteId,
        // The panel only includes psk when the Core runs in PSK mode (links/generator.py), while
        // every payload carries a username and password. Otherwise the server authenticates with
        // its certificate and each user logs in over EAP-MSCHAPv2.
        ikev2AuthType = if (psk != null) Ikev2AuthType.PSK else Ikev2AuthType.USERNAME_PASSWORD,
        presharedKey = psk,
        username = username,
        password = password,
        serverRootCaCertPem = caCertificatePem,
    )

    private fun l2tpProfile(server: String, psk: String?, username: String?, password: String?) = VpnProfile(
        id = UUID.randomUUID().toString(),
        name = server,
        protocol = VpnProtocol.L2TP,
        serverAddress = server,
        l2tpIpsecPresharedKey = psk,
        username = username,
        password = password,
    )

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
