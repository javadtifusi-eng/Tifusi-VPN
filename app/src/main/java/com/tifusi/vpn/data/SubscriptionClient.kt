package com.tifusi.vpn.data

import android.content.Context
import android.provider.Settings
import com.tifusi.vpn.vpn.CertificateStore
import com.tifusi.vpn.vpn.Ikev2AuthType
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
    object NoServers : SubscriptionError("No IKEv2 or L2TP servers")
    data class Network(val detail: String?) : SubscriptionError("Network: $detail")
}

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

    /** Resolves a pasted link, or a code read out from the panel, to the panel endpoint base. */
    fun normalize(input: String): String? {
        val value = input.trim()
        LINK.matchEntire(value)?.let { return "${it.groupValues[1]}/sub/${it.groupValues[2]}" }
        if ("://" in value || value.length !in 9..128 || value.any { it.isWhitespace() || it == '/' }) return null
        return "$DEFAULT_PANEL_URL/code/" + URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    /** Blocking network call; invoke off the main thread. */
    fun fetchProfiles(context: Context, link: String): List<VpnProfile> {
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
            return parse(JSONObject(body)).ifEmpty { throw SubscriptionError.NoServers }
        } catch (e: SubscriptionError) {
            throw e
        } catch (e: Exception) {
            throw SubscriptionError.Network(e.message)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: JSONObject): List<VpnProfile> {
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
        return ikev2 + l2tp
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

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private const val TIMEOUT_MS = 15_000

    // A code carries no panel address, so codes resolve against this panel.
    private const val DEFAULT_PANEL_URL = "https://ge.koledemb.ir"
}
