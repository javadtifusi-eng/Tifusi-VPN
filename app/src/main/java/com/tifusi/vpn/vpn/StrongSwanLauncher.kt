package com.tifusi.vpn.vpn

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tifusi.vpn.R
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * IKEv2 through the strongSwan app instead of the platform client. Some phones (older Samsung
 * builds) complete the platform IKEv2 handshake but never pass a packet through it; strongSwan
 * brings its own IKE and ESP stack. The first time a profile is handed over it is imported from a
 * .sswan file (strongSwan's own import screen, where the user adds the password, which the file
 * format cannot carry); after that it is started directly by its UUID.
 */
object StrongSwanLauncher {
    const val PACKAGE = "org.strongswan.android"
    private const val MIME = "application/vnd.strongswan.profile"
    private const val ACTION_START = "org.strongswan.android.action.START_PROFILE"
    private const val EXTRA_PROFILE_ID = "org.strongswan.android.VPN_PROFILE_ID"
    private const val PREFS = "strongswan_handoff"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Only username/password and certificate profiles: strongSwan for Android has no PSK. */
    fun supports(profile: VpnProfile): Boolean =
        profile.protocol == VpnProtocol.IKEV2 && (!profile.username.isNullOrBlank() || !profile.pkcs12Base64.isNullOrBlank())

    fun launch(context: Context, profile: VpnProfile) {
        if (!isInstalled(context)) {
            openStore(context)
            return
        }
        val uuid = UUID.nameUUIDFromBytes("tifusi:${profile.id}".toByteArray()).toString()
        val json = profileJson(profile, uuid).toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Re-import whenever the profile changed, so a new server or username reaches strongSwan.
        if (prefs.getString(uuid, null) == json.hashCode().toString()) {
            val start = Intent(ACTION_START).setPackage(PACKAGE)
                .putExtra(EXTRA_PROFILE_ID, uuid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(start) }.isSuccess) return
        }
        val dir = File(context.cacheDir, "sswan").apply { mkdirs() }
        val file = File(dir, "tifusi.sswan").apply { writeText(json) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val import = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME).setPackage(PACKAGE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(import)
        } catch (e: ActivityNotFoundException) {
            openStore(context)
            return
        }
        prefs.edit().putString(uuid, json.hashCode().toString()).apply()
        profile.password?.takeIf { it.isNotBlank() }?.let { password ->
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("password", password))
            Toast.makeText(context, R.string.strongswan_password_copied, Toast.LENGTH_LONG).show()
        }
    }

    private fun profileJson(profile: VpnProfile, uuid: String): JSONObject {
        val remote = JSONObject().put("addr", profile.serverAddress)
        profile.remoteIdentifier?.takeIf { it.isNotBlank() }?.let { remote.put("id", it) }
        profile.serverRootCaCertPem?.let(::pemToBase64Der)?.let { remote.put("cert", it) }
        val local = JSONObject()
        val type = if (!profile.username.isNullOrBlank()) {
            local.put("eap_id", profile.username)
            "ikev2-eap"
        } else {
            local.put("p12", profile.pkcs12Base64)
            "ikev2-cert"
        }
        return JSONObject()
            .put("uuid", uuid)
            .put("name", "Tifusi · ${profile.name}")
            .put("type", type)
            .put("remote", remote)
            .put("local", local)
    }

    private fun pemToBase64Der(pem: String): String? {
        val body = pem.substringAfter("-----BEGIN CERTIFICATE-----", "")
            .substringBefore("-----END CERTIFICATE-----").filterNot(Char::isWhitespace)
        if (body.isEmpty()) return null
        return runCatching { Base64.encodeToString(Base64.decode(body, Base64.DEFAULT), Base64.NO_WRAP) }.getOrNull()
    }

    private fun openStore(context: Context) {
        Toast.makeText(context, R.string.strongswan_install, Toast.LENGTH_LONG).show()
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
