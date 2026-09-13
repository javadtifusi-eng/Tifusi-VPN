package com.tifusi.vpn.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tifusi.vpn.BuildConfig
import com.tifusi.vpn.vpn.VpnFailure
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

// Separate from the profiles store: reports churn on every attempt, and a corrupt report queue must
// never be able to take the saved servers with it.
private val Context.reportDataStore by preferencesDataStore(name = "tifusi_connection_reports")

/**
 * One connection attempt or subscription fetch, as sent to the panel's `/app/report`. The field
 * names in [toJson] are the panel contract; do not rename them.
 */
data class ConnectionReport(
    /** Epoch ms when the result happened. */
    val at: Long,
    val event: String,
    val result: String,
    /** Short technical text, untranslated, already stripped of subscription secrets. */
    val detail: String,
    /** VpnProtocol name for connect events; empty for subscription fetches. */
    val protocol: String,
    /** From the connect tap (or fetch start) to the result; null when that is meaningless. */
    val durationMs: Long?,
    val network: String,
    val carrier: String,
    val simCarrier: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at)
        put("event", event)
        put("result", result)
        put("detail", detail)
        put("protocol", protocol)
        put("duration_ms", durationMs ?: JSONObject.NULL)
        put("network", network)
        put("carrier", carrier)
        put("sim_carrier", simCarrier)
    }

    companion object {
        const val EVENT_CONNECT = "connect"
        const val EVENT_SUBSCRIPTION_FETCH = "subscription_fetch"

        const val RESULT_CONNECTED = "connected"
        const val RESULT_FAILED = "failed"
        const val RESULT_TIMEOUT = "timeout"
        const val RESULT_DISCONNECTED_EARLY = "disconnected_early"
        const val RESULT_OK = "ok"

        fun fromJson(json: JSONObject): ConnectionReport = ConnectionReport(
            at = json.getLong("at"),
            event = json.getString("event"),
            result = json.getString("result"),
            detail = json.optString("detail"),
            protocol = json.optString("protocol"),
            durationMs = if (json.isNull("duration_ms")) null else json.getLong("duration_ms"),
            network = json.optString("network"),
            carrier = json.optString("carrier"),
            simCarrier = json.optString("sim_carrier"),
        )
    }
}

/**
 * The phone's own network when an attempt starts. Captured before the tunnel comes up, because
 * "IKEv2 fails on this carrier's mobile data but works on Wi-Fi" is exactly the pattern the owner
 * needs to see, and afterwards the active network is the VPN itself.
 */
