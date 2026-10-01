package com.tifusi.vpn.data

import android.content.Context
import com.tifusi.vpn.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** The newest published build and the fixed URL its APK downloads from. */
data class LatestRelease(val buildNumber: Int, val downloadUrl: String) {
    // Same mapping as versionName in app/build.gradle.kts.
    val versionName: String get() = "1.${(buildNumber - BuildConfig.VERSION_BASE).coerceAtLeast(0)}"
}

/**
 * Asks the panel the app's subscription points at (`<panel>/app/latest`) for the newest build, and
 * downloads it from there too: the panel keeps a copy of the GitHub release, and its customer-facing
 * domain opens from Iran where api.github.com and github.com are slow or half-filtered. GitHub is
 * only the fallback for a phone with no subscription yet. CI tags every build v<run number> and uses
 * that number as versionCode (app/build.gradle.kts), so the two compare directly.
 *
 * An automatic check runs at most once a day; the answer is remembered in between so opening the
 * About page does not spend the user's data every time.
 */
object UpdateChecker {

    val isEnabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Blocking network calls; invoke off the main thread. Null when no answer could be read. */
    suspend fun latestRelease(context: Context, force: Boolean): LatestRelease? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_CHECKED_AT, 0) < DAY_MS) {
            val build = prefs.getInt(KEY_BUILD, 0)
            val url = prefs.getString(KEY_URL, null)
            if (build > 0 && url != null) return LatestRelease(build, url)
        }
        val subscription = VpnProfileRepository(context).subscriptionUrl.first()
        val fromPanel = subscription?.let(ConnectionReporter::panelBase)?.let(::fromPanel)
        val release = fromPanel ?: fromGitHub() ?: return null
        prefs.edit()
            .putLong(KEY_CHECKED_AT, now)
            .putInt(KEY_BUILD, release.buildNumber)
            .putString(KEY_URL, release.downloadUrl)
            .apply()
        return release
    }

    private fun fromPanel(base: String): LatestRelease? = runCatching {
        val json = getJson("$base/app/latest") ?: return null
        LatestRelease(json.getInt("build"), base + json.getString("path"))
    }.getOrNull()

    private fun fromGitHub(): LatestRelease? = runCatching {
        val repo = BuildConfig.UPDATE_REPO.takeIf { it.isNotBlank() } ?: return null
        val json = getJson("https://api.github.com/repos/$repo/releases/latest") ?: return null
        val number = json.getString("tag_name").removePrefix("v").toIntOrNull() ?: return null
        LatestRelease(number, "https://github.com/$repo/releases/latest/download/$APK_NAME")
    }.getOrNull()

    private fun getJson(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TifusiVPN-Android")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    // Short: this is a background nicety, never worth a long spinner on a slow line.
    private const val TIMEOUT_MS = 6_000
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val PREFS = "update_check"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_BUILD = "build"
    private const val KEY_URL = "url"

    // Must match the asset name in .github/workflows/build-apk.yml.
    private const val APK_NAME = "tifusi-vpn.apk"
}
