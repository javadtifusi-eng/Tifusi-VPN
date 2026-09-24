package com.tifusi.vpn.data

import android.content.Context
import android.provider.Settings
import com.tifusi.vpn.BuildConfig
import com.tifusi.vpn.vpn.CertificateStore
import com.tifusi.vpn.vpn.Ikev2AuthType
import com.tifusi.vpn.vpn.Hysteria2Link
import com.tifusi.vpn.vpn.VlessLink
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.json.JSONArray
import org.json.JSONObject

sealed class SubscriptionError(message: String) : Exception(message) {
    object NotASubscriptionLink : SubscriptionError("Not a subscription link")
    object NotFound : SubscriptionError("Subscription not found")
    object DeviceLimit : SubscriptionError("Device limit reached")
    object PanelOutdated : SubscriptionError("Panel has no app.json endpoint")
    object NoServers : SubscriptionError("No IKEv2, VLESS or Hysteria2 servers")
    data class Network(val detail: String?) : SubscriptionError("Network: $detail")
}

/** The account limits the panel reported with the subscription, as of [fetchedAtMs]. */
data class SubscriptionInfo(
    val username: String?,
    val status: String?,
    /** Unix seconds; null means the account never expires. */
    val expireEpochSec: Long?,
    val usedBytes: Long,
    /** Null means unlimited data. */
    val limitBytes: Long?,
    val fetchedAtMs: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("username", username)
        put("status", status)
        put("expire", expireEpochSec)
        put("used", usedBytes)
        put("limit", limitBytes)
        put("fetchedAt", fetchedAtMs)
    }.toString()

    companion object {
        fun fromJson(raw: String): SubscriptionInfo? = runCatching {
            val json = JSONObject(raw)
            SubscriptionInfo(
                username = json.optString("username").takeIf { it.isNotBlank() && it != "null" },
                status = json.optString("status").takeIf { it.isNotBlank() && it != "null" },
                expireEpochSec = if (json.isNull("expire")) null else json.optLong("expire"),
                usedBytes = json.optLong("used"),
                limitBytes = if (json.isNull("limit")) null else json.optLong("limit"),
                fetchedAtMs = json.optLong("fetchedAt"),
            )
        }.getOrNull()
    }
}

data class SubscriptionResult(val profiles: List<VpnProfile>, val info: SubscriptionInfo)

/**
 * Imports a Tifusi Panel subscription from either the link (`<panel>/sub/<secret>`) or the short
 * app code shown on the user's subscription page (e.g. `javad7KQ4MP9X`), fetched as
 * `<panel>/sub/<secret>/app.json` or `<panel>/code/<code>/app.json`
 * (backend/app/routers/subscription.py). Either value is the only credential, like the panel's
 * own subscription link.
 */
object SubscriptionClient {

    /** Profiles from a subscription carry this id prefix so a refresh can replace just them. */
    const val ID_PREFIX = "sub:"

    private val LINK = Regex("""^(https?://\S+?)/sub/([A-Za-z0-9-]{16,})/?(?:[?#]\S*)?$""", RegexOption.IGNORE_CASE)

    /** Another panel's subscription link (PasarGuard, Marzban, …), read as a standard subscription. */
    private val OTHER_LINK = Regex("""^https?://[^\s/?#]+/\S+$""", RegexOption.IGNORE_CASE)

