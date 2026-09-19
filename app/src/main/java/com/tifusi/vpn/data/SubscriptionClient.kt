package com.tifusi.vpn.data

import android.content.Context
import android.provider.Settings
import com.tifusi.vpn.BuildConfig
import com.tifusi.vpn.vpn.CertificateStore
import com.tifusi.vpn.vpn.Ikev2AuthType
import com.tifusi.vpn.vpn.VlessLink
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

sealed class SubscriptionError(message: String) : Exception(message) {
    object NotASubscriptionLink : SubscriptionError("Not a subscription link")
    object NotFound : SubscriptionError("Subscription not found")
    object DeviceLimit : SubscriptionError("Device limit reached")
    object PanelOutdated : SubscriptionError("Panel has no app.json endpoint")
    object NoServers : SubscriptionError("No IKEv2, L2TP or VLESS servers")
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

    /**
     * Resolves a pasted link, or a code read out from the panel, to the panel endpoint base. A bare
     * code resolves against the panel this build ships with (tifusi.panelUrl in gradle.properties);
     * `CODE@panel.example.com` reaches any other panel.
     */
    fun normalize(input: String): String? {
        val value = input.trim()
        LINK.matchEntire(value)?.let { return "${it.groupValues[1]}/sub/${it.groupValues[2]}" }
        if ("://" in value || value.any { it.isWhitespace() || it == '/' }) return null
        val at = value.lastIndexOf('@')
        val code = if (at >= 0) value.substring(0, at) else value
        val panel = if (at >= 0) value.substring(at + 1).takeIf { it.isNotEmpty() }?.let { "https://$it" }
        else BuildConfig.DEFAULT_PANEL_URL.trimEnd('/').takeIf { it.isNotEmpty() }
        if (panel == null || code.length !in 9..128) return null
        return "$panel/code/" + URLEncoder.encode(code, "UTF-8").replace("+", "%20")
    }

    /** Blocking network call; invoke off the main thread. */
    fun fetchProfiles(context: Context, link: String): SubscriptionResult {
        val normalized = normalize(link) ?: throw SubscriptionError.NotASubscriptionLink
        // A stable per-install id, so the panel's device limit counts this phone once even as
        // its mobile IP changes between refreshes.
        val hwid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val connection = (URL("$normalized/app.json?hwid=" + URLEncoder.encode(hwid, "UTF-8"))
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TifusiVPN-Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (code) {
                in 200..299 -> Unit
                403 -> throw SubscriptionError.DeviceLimit
                // The panel's own lookup answers "Not found"; FastAPI's unknown-route answer is
                // "Not Found", meaning the panel predates this endpoint.
                404 -> throw if (body.contains("Not found")) SubscriptionError.NotFound else SubscriptionError.PanelOutdated
                else -> throw SubscriptionError.Network("HTTP $code")
            }
            val json = JSONObject(body)
            // The secret or code the request was made with; a locked subscription is sealed with it.
            val credential = URLDecoder.decode(normalized.substringAfterLast('/'), "UTF-8")
            val profiles = parseProfiles(json, credential).ifEmpty { throw SubscriptionError.NoServers }
            return SubscriptionResult(profiles, parseInfo(json))
        } catch (e: SubscriptionError) {
            throw e
        } catch (e: Exception) {
            throw SubscriptionError.Network(e.message)
        } finally {
            connection.disconnect()
        }
    }

    /** Every usable server in app.json, IKEv2 first; empty only when no array has one. */
    internal fun parseProfiles(json: JSONObject, credential: String? = null): List<VpnProfile> {
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
        val l2tp = json.optJSONArray("l2tp").objects().map { cfg ->
            val server = cfg.getString("server")
            VpnProfile(
                id = "${ID_PREFIX}l2tp:$server",
                name = cfg.str("remark") ?: server,
                protocol = VpnProtocol.L2TP,
                serverAddress = server,
                l2tpIpsecPresharedKey = cfg.str("psk"),
                username = cfg.str("username"),
                password = cfg.str("password"),
            )
        }
        // The panel lists VLESS inbounds as the same vless:// share links users paste into other
        // apps. A link this app cannot use is skipped, so one unusual inbound never blocks the rest.
        val vless = vlessProfiles(json.optJSONArray("vless").strings(), locked = false)
        // Config lock: the panel sends the links sealed instead (backend/app/subscription/lock.py).
        val sealed = json.str("sealed")?.let { blob ->
            val opened = credential?.let { runCatching { unseal(blob, it) }.getOrNull() }
            vlessProfiles(opened?.optJSONArray("vless").strings(), locked = true)
        }.orEmpty()
        return ikev2 + l2tp + (vless + sealed).distinctBy { it.id } // Ids key the server list; a duplicate would crash it.
    }

    // A link this app cannot use is skipped, so one unusual inbound never blocks the rest.
    private fun vlessProfiles(links: List<String>, locked: Boolean): List<VpnProfile> = links.mapNotNull { raw ->
        val link = runCatching { VlessLink.parse(raw) }.getOrNull() ?: return@mapNotNull null
        VpnProfile(
            id = "${ID_PREFIX}vless:${link.address}:${link.port}:${link.remark.orEmpty()}",
            name = link.remark ?: if (locked) "VLESS" else "${link.address}:${link.port}",
            protocol = VpnProtocol.VLESS,
            serverAddress = if (locked) "" else link.address,
            vlessLink = raw.trim(),
            locked = locked,
        )
    }

    /**
     * Opens a locked subscription's `sealed` blob: base64(nonce(12) || ciphertext || tag(16)),
     * AES-256-GCM with the key SHA-256("tifusi-config-lock/v1:" + credential), where credential
     * is the subscription secret or app code the request was made with.
     */
    internal fun unseal(blob: String, credential: String): JSONObject {
        val raw = Base64.getDecoder().decode(blob)
        val key = MessageDigest.getInstance("SHA-256").digest("tifusi-config-lock/v1:$credential".toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        return JSONObject(String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8))
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
}
