package com.tifusi.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnManager
import android.net.VpnService
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single entry point the UI uses to bring a tunnel up or down, regardless of protocol. Hides the
 * fact that IKEv2 goes through the platform VpnManager, WireGuard through its own backend, and
 * L2TP/PPTP can only be handed off to Settings.
 *
 * [connect] and [disconnect] may block (the WireGuard backend waits for its service), so callers
 * must invoke them off the main thread.
 */
class VpnController(private val context: Context) {

    private val wireGuardManager by lazy { WireGuardVpnManager(context) }
    private val ikev2Manager: Ikev2VpnManager? =
        if (Ikev2VpnManager.isSupported()) Ikev2VpnManager(context) else null

    private val _state = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
    val state: StateFlow<VpnConnectionState> = _state.asStateFlow()

    private var activeProtocol: VpnProtocol? = null
    private var connectedSinceElapsedMs: Long? = null
    private var connectingSinceElapsedMs: Long? = null
    private var lastPlatformEvent: PlatformVpnEvent? = null
    private var connectionCallback: ConnectivityManager.NetworkCallback? = null

    init {
        // On Android 11-12 there is no profile-state API, so the appearance of a VPN network is
        // the only signal that the IKEv2 tunnel actually came up.
        connectionCallback = ikev2Manager?.observeConnectionState { connected ->
            if (activeProtocol != VpnProtocol.IKEV2) return@observeConnectionState
            if (connected && _state.value is VpnConnectionState.Connecting) {
                markConnected()
            } else if (!connected && _state.value is VpnConnectionState.Connected) {
                markDisconnected()
            }
        }
    }

    /**
     * Starts a connection. When the platform still needs the one-time VPN consent dialog this
     * returns the [Intent] to launch; the caller re-invokes [connect] once the user accepts.
     */
    fun connect(profile: VpnProfile): Intent? {
        val blocking = VpnProfileValidator.validate(profile).filter { it.isBlocking }
        if (blocking.isNotEmpty()) {
            _state.value = VpnConnectionState.Invalid(blocking)
            return null
        }

        return when (profile.protocol) {
            VpnProtocol.IKEV2 -> connectIkev2(profile)
            VpnProtocol.WIREGUARD -> connectWireGuard(profile)
            VpnProtocol.L2TP, VpnProtocol.PPTP -> {
                _state.value = VpnConnectionState.RequiresSystemSettings(profile)
                null
            }
        }
    }

    fun disconnect(profile: VpnProfile) {
        when (profile.protocol) {
            VpnProtocol.IKEV2 -> ikev2Manager?.disconnect()
            VpnProtocol.WIREGUARD -> runCatching { wireGuardManager.disconnect() }
            // Nothing app-side to tear down; the tunnel lives entirely in Settings.
            VpnProtocol.L2TP, VpnProtocol.PPTP -> Unit
        }
        markDisconnected()
    }

    /** Clears a transient error or hand-off state without touching any tunnel. */
    fun dismissMessage() {
        val current = _state.value
        if (current !is VpnConnectionState.Connected && current !is VpnConnectionState.Connecting) {
            _state.value = VpnConnectionState.Disconnected
        }
    }

    /**
     * Reconciles [state] with what the platform reports. Called on a timer by the UI layer. This
     * is what turns a silently failing IKEv2 negotiation — the usual symptom of a certificate or
     * Remote ID mismatch — into a visible failure instead of an endless "Connecting".
     */
    fun refresh() {
        if (activeProtocol == VpnProtocol.IKEV2) {
            ikev2Manager?.platformState()?.let { platform ->
                when (platform) {
                    Ikev2PlatformState.CONNECTED ->
                        if (_state.value !is VpnConnectionState.Connected) markConnected()
                    Ikev2PlatformState.FAILED ->
                        fail(VpnFailure.NegotiationFailed)
                    Ikev2PlatformState.DISCONNECTED ->
                        if (_state.value is VpnConnectionState.Connected) markDisconnected()
                    Ikev2PlatformState.CONNECTING -> Unit
                }
            }
        }

        val connectingSince = connectingSinceElapsedMs
        if (_state.value is VpnConnectionState.Connecting && connectingSince != null &&
            SystemClock.elapsedRealtime() - connectingSince > CONNECT_TIMEOUT_MS
        ) {
            if (activeProtocol == VpnProtocol.IKEV2) ikev2Manager?.disconnect()
            fail(lastPlatformEvent?.let { VpnFailure.Platform(it) } ?: VpnFailure.Timeout)
        }
    }

