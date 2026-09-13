package com.tifusi.vpn.data

import com.tifusi.vpn.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** The newest published build and the fixed URL its APK downloads from. */
data class LatestRelease(val buildNumber: Int, val downloadUrl: String)

/**
 * Reads the latest GitHub release of [BuildConfig.UPDATE_REPO]. CI tags every build v<run number>
 * and also uses that number as versionCode (app/build.gradle.kts), so the two compare directly.
 */
object UpdateChecker {

    /** Blocking network call; invoke off the main thread. Null when no answer could be read. */
    fun latestRelease(): LatestRelease? = runCatching {
        val repo = BuildConfig.UPDATE_REPO.takeIf { it.isNotBlank() } ?: return null
        val connection = (URL("https://api.github.com/repos/$repo/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TifusiVPN-Android")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val number = json.getString("tag_name").removePrefix("v").toIntOrNull() ?: return null
            LatestRelease(number, "https://github.com/$repo/releases/latest/download/$APK_NAME")
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    val isEnabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    private const val TIMEOUT_MS = 15_000

    // Must match the asset name in .github/workflows/build-apk.yml.
    private const val APK_NAME = "tifusi-vpn.apk"
}