    /**
     * Resolves a pasted link, or a code read out from the panel, to the panel endpoint base. The
     * app carries no panel address of its own: a code from the panel has its host in it (see
     * [splitEmbeddedHost]), like the QR code has the whole link, so moving to a new domain never
     * needs a new build. `CODE@panel.example.com` is still accepted, and a bare code only when
     * this build was given a default panel (tifusi.panelUrl in gradle.properties; empty by default).
     */
    fun normalize(input: String): String? {
        val value = input.trim()
        LINK.matchEntire(value)?.let { return "${it.groupValues[1]}/sub/${it.groupValues[2]}" }
        if (OTHER_LINK.matches(value)) return value
        if ("://" in value || value.any { it.isWhitespace() || it == '/' }) return null
        val at = value.lastIndexOf('@')
        val typed = if (at >= 0) value.substring(0, at) else value
        val embedded = splitEmbeddedHost(typed)
        val code = embedded?.first ?: typed
        val panel = when {
            at >= 0 -> value.substring(at + 1).takeIf { it.isNotEmpty() }?.let { "https://$it" }
            embedded != null -> "https://${embedded.second}"
            else -> BuildConfig.DEFAULT_PANEL_URL.trimEnd('/').takeIf { it.isNotEmpty() }
        }
        if (panel == null || code.length !in 9..128) return null
        return "$panel/code/" + URLEncoder.encode(code, "UTF-8").replace("+", "%20")
    }

    private val HOST = Regex("""^[a-z0-9.-]+\.[a-z0-9-]+(:\d{1,5})?$""")

    /**
     * The panel hands a code out as `CODE-<host in base32>`. The host is encoded rather than
     * written out because the code travels over SMS, where a readable domain risks being
     * filtered. Returns the bare code and the host, or null when there is no host on the value
     * (older codes, or a username that contains a dash).
     */
    internal fun splitEmbeddedHost(value: String): Pair<String, String>? {
        val dash = value.lastIndexOf('-')
        if (dash <= 0 || dash == value.length - 1) return null
        val host = decodeBase32(value.substring(dash + 1)) ?: return null
        return if (HOST.matches(host)) value.substring(0, dash) to host else null
    }

