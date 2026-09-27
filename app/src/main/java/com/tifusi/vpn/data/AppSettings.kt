package com.tifusi.vpn.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The tunnel options on the Settings screen. Read by XrayVpnService each time it builds a tunnel,
 * so a change takes effect on the next connect (or wake, with Stop on sleep).
 */
data class TunnelSettings(
    val logLevel: String = LOG_LEVELS.first(),
    val stopOnSleep: Boolean = false,
    val dns: String = DNS_PRESETS.first().first,
    val bypassIran: Boolean = false,
    val mtu: Int = MTUS.first(),
    // How the Configs list pings servers, as in V2Box: through the tunnel (connection), a TCP
    // handshake with the server, or a plain ICMP echo to it.
    val pingMethod: String = PING_METHODS.first(),
    val pingTimeoutSec: Int = 5,
    val pingConcurrency: Int = 5,
    val pingEndpoint: String = PING_ENDPOINTS.first().first,
    // IKEv2 through the built-in strongSwan engine instead of the phone's own IKEv2 client, which
    // is missing before Android 11 and passes no traffic on some phones (older Samsung builds).
    val ikev2BuiltIn: Boolean = true,
) {
    val dnsServers: List<String> get() = DNS_PRESETS.firstOrNull { it.first == dns }?.second ?: DNS_PRESETS.first().second
    val pingUrl: String get() = PING_ENDPOINTS.firstOrNull { it.first == pingEndpoint }?.second ?: PING_ENDPOINTS.first().second

    companion object {
        val LOG_LEVELS = listOf("none", "error", "warning", "info", "debug")
        val MTUS = listOf(1500, 1400, 1280)
        val PING_METHODS = listOf("connection", "tcp", "icmp")
        val PING_TIMEOUTS = 1..10
        val PING_CONCURRENCY = 1..15
        // Endpoints that answer 204 (or a tiny page) with no body, for the connection test.
        val PING_ENDPOINTS = listOf(
            "google" to "https://www.google.com/generate_204",
            "gstatic" to "https://www.gstatic.com/generate_204",
            "cloudflare" to "https://cp.cloudflare.com/generate_204",
            "apple" to "http://captive.apple.com/hotspot-detect.html",
        )
        val DNS_PRESETS = listOf(
            "Google + Cloudflare" to listOf("8.8.8.8", "1.1.1.1"),
            "Cloudflare" to listOf("1.1.1.1", "1.0.0.1"),
            "Google" to listOf("8.8.8.8", "8.8.4.4"),
            "Quad9" to listOf("9.9.9.9", "149.112.112.112"),
            "AdGuard" to listOf("94.140.14.14", "94.140.15.15"),
        )
    }
}

object AppSettings {
    private const val PREFS = "app_settings"

    private val _state = MutableStateFlow(TunnelSettings())
    val state: StateFlow<TunnelSettings> = _state.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context): TunnelSettings {
        if (!loaded) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = TunnelSettings()
            _state.value = TunnelSettings(
                logLevel = p.getString("log_level", d.logLevel)!!.takeIf { it in TunnelSettings.LOG_LEVELS } ?: d.logLevel,
                stopOnSleep = p.getBoolean("stop_on_sleep", d.stopOnSleep),
                dns = p.getString("dns", d.dns)!!,
                bypassIran = p.getBoolean("bypass_iran", d.bypassIran),
                mtu = p.getInt("mtu", d.mtu).takeIf { it in TunnelSettings.MTUS } ?: d.mtu,
                pingMethod = p.getString("ping_method", d.pingMethod)!!.takeIf { it in TunnelSettings.PING_METHODS } ?: d.pingMethod,
                pingTimeoutSec = p.getInt("ping_timeout", d.pingTimeoutSec).coerceIn(TunnelSettings.PING_TIMEOUTS),
                pingConcurrency = p.getInt("ping_concurrency", d.pingConcurrency).coerceIn(TunnelSettings.PING_CONCURRENCY),
                pingEndpoint = p.getString("ping_endpoint", d.pingEndpoint)!!.takeIf { e -> TunnelSettings.PING_ENDPOINTS.any { it.first == e } } ?: d.pingEndpoint,
                ikev2BuiltIn = p.getBoolean("ikev2_builtin", d.ikev2BuiltIn),
            )
            loaded = true
        }
        return _state.value
    }

    fun update(context: Context, change: (TunnelSettings) -> TunnelSettings) {
        val next = change(load(context))
        _state.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("log_level", next.logLevel)
            .putBoolean("stop_on_sleep", next.stopOnSleep)
            .putString("dns", next.dns)
            .putBoolean("bypass_iran", next.bypassIran)
            .putInt("mtu", next.mtu)
            .putString("ping_method", next.pingMethod)
            .putInt("ping_timeout", next.pingTimeoutSec)
            .putInt("ping_concurrency", next.pingConcurrency)
            .putString("ping_endpoint", next.pingEndpoint)
            .putBoolean("ikev2_builtin", next.ikev2BuiltIn)
            .apply()
    }
}
