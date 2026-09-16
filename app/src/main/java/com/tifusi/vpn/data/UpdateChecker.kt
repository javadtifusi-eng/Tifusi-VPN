package com.tifusi.vpn.data

import android.os.Build
import com.tifusi.vpn.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** The newest published build and the fixed URL its APK downloads from. */
data class LatestRelease(val buildNumber: Int, val downloadUrl: String) {
    // Same mapping as versionName in app/build.gradle.kts.
    val versionName: String get() = "1.${(buildNumber - BuildConfig.VERSION_BASE).coerceAtLeast(0)}"
}

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
            val url = abiApkUrl(json.optJSONArray("assets"))
                ?: "https://github.com/$repo/releases/latest/download/$APK_NAME"
            LatestRelease(number, url)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    val isEnabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /**
     * The release's APK for this phone's architecture, about half the size of the universal one.
     * Only a name the release actually carries is returned, so a build published without the
     * per-ABI files falls back to the universal APK instead of a dead link.
     */
    private fun abiApkUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        val byName = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .associateBy({ it.optString("name") }, { it.optString("browser_download_url") })
        // In preference order: a 64-bit phone lists arm64-v8a first and armeabi-v7a after it.
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            byName["tifusi-vpn-$abi.apk"]?.takeIf { it.isNotBlank() }
        }
    }

    private const val TIMEOUT_MS = 15_000

    // Must match the asset names in .github/workflows/build-apk.yml.
    private const val APK_NAME = "tifusi-vpn.apk"
}