data class NetworkSnapshot(
    /** "cellular", "wifi", "ethernet", "vpn", "none" or "other". */
    val network: String,
    /** The operator the phone is registered on; empty when unavailable. */
    val carrier: String,
    /** The operator named on the SIM, which differs from [carrier] when roaming. */
    val simCarrier: String,
) {
    companion object {
        /** Cheap binder calls only; still best kept off the main thread. */
        fun capture(context: Context): NetworkSnapshot {
            // Operator names need no READ_PHONE_STATE permission, unlike anything more specific.
            val telephony = try {
                context.getSystemService(TelephonyManager::class.java)
            } catch (e: Exception) {
                null
            }
            return NetworkSnapshot(
                network = networkType(context),
                carrier = runCatching { telephony?.networkOperatorName }.getOrNull().orEmpty(),
                simCarrier = runCatching { telephony?.simOperatorName }.getOrNull().orEmpty(),
            )
        }

        private fun networkType(context: Context): String {
            return try {
                val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return "other"
                val active = connectivity.activeNetwork ?: return "none"
                val capabilities = connectivity.getNetworkCapabilities(active) ?: return "none"
                when {
                    // First: a VPN network usually also carries its underlying transport.
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
            } catch (e: Exception) {
                "other"
            }
        }
    }
}

/**
 * The queue of reports not yet accepted by the panel, oldest first. Capped at [MAX_REPORTS]: a
 * phone that can never reach the panel must not grow the store forever, and the newest attempts
 * are the ones worth diagnosing.
 */
class ConnectionReportStore(private val context: Context) {

    private val reportsKey = stringPreferencesKey("reports")

    suspend fun add(report: ConnectionReport) {
        context.reportDataStore.edit { prefs ->
            // Records are written from independent coroutines, so arrival order is not guaranteed.
            val updated = (decode(prefs[reportsKey]) + report).sortedBy { it.at }.takeLast(MAX_REPORTS)
            prefs[reportsKey] = encode(updated)
        }
    }

    suspend fun pending(): List<ConnectionReport> = decode(context.reportDataStore.data.first()[reportsKey])

    /** Removes exactly the entries that were sent; reports added during the upload stay queued. */
    suspend fun remove(sent: List<ConnectionReport>) {
        val sentSet = sent.toSet()
        context.reportDataStore.edit { prefs ->
            prefs[reportsKey] = encode(decode(prefs[reportsKey]).filterNot { it in sentSet })
        }
    }

    private fun encode(reports: List<ConnectionReport>): String {
        val array = JSONArray()
        reports.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun decode(raw: String?): List<ConnectionReport> {
        if (raw == null) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching { ConnectionReport.fromJson(array.getJSONObject(i)) }.getOrNull()
        }
    }

    companion object {
        const val MAX_REPORTS = 50
    }
}

/**
 * Records what happened on each attempt and sends it to the panel by itself, so failures on phones
 * the owner never sees still reach him without anyone taking screenshots.
 *
 * Everything here is best effort: it runs on its own background scope, swallows every error, and
 * never touches connection state. Reports wait in [ConnectionReportStore] until the panel answers.
 */
object ConnectionReporter {

    // Reporting must never crash the app, whatever a device's DataStore or network stack throws.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> },
    )

    // Several triggers can fire together (a result and the launch upload); without the lock both
    // would send the same entries.
    private val uploadLock = Mutex()

    /** Shortly after a result, giving the DataStore write time to land first. */
    const val UPLOAD_SOON_MS = 3_000L

    /**
     * After a tunnel comes up. The panel can be blocked on the raw mobile network yet reachable
     * through the tunnel, which only carries traffic a moment after "Connected".
     */
    const val UPLOAD_THROUGH_TUNNEL_MS = 15_000L

    private const val TIMEOUT_MS = 10_000
    private const val MAX_DETAIL_LENGTH = 500

    /** Queues [report], then tries an upload after each delay in [uploadAfterMs]. */
    fun record(context: Context, report: ConnectionReport, uploadAfterMs: List<Long> = emptyList()) {
        val app = context.applicationContext
        scope.launch {
            runCatching { ConnectionReportStore(app).add(report) }
            uploadAfterMs.forEach { delayMs -> uploadLater(app, delayMs) }
        }
    }

    fun recordSubscriptionFetch(
        context: Context,
        network: NetworkSnapshot,
        startedElapsedMs: Long,
        error: Throwable?,
        uploadAfterMs: List<Long> = emptyList(),
    ) {
        val report = ConnectionReport(
            at = System.currentTimeMillis(),
            event = ConnectionReport.EVENT_SUBSCRIPTION_FETCH,
            result = if (error == null) ConnectionReport.RESULT_OK else ConnectionReport.RESULT_FAILED,
            detail = when (error) {
                null -> ""
                is SubscriptionError -> error.reportDetail()
                else -> sanitizeDetail("${error.javaClass.simpleName}: ${error.message}")
            },
            protocol = "",
            durationMs = SystemClock.elapsedRealtime() - startedElapsedMs,
            network = network.network,
            carrier = network.carrier,
            simCarrier = network.simCarrier,
        )
        record(context, report, uploadAfterMs)
    }

    fun uploadLater(context: Context, delayMs: Long) {
        val app = context.applicationContext
        scope.launch {
            delay(delayMs)
            uploadLock.withLock { runCatching { uploadPending(app) } }
        }
    }

    private suspend fun uploadPending(context: Context) {
        // The report is tied to a subscription on the panel; with none saved there is no one to
        // attribute it to, so it waits until the user imports one.
        val subscription = VpnProfileRepository(context).subscriptionUrl.first() ?: return
        val store = ConnectionReportStore(context)
        val pending = store.pending()
        if (pending.isEmpty()) return
        val panel = panelBase(subscription) ?: return

        val code = try {
            post("$panel/app/report", requestBody(subscription, pending))
        } catch (e: Exception) {
            // Offline or blocked: keep everything for the next trigger.
            return
        }
        when (code) {
            // Accepted, or the panel does not know this subscription and never will: either way
            // resending would be pointless.
            HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_NOT_FOUND -> store.remove(pending)
            else -> Unit
        }
    }

    /**
     * The panel that issued the saved subscription: everything before `/sub/` or `/code/` in its
     * endpoint, so a panel served under a path prefix still works.
     */
    private fun panelBase(subscription: String): String? {
        val endpoint = SubscriptionClient.normalize(subscription)
        val base = endpoint?.let {
            val cut = maxOf(it.lastIndexOf("/sub/"), it.lastIndexOf("/code/"))
            if (cut > 0) it.substring(0, cut) else null
        }
        return base ?: BuildConfig.DEFAULT_PANEL_URL.trimEnd('/').takeIf { it.isNotEmpty() }
    }

    private fun requestBody(subscription: String, reports: List<ConnectionReport>): String {
        val array = JSONArray()
        reports.forEach { array.put(it.toJson()) }
        return JSONObject().apply {
            put("subscription", subscription)
            put("app_version", BuildConfig.VERSION_NAME)
            put("android_sdk", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("reports", array)
        }.toString()
    }

    /** Blocking; only called from [scope]. */
    private fun post(url: String, body: String): Int {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "TifusiVPN-Android")
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private val SUBSCRIPTION_PATH = Regex("""(/(?:sub|code)/)[^/\s?#"']+""", RegexOption.IGNORE_CASE)
    private val HWID_QUERY = Regex("""(hwid=)[^&\s"']*""", RegexOption.IGNORE_CASE)

    /**
     * Exception messages can quote the request URL, which carries the subscription secret and the
     * device id. The subscription travels once, in its own field; it must not leak into details.
     */
    internal fun sanitizeDetail(text: String): String = text
        .replace(SUBSCRIPTION_PATH) { it.groupValues[1] + "***" }
        .replace(HWID_QUERY) { it.groupValues[1] + "***" }
        .take(MAX_DETAIL_LENGTH)
}

/** Technical description of a connect failure for [ConnectionReport.detail]. */
fun VpnFailure.reportDetail(): String = ConnectionReporter.sanitizeDetail(
    when (this) {
        is VpnFailure.Certificate -> "Certificate: ${problem.javaClass.simpleName}: ${problem.message}"
        VpnFailure.NegotiationFailed -> "NegotiationFailed: platform profile state FAILED"
        VpnFailure.Timeout -> "Timeout"
        is VpnFailure.Platform -> event.describe()
        VpnFailure.Deactivated -> "Deactivated: Android turned the VPN off (Settings or another VPN app)"
        is VpnFailure.Unknown -> "Unknown: $detail"
    },
)

/** Class name plus detail, e.g. "Network: HTTP 502" or "DeviceLimit". */
fun SubscriptionError.reportDetail(): String = ConnectionReporter.sanitizeDetail(
    when (this) {
        is SubscriptionError.Network -> "Network: $detail"
        else -> javaClass.simpleName
    },
)
