package com.tifusi.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnManager
import android.net.VpnService
import android.os.SystemClock
import com.tifusi.vpn.data.ConnectionReport
import com.tifusi.vpn.data.ConnectionReporter
import com.tifusi.vpn.data.NetworkSnapshot
import com.tifusi.vpn.data.reportDetail
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
    private var trafficBaseline: LongArray? = null
    private var ikev2SessionKey: String? = null

    // Reporting only observes the transitions below; it never feeds back into [state]. Results can
    // be reached from the ticker, the UI and the network callback thread at once, so the attempt is
    // taken under [reportLock] and recorded exactly once.
    private val reportLock = Any()

    /** The attempt started by [connect] whose result has not been recorded yet. */
    private var pendingAttempt: ReportAttempt? = null

    /** The attempt that last came up, so a drop soon after "Connected" is reported too. */
    private var connectedAttempt: ReportAttempt? = null
    private var connectedAttemptAtElapsedMs = 0L

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

        // Only these two are tunnels the app runs itself and so can see the outcome of. Re-invoked
        // after the consent dialog, this restarts the clock, so the duration excludes the dialog.
        if (profile.protocol == VpnProtocol.IKEV2 || profile.protocol == VpnProtocol.WIREGUARD) {
            beginAttempt(profile.protocol)
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
        // Taken before teardown: stopping the tunnel makes the platform report DEACTIVATED_BY_USER
        // and drop the VPN network, which must not be recorded as a failure or an early drop.
        val wasConnecting = _state.value is VpnConnectionState.Connecting
        val cancelled = synchronized(reportLock) {
            connectedAttempt = null
            pendingAttempt.also { pendingAttempt = null }
        }
        when (profile.protocol) {
            VpnProtocol.IKEV2 -> ikev2Manager?.disconnect()
            VpnProtocol.WIREGUARD -> runCatching { wireGuardManager.disconnect() }
            // Nothing app-side to tear down; the tunnel lives entirely in Settings.
            VpnProtocol.L2TP, VpnProtocol.PPTP -> Unit
        }
        markDisconnected()
        // A user giving up on an endless "Connecting" is the failure the owner most needs to see.
        if (wasConnecting && cancelled != null) {
            recordConnect(
                cancelled, ConnectionReport.RESULT_FAILED,
                "Cancelled by user while connecting",
                SystemClock.elapsedRealtime() - cancelled.startedElapsedMs,
            )
        }
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
            fail(lastPlatformEvent?.let { VpnFailure.Platform(it) } ?: VpnFailure.Timeout, timedOut = true)
        }
    }

    /** The platform's own report on the IKEv2 profile (Android 13+), via [VpnEventService]. */
    fun onPlatformEvent(event: PlatformVpnEvent) {
        // A settings change, not a failure: it must not tear down a working tunnel.
        if (event.category == VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED) return
        if (activeProtocol != VpnProtocol.IKEV2) return
        val current = _state.value
        if (current !is VpnConnectionState.Connecting && current !is VpnConnectionState.Connected) return
        // Starting a new run stops the previous one, and the platform reports that stop as
        // DEACTIVATED_BY_USER. Acting on it used to tear down the brand-new tunnel the moment it
        // came up, so only events for the current run count.
        if (event.sessionKey != null && event.sessionKey != ikev2SessionKey) return
        if (event.category == VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER) {
            // The platform already stopped the VPN (Settings, or another VPN app took over);
            // stopping it again is pointless, only the state and a plain message are left.
            fail(VpnFailure.Deactivated)
            return
        }
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
            // Until the platform hands out the new run's key, ignore every keyed event: they can
            // only be about the run being replaced.
            ikev2SessionKey = PENDING_SESSION
            markConnecting()
            // Only starts negotiation; success is confirmed later by refresh() or the network
            // callback, never assumed here.
            ikev2SessionKey = manager.connect()
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
        trafficBaseline = deviceTrafficCounters()
        _state.value = VpnConnectionState.Connected

        val now = SystemClock.elapsedRealtime()
        val attempt = synchronized(reportLock) {
            pendingAttempt?.also {
                pendingAttempt = null
                connectedAttempt = it
                connectedAttemptAtElapsedMs = now
            }
        }
        if (attempt != null) {
            recordConnect(
                attempt, ConnectionReport.RESULT_CONNECTED, "", now - attempt.startedElapsedMs,
                uploadAfterMs = listOf(ConnectionReporter.UPLOAD_SOON_MS, ConnectionReporter.UPLOAD_THROUGH_TUNNEL_MS),
            )
        }
    }

    private fun markDisconnected() {
        activeProtocol = null
        connectingSinceElapsedMs = null
        connectedSinceElapsedMs = null
        trafficBaseline = null
        _state.value = VpnConnectionState.Disconnected

        // disconnect() clears the attempt first, so reaching this means the tunnel went down alone.
        recordEarlyDrop("Tunnel went down (VPN network lost or platform state DISCONNECTED)")
    }

    /** [timedOut] marks the [CONNECT_TIMEOUT_MS] path, whose [failure] is only the last known reason. */
    private fun fail(failure: VpnFailure, timedOut: Boolean = false) {
        activeProtocol = null
        connectingSinceElapsedMs = null
        connectedSinceElapsedMs = null
        _state.value = VpnConnectionState.Failed(failure)

        val attempt = synchronized(reportLock) { pendingAttempt.also { pendingAttempt = null } }
        if (attempt != null) {
            val detail = when {
                !timedOut -> failure.reportDetail()
                failure is VpnFailure.Platform ->
                    "No result after ${CONNECT_TIMEOUT_MS / 1000} s; last platform event: ${failure.reportDetail()}"
                else -> "No result after ${CONNECT_TIMEOUT_MS / 1000} s; no platform event"
            }
            recordConnect(
                attempt,
                if (timedOut) ConnectionReport.RESULT_TIMEOUT else ConnectionReport.RESULT_FAILED,
                detail,
                SystemClock.elapsedRealtime() - attempt.startedElapsedMs,
            )
        }
        recordEarlyDrop(failure.reportDetail())
    }

    private fun beginAttempt(protocol: VpnProtocol) {
        // Before provisioning or starting anything, so this is still the phone's own network.
        val network = NetworkSnapshot.capture(context)
        val attempt = ReportAttempt(protocol, SystemClock.elapsedRealtime(), network)
        synchronized(reportLock) {
            pendingAttempt = attempt
            connectedAttempt = null
        }
    }

    /**
     * A tunnel that reaches "Connected" and dies within seconds looks like success in a
     * "connected" report alone; typically the server accepted IKE but the traffic path is broken.
     */
    private fun recordEarlyDrop(cause: String) {
        val now = SystemClock.elapsedRealtime()
        var upMs = 0L
        val dropped = synchronized(reportLock) {
            val attempt = connectedAttempt
            connectedAttempt = null
            upMs = now - connectedAttemptAtElapsedMs
            attempt?.takeIf { upMs <= EARLY_DROP_MS }
        } ?: return
        recordConnect(dropped, ConnectionReport.RESULT_DISCONNECTED_EARLY, "Up for $upMs ms, then: $cause", null)
    }

    private fun recordConnect(
        attempt: ReportAttempt,
        result: String,
        detail: String,
        durationMs: Long?,
        uploadAfterMs: List<Long> = listOf(ConnectionReporter.UPLOAD_SOON_MS),
    ) {
        val report = ConnectionReport(
            at = System.currentTimeMillis(),
            event = ConnectionReport.EVENT_CONNECT,
            result = result,
            detail = detail,
            protocol = attempt.protocol.name,
            durationMs = durationMs,
            network = attempt.network.network,
            carrier = attempt.network.carrier,
            simCarrier = attempt.network.simCarrier,
        )
        ConnectionReporter.record(context, report, uploadAfterMs)
    }

    private class ReportAttempt(
        val protocol: VpnProtocol,
        val startedElapsedMs: Long,
        val network: NetworkSnapshot,
    )

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
        VpnProtocol.WIREGUARD -> runCatching { wireGuardManager.statistics() }.getOrNull()
        VpnProtocol.IKEV2 -> ikev2TrafficEstimate()
        else -> null
    }

    /** Device-wide rx, tx, mobile rx, mobile tx; android.net.TrafficStats reports -1 if unsupported. */
    private fun deviceTrafficCounters() = longArrayOf(
        android.net.TrafficStats.getTotalRxBytes(),
        android.net.TrafficStats.getTotalTxBytes(),
        android.net.TrafficStats.getMobileRxBytes(),
        android.net.TrafficStats.getMobileTxBytes(),
    )

    /**
     * The platform IKEv2 profile exposes no per-tunnel counters to its owning app, so this estimates
     * them from device totals since the tunnel came up. Totals see tunnel traffic twice, once on the
     * tunnel interface and once encrypted on the underlying network: on mobile data that underlying
     * share is the mobile counter, and on Wi-Fi, where the mobile counter does not grow, it is about
     * half of the total.
     */
    private fun ikev2TrafficEstimate(): TrafficStats? {
        val base = trafficBaseline ?: return null
        val now = deviceTrafficCounters()
        if (base[0] < 0 || now[0] < 0) return null
        fun delta(i: Int) = if (base[i] < 0 || now[i] < 0) 0L else (now[i] - base[i]).coerceAtLeast(0)
        fun tunnel(total: Long, mobile: Long) = if (mobile > 0) (total - mobile).coerceAtLeast(0) else total / 2
        return TrafficStats(rxBytes = tunnel(delta(0), delta(2)), txBytes = tunnel(delta(1), delta(3)))
    }

    companion object {
        // Longer than the IKE library's ~31 s of retransmits, so its PROTOCOL_TIMEOUT event
        // arrives while still Connecting and is shown instead of the generic timeout.
        private const val CONNECT_TIMEOUT_MS = 45_000L

        /** A drop within this long after "Connected" is reported as disconnected_early. */
        private const val EARLY_DROP_MS = 10_000L

        // Never a real key: the platform's keys are UUIDs.
        private const val PENDING_SESSION = ""
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

    /** Android itself turned the VPN off: from Settings, or because another VPN app started. */
    object Deactivated : VpnFailure

    data class Unknown(val detail: String?) : VpnFailure
}
