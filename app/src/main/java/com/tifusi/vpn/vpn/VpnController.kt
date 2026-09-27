package com.tifusi.vpn.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnManager
import android.net.VpnService
import android.os.SystemClock
import com.tifusi.vpn.data.AppSettings
import com.tifusi.vpn.data.ConnectionReport
import com.tifusi.vpn.data.ConnectionReporter
import com.tifusi.vpn.data.NetworkSnapshot
import com.tifusi.vpn.data.reportDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.strongswan.android.logic.CharonVpnService

/**
 * Single entry point the UI uses to bring a tunnel up or down, regardless of protocol. Hides the
 * fact that IKEv2 goes through the built-in strongSwan engine ([CharonVpnService]), or for PSK
 * profiles, which strongSwan for Android cannot do, the platform VpnManager.
 *
 * [connect] and [disconnect] may block, so callers must invoke them off the main thread.
 */
class VpnController(private val context: Context) {

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
    private var uidTrafficBaseline: Pair<Long, Long>? = null
    private var ikev2SessionKey: String? = null

    /** The [CharonVpnService] run this controller started, while IKEv2 goes through the built-in engine. */
    private var charonRunId: Long? = null

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
            if (activeProtocol != VpnProtocol.IKEV2 || charonRunId != null) return@observeConnectionState
            if (connected && _state.value is VpnConnectionState.Connecting) {
                markConnected()
            } else if (!connected && _state.value is VpnConnectionState.Connected) {
                markDisconnected()
            }
        }
        adoptRunningTunnel()
    }

    /** A controller created after the app was closed and reopened must show a tunnel that is still up. */
    private fun adoptRunningTunnel() {
        if (CharonVpnService.getState().let { it == CharonVpnService.State.CONNECTED || it == CharonVpnService.State.CONNECTING }) {
            charonRunId = CharonVpnService.getRunId()
            activeProtocol = VpnProtocol.IKEV2
            if (CharonVpnService.getState() == CharonVpnService.State.CONNECTED) markConnected() else markConnecting()
        } else if (ikev2Manager?.platformState() == Ikev2PlatformState.CONNECTED) {
            activeProtocol = VpnProtocol.IKEV2
            markConnected()
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

        // Only these are tunnels the app runs itself and so can see the outcome of. Re-invoked after
        // the consent dialog, this restarts the clock, so the duration excludes the dialog.
        beginAttempt(profile.protocol)

        return when (profile.protocol) {
            VpnProtocol.IKEV2 -> if (usesBuiltInEngine(profile)) connectCharon(profile) else connectIkev2(profile)
            // Only IKEv2 is left; VLESS profiles are filtered out of the lists and cannot get here.
            VpnProtocol.VLESS -> {
                fail(VpnFailure.Unknown("VLESS is not supported"))
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
            VpnProtocol.IKEV2 -> {
                val runId = charonRunId
                if (runId != null) {
                    charonRunId = null
                    CharonVpnService.stop(context, runId + 1)
                    CharonLog.report(context, "disconnect")
                } else {
                    ikev2Manager?.disconnect()
                }
            }
            VpnProtocol.VLESS -> Unit
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
        if (activeProtocol == VpnProtocol.IKEV2 && charonRunId != null) {
            reconcileCharon()
        } else if (activeProtocol == VpnProtocol.IKEV2) {
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
            val failure = when {
                activeProtocol == VpnProtocol.IKEV2 && charonRunId != null -> {
                    CharonVpnService.stop(context, (charonRunId ?: 0) + 1)
                    charonRunId = null
                    CharonLog.report(context, "timeout")
                    VpnFailure.Charon("no answer from the server within ${CONNECT_TIMEOUT_MS / 1000} s")
                }
                else -> {
                    if (activeProtocol == VpnProtocol.IKEV2) ikev2Manager?.disconnect()
                    lastPlatformEvent?.let { VpnFailure.Platform(it) } ?: VpnFailure.Timeout
                }
            }
            fail(failure, timedOut = true)
        }
    }

    /** The platform's own report on the IKEv2 profile (Android 13+), via [VpnEventService]. */
    fun onPlatformEvent(event: PlatformVpnEvent) {
        // A settings change, not a failure: it must not tear down a working tunnel.
        if (event.category == VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED) return
        if (activeProtocol != VpnProtocol.IKEV2 || charonRunId != null) return
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
            // Only PSK profiles get here (the built-in engine takes the rest), and before
            // Android 11 there is no platform IKEv2 client to run them on.
            fail(VpnFailure.Unknown("PSK profiles need Android 11 or newer"))
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


    /** The built-in engine takes username/password and certificate profiles (strongSwan for Android has no PSK). */
    private fun usesBuiltInEngine(profile: VpnProfile): Boolean {
        if (!CharonVpnService.isAvailable()) return false
        val supported = when (profile.ikev2AuthType) {
            Ikev2AuthType.USERNAME_PASSWORD -> !profile.username.isNullOrBlank()
            Ikev2AuthType.CERTIFICATE -> !profile.pkcs12Base64.isNullOrBlank()
            else -> false
        }
        return supported
    }

    private fun connectCharon(profile: VpnProfile): Intent? {
        // CharonVpnService is this app's own VpnService, with a consent gate of its own.
        VpnService.prepare(context)?.let { return it }

        // The platform profile, if one is up, would hold the VPN slot.
        ikev2Manager?.disconnect()
        activeProtocol = VpnProtocol.IKEV2
        markConnecting()
        val runId = SystemClock.elapsedRealtimeNanos()
        charonRunId = runId
        val eap = profile.ikev2AuthType == Ikev2AuthType.USERNAME_PASSWORD
        val extras = Intent()
            .putExtra(CharonVpnService.EXTRA_NAME, profile.name)
            .putExtra(CharonVpnService.EXTRA_SERVER, profile.serverAddress)
            .putExtra(CharonVpnService.EXTRA_TYPE, if (eap) "ikev2-eap" else "ikev2-cert")
            .putExtra(CharonVpnService.EXTRA_REMOTE_ID, profile.remoteIdentifier?.takeIf { it.isNotBlank() })
            .putExtra(CharonVpnService.EXTRA_LOCAL_ID, profile.localIdentifier?.takeIf { it.isNotBlank() })
            .putExtra(CharonVpnService.EXTRA_CA_PEM, profile.serverRootCaCertPem?.takeIf { it.isNotBlank() })
            .putExtra(CharonVpnService.EXTRA_MTU, AppSettings.load(context).mtu.coerceAtMost(1400))
        if (eap) {
            extras.putExtra(CharonVpnService.EXTRA_USERNAME, profile.username)
                .putExtra(CharonVpnService.EXTRA_PASSWORD, profile.password)
        } else {
            extras.putExtra(CharonVpnService.EXTRA_P12, profile.pkcs12Base64)
                .putExtra(CharonVpnService.EXTRA_P12_PASSWORD, profile.pkcs12Password)
        }
        try {
            CharonVpnService.start(context, extras, runId)
        } catch (e: Exception) {
            charonRunId = null
            fail(VpnFailure.Charon(e.message ?: e.javaClass.simpleName))
        }
        return null
    }

    /** Follows [CharonVpnService] for the run [connectCharon] started; driven by [refresh]. */
    private fun reconcileCharon() {
        val runId = charonRunId ?: return
        if (CharonVpnService.getRunId() != runId) return
        when (CharonVpnService.getState()) {
            CharonVpnService.State.CONNECTING ->
                // A drop charon is re-establishing on its own shows as connecting again.
                if (_state.value is VpnConnectionState.Connected) markConnecting()
            CharonVpnService.State.CONNECTED ->
                if (_state.value !is VpnConnectionState.Connected) markConnected()
            CharonVpnService.State.FAILED -> {
                charonRunId = null
                val error = CharonVpnService.getError()
                CharonLog.report(context, "failed")
                fail(if (error == "revoked") VpnFailure.Deactivated else VpnFailure.Charon(error ?: "unknown"))
            }
            CharonVpnService.State.DISABLED -> {
                charonRunId = null
                CharonLog.report(context, "dropped")
                markDisconnected()
            }
            null -> Unit
        }
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
        val uid = android.os.Process.myUid()
        uidTrafficBaseline = android.net.TrafficStats.getUidRxBytes(uid) to android.net.TrafficStats.getUidTxBytes(uid)
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
        VpnProtocol.IKEV2 -> if (charonRunId != null) charonTraffic() else ikev2TrafficEstimate()
        VpnProtocol.VLESS -> null
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
    /**
     * The built-in engine sends ESP from this app's own sockets, so the app's UID counters are the
     * tunnel's traffic (plus ESP overhead): exact, unlike the device-wide estimate below.
     */
    private fun charonTraffic(): TrafficStats? {
        val base = uidTrafficBaseline ?: return null
        val rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        val tx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        if (rx < 0 || tx < 0) return null
        return TrafficStats(rxBytes = (rx - base.first).coerceAtLeast(0), txBytes = (tx - base.second).coerceAtLeast(0))
    }

    private fun ikev2TrafficEstimate(): TrafficStats? {
        val base = trafficBaseline ?: return null
        val now = deviceTrafficCounters()
        if (base[0] < 0 || now[0] < 0) return null
        fun delta(i: Int) = if (base[i] < 0 || now[i] < 0) 0L else (now[i] - base[i]).coerceAtLeast(0)
        // Some phones count the tunnel interface in the totals and some do not. When they do, the
        // total is about tunnel plus mobile; when they do not, total minus mobile stays near zero
        // while mobile grows, and the encrypted mobile traffic is the best measure of the tunnel.
        fun tunnel(total: Long, mobile: Long) = when {
            mobile <= 0 -> total / 2
            total - mobile < mobile / 4 -> mobile
            else -> (total - mobile).coerceAtLeast(0)
        }
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

    /** The Xray core behind VLESS could not start; [detail] is its own error text. */
    data class Xray(val detail: String) : VpnFailure

    /** The built-in strongSwan engine failed; [detail] is its status (auth_failed, unreachable...). */
    data class Charon(val detail: String) : VpnFailure
}