    /** The platform's own report on the IKEv2 profile (Android 13+), via [VpnEventService]. */
    fun onPlatformEvent(event: PlatformVpnEvent) {
        // A settings change, not a failure: it must not tear down a working tunnel.
        if (event.category == VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED) return
        if (activeProtocol != VpnProtocol.IKEV2) return
        val current = _state.value
        if (current !is VpnConnectionState.Connecting && current !is VpnConnectionState.Connected) return
        if (event.isRecoverable) {
            // The platform retries these itself; keep the reason so the timeout can show it.
            lastPlatformEvent = event
        } else {
            ikev2Manager?.disconnect()
            fail(VpnFailure.Platform(event))
        }
    }

    private fun connectIkev2(profile: VpnProfile): Intent? {
        val manager = ikev2Manager ?: run {
            // Pre-Android 11 has no public IKEv2 provisioning API, so fall back to the same
            // hand-off Settings flow the legacy protocols use.
            _state.value = VpnConnectionState.RequiresSystemSettings(profile)
            return null
        }

        val consentIntent = try {
            manager.provision(profile)
        } catch (e: CertificateProblem) {
            fail(VpnFailure.Certificate(e))
            return null
        } catch (e: IllegalArgumentException) {
            // Ikev2VpnProfile.Builder rejects malformed input (e.g. an unusable key) this way.
            fail(VpnFailure.Unknown(e.message))
            return null
        }

        // The consent dialog must be accepted before the provisioned profile can be started.
        if (consentIntent != null) return consentIntent

        return try {
            activeProtocol = VpnProtocol.IKEV2
            markConnecting()
            // Only starts negotiation; success is confirmed later by refresh() or the network
            // callback, never assumed here.
            manager.connect()
            null
        } catch (e: Exception) {
            fail(VpnFailure.Unknown(e.message))
            null
        }
    }

    private fun connectWireGuard(profile: VpnProfile): Intent? {
        // WireGuard runs as this app's own VpnService, which has a consent gate separate from
        // the platform IKEv2 one.
        VpnService.prepare(context)?.let { return it }

        activeProtocol = VpnProtocol.WIREGUARD
        markConnecting()
        try {
            wireGuardManager.connect(profile)
            markConnected()
        } catch (e: Exception) {
            fail(VpnFailure.Unknown(e.message))
        }
        return null
    }

    private fun markConnecting() {
        lastPlatformEvent = null
        connectingSinceElapsedMs = SystemClock.elapsedRealtime()
        _state.value = VpnConnectionState.Connecting
    }

    private fun markConnected() {
        connectingSinceElapsedMs = null
        connectedSinceElapsedMs = SystemClock.elapsedRealtime()
        _state.value = VpnConnectionState.Connected
    }

    private fun markDisconnected() {
        activeProtocol = null
        connectingSinceElapsedMs = null
        connectedSinceElapsedMs = null
        _state.value = VpnConnectionState.Disconnected
    }

    private fun fail(failure: VpnFailure) {
        activeProtocol = null
        connectingSinceElapsedMs = null
        connectedSinceElapsedMs = null
        _state.value = VpnConnectionState.Failed(failure)
    }

    /** Must be called when the owner goes away, or each controller leaks a network callback. */
    fun close() {
        connectionCallback?.let { ikev2Manager?.stopObserving(it) }
        connectionCallback = null
    }

    /** Seconds the current tunnel has been up, for the duration readout on the home screen. */
    fun connectedDurationSeconds(): Long {
        val since = connectedSinceElapsedMs ?: return 0
        return (SystemClock.elapsedRealtime() - since) / 1000
    }

    fun trafficStats(profile: VpnProfile): TrafficStats? = when (profile.protocol) {
        // Only the WireGuard backend exposes per-tunnel counters; the platform IKEv2 profile
        // does not surface them to the owning app.
        VpnProtocol.WIREGUARD -> runCatching { wireGuardManager.statistics() }.getOrNull()
        else -> null
    }

    companion object {
        // Longer than the IKE library's ~31 s of retransmits, so its PROTOCOL_TIMEOUT event
        // arrives while still Connecting and is shown instead of the generic timeout.
        private const val CONNECT_TIMEOUT_MS = 45_000L
    }
}

sealed interface VpnConnectionState {
    object Disconnected : VpnConnectionState
    object Connecting : VpnConnectionState
    object Connected : VpnConnectionState
    data class Invalid(val issues: List<ValidationIssue>) : VpnConnectionState
    data class Failed(val failure: VpnFailure) : VpnConnectionState
    data class RequiresSystemSettings(val profile: VpnProfile) : VpnConnectionState
}

sealed interface VpnFailure {
    data class Certificate(val problem: CertificateProblem) : VpnFailure

    /** The platform reported the IKE negotiation failed after it started. */
    object NegotiationFailed : VpnFailure

    object Timeout : VpnFailure

    data class Platform(val event: PlatformVpnEvent) : VpnFailure

    data class Unknown(val detail: String?) : VpnFailure
}