    /** RFC 4648 base32, upper or lower case, padding optional; null on any other character. */
    internal fun decodeBase32(text: String): String? {
        var buffer = 0
        var bits = 0
        val out = StringBuilder()
        for (ch in text.uppercase()) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in '2'..'7' -> ch - '2' + 26
                else -> return null
            }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toString().lowercase()
    }

    /** Blocking network call; invoke off the main thread. */
    fun fetchProfiles(context: Context, link: String): SubscriptionResult {
        val normalized = normalize(link) ?: throw SubscriptionError.NotASubscriptionLink
        // Not a Tifusi Panel link: there is no app.json to ask for, only the standard subscription.
        if (!LINK.matches(normalized) && "/code/" !in normalized) return fetchStandard(normalized, withPageIkev2 = true)
        // A stable per-install id, so the panel's device limit counts this phone once even as
        // its mobile IP changes between refreshes.
        val hwid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val ours = httpGet("$normalized/app.json?hwid=" + URLEncoder.encode(hwid, "UTF-8"), "application/json")
        when (ours.status) {
            in 200..299 -> {
                val json = runCatching { JSONObject(ours.body) }.getOrNull()
                if (json != null) {
                    val profiles = parseProfiles(json).ifEmpty { throw SubscriptionError.NoServers }
                    return SubscriptionResult(profiles, parseInfo(json))
                }
            }
            403 -> throw SubscriptionError.DeviceLimit
            // This project's panel answers a wrong code with "Not found"; any other 404 (FastAPI's
            // "Not Found", another panel's own page) just means there is no app.json here.
            404 -> if (ours.body.contains("Not found")) throw SubscriptionError.NotFound
            else -> throw SubscriptionError.Network("HTTP ${ours.status}")
        }
        // Not this project's panel, or one that predates app.json. The app is not tied to one
        // panel, so fall back to the standard subscription every panel serves at /sub/<token>.
        return fetchStandard(standardUrl(normalized))
    }

    private fun fetchStandard(url: String, withPageIkev2: Boolean = false): SubscriptionResult {
        val plain = httpGet(url, "*/*")
        if (plain.status !in 200..299) throw SubscriptionError.PanelOutdated
        val json = standardSubscriptionJson(plain.body, plain.userinfo)
        if (withPageIkev2) pageIkev2(url)?.let { json.put("ikev2", JSONArray().put(it)) }
        val profiles = parseProfiles(json).ifEmpty { throw SubscriptionError.NoServers }
        return SubscriptionResult(profiles, parseInfo(json))
    }

    private val PAGE_IKEV2 = Regex("""<script type="application/json" id="tifusi-ikev2">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The IKEv2 login another panel's subscription page publishes for this app (the PasarGuard
     * IKEv2 add-on puts it there), fetched as the page a browser would get. Null when there is none.
     */
    private fun pageIkev2(url: String): JSONObject? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        // This extra fetch is best-effort and only adds IKEv2, so it uses a short timeout: it must
        // never hold up the whole subscription refresh (or make the panel look unreachable) when the
        // page is slow. A failure here just means no IKEv2 this time; the servers still import.
        connection.connectTimeout = PAGE_TIMEOUT_MS
        connection.readTimeout = PAGE_TIMEOUT_MS
        connection.setRequestProperty("Accept", "text/html")
        connection.setRequestProperty("User-Agent", "TifusiVPN-Android")
        try {
            if (connection.responseCode !in 200..299) return null
            // The login sits at the top of the page, which can be over a megabyte on the whole: read
            // only the start, so a slow mobile link neither waits for nor fails on the rest.
            val head = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (head.length < PAGE_HEAD_LIMIT) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    head.append(buffer, 0, n)
                    if ("</script>" in head && PAGE_IKEV2.containsMatchIn(head)) break
                }
            }
            val raw = PAGE_IKEV2.find(head)?.groupValues?.get(1) ?: return null
            JSONObject(raw).takeIf { it.optString("type") == "ikev2" && it.optString("server").isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private class HttpResult(val status: Int, val body: String, val userinfo: String?)

    private fun httpGet(url: String, accept: String): HttpResult {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "TifusiVPN-Android")
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return HttpResult(status, body, connection.getHeaderField("Subscription-Userinfo"))
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            throw SubscriptionError.Network(e.message)
        }
    }

    /** `<panel>/code/<code>` has no standard form; a code that is a panel's own token maps to `/sub/<code>`. */
    private fun standardUrl(normalized: String): String =
        if ("/code/" in normalized) normalized.replaceFirst("/code/", "/sub/") else normalized

    /**
     * A standard subscription (what any panel serves at /sub/<token>): share links one per line,
     * plain or base64, with usage in the `Subscription-Userinfo` header. Only what this app can
     * dial is kept, as the same arrays [parseProfiles] reads from app.json.
     */
    internal fun standardSubscriptionJson(body: String, userinfo: String?): JSONObject {
        val lines = subscriptionLines(body)
        val json = JSONObject()
        json.put("vless", JSONArray(lines.filter { it.startsWith("vless://", ignoreCase = true) }))
        json.put("hysteria2", JSONArray(lines.filter { Hysteria2Link.isHysteria2(it) }))
        val fields = userinfo.orEmpty().split(';').mapNotNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) return@mapNotNull null
            kv[1].trim().toLongOrNull()?.let { value -> kv[0].trim().lowercase() to value }
        }.toMap()
        json.put("used_traffic", (fields["upload"] ?: 0L) + (fields["download"] ?: 0L))
        fields["total"]?.takeIf { it > 0 }?.let { json.put("data_limit", it) }
        fields["expire"]?.takeIf { it > 0 }?.let { json.put("expire", it) }
        return json
    }

    private fun subscriptionLines(body: String): List<String> {
        val text = body.trim()
        val decoded = if ("://" in text) text else runCatching {
            String(java.util.Base64.getMimeDecoder().decode(text.replace('-', '+').replace('_', '/')))
        }.getOrDefault(text)
        return decoded.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** Every usable server in app.json, IKEv2 first; empty only when no array has one. */
    internal fun parseProfiles(json: JSONObject): List<VpnProfile> {
        val ikev2 = json.optJSONArray("ikev2").objects().map { cfg ->
            val server = cfg.getString("server")
            val psk = cfg.str("psk")
            VpnProfile(
                id = "${ID_PREFIX}ikev2:$server",
                name = cfg.str("remark") ?: server,
                protocol = VpnProtocol.IKEV2,
                serverAddress = server,
                remoteIdentifier = cfg.str("remote_id"),
                // The panel sends psk only for PSK-mode Cores; otherwise it is EAP-MSCHAPv2.
                ikev2AuthType = if (psk != null) Ikev2AuthType.PSK else Ikev2AuthType.USERNAME_PASSWORD,
                presharedKey = psk,
                username = cfg.str("username"),
                password = cfg.str("password"),
                serverRootCaCertPem = cfg.str("certificate")?.let(::leafIssuerPem),
            )
        }
        // The panel lists VLESS inbounds as the same vless:// share links users paste into other
        // apps. A link this app cannot use is skipped, so one unusual inbound never blocks the rest.
        val vless = json.optJSONArray("vless").strings().mapNotNull { raw ->
            val link = runCatching { VlessLink.parse(raw) }.getOrNull() ?: return@mapNotNull null
            VpnProfile(
                id = "${ID_PREFIX}vless:${link.address}:${link.port}:${link.remark.orEmpty()}",
                name = link.remark ?: "${link.address}:${link.port}",
                protocol = VpnProtocol.VLESS,
                serverAddress = link.address,
                vlessLink = raw.trim(),
            )
        }.distinctBy { it.id } // Ids key the server list; a duplicate would crash it.
        // Same rule as VLESS: a link this app cannot use is skipped rather than failing the refresh.
        val hysteria2 = json.optJSONArray("hysteria2").strings().mapNotNull { raw ->
            val link = runCatching { Hysteria2Link.parse(raw) }.getOrNull() ?: return@mapNotNull null
            VpnProfile(
                id = "${ID_PREFIX}hysteria2:${link.address}:${link.port}:${link.remark.orEmpty()}",
                name = link.remark ?: "${link.address}:${link.port}",
                protocol = VpnProtocol.HYSTERIA2,
                serverAddress = link.address,
                hysteria2Link = raw.trim(),
            )
        }.distinctBy { it.id }
        return ikev2 + vless + hysteria2
    }

    /**
     * The certificate that issued the server's own certificate, pinned as the trust anchor.
     * strongSwan sends only its end-entity certificate, and Android's IKE stack neither fetches
     * nor ships intermediates, so a publicly issued leaf (Let's Encrypt's YR1 chains to Root YR,
     * which older stores lack) never validates against the system roots: iOS completes the chain
     * itself, Android silently fails IKE_AUTH. For the panel's self-signed bundle the issuer is
     * its CA. If the server renews onto another intermediate, refreshing the subscription
     * picks it up.
     */
    private fun leafIssuerPem(pem: String): String? = runCatching {
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(pem.toByteArray()))
            .filterIsInstance<X509Certificate>()
        // basicConstraints < 0 marks a non-CA certificate, i.e. the server's own.
        val leaf = certificates.firstOrNull { it.basicConstraints < 0 } ?: certificates.first()
        certificates.firstOrNull { it.subjectX500Principal == leaf.issuerX500Principal }
            ?.let(CertificateStore::toPem)
    }.getOrNull()

    private fun parseInfo(json: JSONObject) = SubscriptionInfo(
        username = json.str("username"),
        status = json.str("status"),
        expireEpochSec = if (json.isNull("expire") || !json.has("expire")) null else json.optLong("expire"),
        usedBytes = json.optLong("used_traffic"),
        limitBytes = if (json.isNull("data_limit") || !json.has("data_limit")) null else json.optLong("data_limit"),
        fetchedAtMs = System.currentTimeMillis(),
    )

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private const val TIMEOUT_MS = 15_000
    // The best-effort IKEv2-from-page fetch: kept short so it can't stall a refresh.
    private const val PAGE_TIMEOUT_MS = 4_000
    private const val PAGE_HEAD_LIMIT = 256 * 1024
}
