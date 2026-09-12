package com.tifusi.vpn.vpn

import android.app.Service
import android.content.Intent
import android.net.LinkProperties
import android.net.VpnManager
import android.os.Build
import android.os.IBinder
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** What the platform reported about the provisioned IKEv2 profile (API 33+). */
data class PlatformVpnEvent(
    val category: String,
    val errorClass: Int,
    val errorCode: Int,
    /** Null when the platform sent no underlying link info. */
    val underlyingHasIpv4: Boolean?,
) {
    val isRecoverable: Boolean get() = errorClass == VpnManager.ERROR_CLASS_RECOVERABLE

    /** Technical, untranslated description: it is meant to be screenshotted and diagnosed. */
    fun describe(): String {
        val what = when (category) {
            VpnManager.CATEGORY_EVENT_NETWORK_ERROR -> "NETWORK_ERROR / " + when (errorCode) {
                VpnManager.ERROR_CODE_NETWORK_UNKNOWN_HOST -> "UNKNOWN_HOST"
                VpnManager.ERROR_CODE_NETWORK_PROTOCOL_TIMEOUT -> "PROTOCOL_TIMEOUT"
                VpnManager.ERROR_CODE_NETWORK_LOST -> "NETWORK_LOST"
                VpnManager.ERROR_CODE_NETWORK_IO -> "NETWORK_IO"
                else -> "code $errorCode"
            }
            VpnManager.CATEGORY_EVENT_IKE_ERROR -> "IKE_ERROR / " + when (errorCode) {
                14 -> "NO_PROPOSAL_CHOSEN"
                17 -> "INVALID_KE_PAYLOAD"
                24 -> "AUTHENTICATION_FAILED"
                38 -> "TS_UNACCEPTABLE"
                else -> "type $errorCode"
            }
            VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER -> "DEACTIVATED_BY_USER"
            VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED -> "ALWAYS_ON_STATE_CHANGED"
            else -> category.substringAfterLast('.')
        }
        val network = when (underlyingHasIpv4) {
            true -> "IPv4 network"
            false -> "IPv6-only network"
            null -> "network unknown"
        }
        return "$what · $network"
    }
}

object VpnEvents {
    private val _events = MutableSharedFlow<PlatformVpnEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlatformVpnEvent> = _events.asSharedFlow()

    fun publish(event: PlatformVpnEvent) {
        _events.tryEmit(event)
    }
}

/**
 * The platform delivers VpnManager.ACTION_VPN_MANAGER_EVENT with startService, not a broadcast,
 * so a BroadcastReceiver would never see it. The manifest filter must list all four categories
 * because an intent only matches when every one of its categories is in the filter.
 */
class VpnEventService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            parse(intent)?.let(VpnEvents::publish)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun parse(intent: Intent): PlatformVpnEvent? {
        if (intent.action != VpnManager.ACTION_VPN_MANAGER_EVENT) return null
        val category = intent.categories?.firstOrNull() ?: return null
        val link = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(VpnManager.EXTRA_UNDERLYING_LINK_PROPERTIES, LinkProperties::class.java)
        } else {
            null
        }
        return PlatformVpnEvent(
            category = category,
            errorClass = intent.getIntExtra(VpnManager.EXTRA_ERROR_CLASS, -1),
            errorCode = intent.getIntExtra(VpnManager.EXTRA_ERROR_CODE, -1),
            underlyingHasIpv4 = link?.linkAddresses?.any { it.address is Inet4Address },
        )
    }
}
