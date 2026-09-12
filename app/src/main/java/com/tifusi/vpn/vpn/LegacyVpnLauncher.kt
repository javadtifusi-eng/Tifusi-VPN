package com.tifusi.vpn.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * L2TP and PPTP have no public provisioning API — [android.net.VpnManager] only accepts
 * [android.net.Ikev2VpnProfile]. The platform keeps those profile types behind Settings, so the
 * best an unprivileged app can do is hold the credentials and hand the user off to the VPN
 * settings screen with the values ready to paste.
 */
object LegacyVpnLauncher {

    fun openVpnSettings(context: Context) {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // ACTION_VPN_SETTINGS is not guaranteed on every OEM build; fall back to the top-level
        // wireless settings screen, which always exists.
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    /** Copies a single field so the user can paste it straight into the Settings form. */
    fun copyField(context: Context, label: String, value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    /** The ordered field list the Settings VPN form asks for, per protocol. */
    fun fieldsFor(profile: VpnProfile): List<LegacyField> = buildList {
        add(LegacyField(LegacyFieldKey.NAME, profile.name))
        add(LegacyField(LegacyFieldKey.TYPE, profile.protocol.name))
        add(LegacyField(LegacyFieldKey.SERVER, profile.serverAddress))
        if (profile.protocol == VpnProtocol.L2TP) {
            profile.l2tpIpsecPresharedKey?.takeIf { it.isNotBlank() }?.let {
                add(LegacyField(LegacyFieldKey.IPSEC_PRESHARED_KEY, it))
            }
        }
        profile.username?.takeIf { it.isNotBlank() }?.let {
            add(LegacyField(LegacyFieldKey.USERNAME, it))
        }
        profile.password?.takeIf { it.isNotBlank() }?.let {
            add(LegacyField(LegacyFieldKey.PASSWORD, it))
        }
    }
}

data class LegacyField(val key: LegacyFieldKey, val value: String)

enum class LegacyFieldKey {
    NAME,
    TYPE,
    SERVER,
    IPSEC_PRESHARED_KEY,
    USERNAME,
    PASSWORD,
}
