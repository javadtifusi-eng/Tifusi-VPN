package com.tifusi.vpn.ui.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.tifusi.vpn.data.ConnectionReporter
import com.tifusi.vpn.data.NetworkSnapshot
import com.tifusi.vpn.data.SubscriptionClient
import com.tifusi.vpn.data.SubscriptionInfo
import com.tifusi.vpn.data.VpnProfileRepository
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.first
import com.tifusi.vpn.vpn.TrafficStats
import com.tifusi.vpn.vpn.VpnConnectionState
import com.tifusi.vpn.vpn.VpnController
import com.tifusi.vpn.vpn.VpnEvents
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnProfileRepository(application)
    private val controller = VpnController(application)

    // The timer and a tap can land together; one measurement at a time keeps them from racing.
    // Declared before init, which starts the loop that uses it.
    private val latencyCheck = Mutex()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Emitted when the platform needs the user to approve this app as a VPN provider. */
    private val _consentRequest = MutableStateFlow<Intent?>(null)
    val consentRequest: StateFlow<Intent?> = _consentRequest.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.profiles, repository.selectedProfileId) { profiles, selectedId ->
                profiles to selectedId
            }.collect { (profiles, selectedId) ->
                val selected = profiles.find { it.id == selectedId } ?: profiles.firstOrNull()
                _uiState.update {
                    it.copy(
                        profiles = profiles,
                        selectedProfile = selected,
                        selectedProtocol = selected?.protocol ?: it.selectedProtocol,
                    )
                }
            }
        }

        viewModelScope.launch {
            controller.state.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        viewModelScope.launch {
            VpnEvents.events.collect(controller::onPlatformEvent)
        }

        viewModelScope.launch {
            repository.subscriptionInfo.collect { info -> _uiState.update { it.copy(subscriptionInfo = info) } }
        }

        // Days and data left change on the panel while the app is closed, so re-read the saved
        // subscription once per launch. Silent: offline or a failing panel keeps the last numbers.
        viewModelScope.launch(Dispatchers.IO) {
            val url = repository.subscriptionUrl.first() ?: return@launch
            val network = NetworkSnapshot.capture(getApplication())
            val startedAt = SystemClock.elapsedRealtime()
            val result = runCatching { SubscriptionClient.fetchProfiles(getApplication(), url) }
            result.getOrNull()?.let { repository.replaceSubscriptionProfiles(url, it) }
            // Only failures: a successful refresh on every launch would bury the reports that matter.
            result.exceptionOrNull()?.let { error ->
                ConnectionReporter.recordSubscriptionFetch(getApplication(), network, startedAt, error)
            }
            // Sends whatever earlier runs recorded but could not deliver, this refresh's failure included.
            ConnectionReporter.uploadLater(getApplication(), ConnectionReporter.UPLOAD_SOON_MS)
        }

        // Reconciles with the platform and drives the duration, traffic and speed readouts.
        viewModelScope.launch(Dispatchers.IO) {
            var previous: TrafficStats? = null
            while (true) {
                controller.refresh()
                val state = _uiState.value
                if (state.connectionState is VpnConnectionState.Connected) {
                    val stats = state.selectedProfile?.let(controller::trafficStats)
                    // Per-tick deltas; TICK_INTERVAL_MS is one second, so bytes per second.
                    val last = previous
                    val down = if (stats != null && last != null) (stats.rxBytes - last.rxBytes).coerceAtLeast(0) else null
                    val up = if (stats != null && last != null) (stats.txBytes - last.txBytes).coerceAtLeast(0) else null
                    previous = stats
                    _uiState.update {
                        it.copy(
                            connectedSeconds = controller.connectedDurationSeconds(),
                            trafficStats = stats,
                            downloadBytesPerSec = down,
                            uploadBytesPerSec = up,
                        )
                    }
                } else {
                    previous = null
                    if (state.connectedSeconds != 0L || state.trafficStats != null || state.internetChecked) {
                        _uiState.update {
                            it.copy(
                                connectedSeconds = 0, trafficStats = null, downloadBytesPerSec = null,
                                uploadBytesPerSec = null, internetChecked = false, internetLatencyMs = null,
                            )
                        }
                    }
                }
                delay(TICK_INTERVAL_MS)
            }
        }

        // "Connected" only means the tunnel is up; this checks traffic really gets through it.
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (_uiState.value.connectionState is VpnConnectionState.Connected) {
                    checkLatency()
                    delay(INTERNET_CHECK_INTERVAL_MS)
                } else {
                    delay(TICK_INTERVAL_MS)
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                // PSS of this process: the Xray core runs in it, so this is what the tunnel costs.
                val bytes = runCatching { android.os.Debug.getPss() * 1024 }.getOrNull()
                _uiState.update { it.copy(memoryBytes = bytes) }
                delay(MEMORY_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        controller.close()
    }

    fun selectProtocol(protocol: VpnProtocol) {
        _uiState.update { it.copy(selectedProtocol = protocol) }
        // Switch to the first saved profile that speaks the newly chosen protocol, if any.
        val match = _uiState.value.profiles.firstOrNull { it.protocol == protocol }
        if (match != null) {
            viewModelScope.launch { repository.setSelectedProfile(match.id) }
        }
    }

    fun selectProfile(profile: VpnProfile) {
        viewModelScope.launch { repository.setSelectedProfile(profile.id) }
    }

    fun deleteProfile(profile: VpnProfile) {
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            // Don't leave a tunnel running for a profile that no longer exists in the app.
            val isLive = state.connectionState is VpnConnectionState.Connected ||
                state.connectionState is VpnConnectionState.Connecting
            if (isLive && state.selectedProfile?.id == profile.id) {
                controller.disconnect(profile)
            }
            repository.deleteProfile(profile.id)
        }
    }

    fun toggleConnection() {
        val profile = _uiState.value.selectedProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (_uiState.value.connectionState) {
                // Tapping while negotiating cancels, rather than stacking a second attempt.
                VpnConnectionState.Connected, VpnConnectionState.Connecting ->
                    controller.disconnect(profile)
                else -> _consentRequest.value = controller.connect(profile)
            }
        }
    }

    /** Called after the system VPN consent dialog returns successfully. */
    fun onConsentGranted() {
        _consentRequest.value = null
        val profile = _uiState.value.selectedProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _consentRequest.value = controller.connect(profile)
        }
    }

    fun onConsentDismissed() {
        _consentRequest.value = null
    }

    fun dismissMessage() {
        controller.dismissMessage()
    }

    /** Milliseconds for a tiny request through the tunnel, or null when it does not get through. */
    /** Tapping the latency readout: measure again now instead of waiting for the next tick. */
    fun retestLatency() {
        if (_uiState.value.connectionState !is VpnConnectionState.Connected || latencyCheck.isLocked) return
        _uiState.update { it.copy(internetChecked = false) }
        viewModelScope.launch(Dispatchers.IO) { checkLatency() }
    }

    private suspend fun checkLatency() = latencyCheck.withLock {
        val latency = if (_uiState.value.selectedProfile?.protocol?.runsInCore == true) {
            // This app bypasses its own tunnel, so only the core can test it.
            controller.vlessLatencyMs(INTERNET_CHECK_URL)
        } else {
            measureInternet()
        }
        if (_uiState.value.connectionState is VpnConnectionState.Connected) {
            _uiState.update { it.copy(internetChecked = true, internetLatencyMs = latency) }
        }
    }

    /**
     * Round-trip time through the tunnel, not the cost of setting up a connection.
     *
     * A single fresh request times DNS, the TCP handshake and the TLS handshake as well as the round
     * trip, which on a mobile path in Iran reads as a second or more and looks like a broken tunnel.
     * So the first request only warms a connection; its body is drained rather than disconnected,
     * which hands the socket back to HttpURLConnection's keep-alive pool, and the second request
     * reuses it and is the one timed. If that second one fails, the first still proves the tunnel
     * works and its time is reported rather than nothing.
     */
    private fun measureInternet(): Long? {
        val warm = timedRequest(keepAlive = true) ?: return null
        return timedRequest(keepAlive = false) ?: warm
    }

    private fun timedRequest(keepAlive: Boolean): Long? = runCatching {
        val started = SystemClock.elapsedRealtime()
        val connection = (URL(INTERNET_CHECK_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = INTERNET_CHECK_TIMEOUT_MS
            readTimeout = INTERNET_CHECK_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
        }
        try {
            val ok = connection.responseCode in 200..399
            // Draining is what returns the socket to the pool; disconnect() would close it.
            runCatching { connection.inputStream.use { it.readBytes() } }
            if (ok) SystemClock.elapsedRealtime() - started else null
        } finally {
            if (!keepAlive) connection.disconnect()
        }
    }.getOrNull()

    companion object {
        private const val TICK_INTERVAL_MS = 1000L
        private const val MEMORY_INTERVAL_MS = 3_000L
        private const val INTERNET_CHECK_INTERVAL_MS = 15_000L
        private const val INTERNET_CHECK_TIMEOUT_MS = 8_000
        // Answers 204 with no body; the same endpoint Android itself uses for connectivity checks.
        private const val INTERNET_CHECK_URL = "https://www.google.com/generate_204"
    }
}

data class HomeUiState(
    val profiles: List<VpnProfile> = emptyList(),
    val selectedProfile: VpnProfile? = null,
    val selectedProtocol: VpnProtocol = VpnProtocol.IKEV2,
    val connectionState: VpnConnectionState = VpnConnectionState.Disconnected,
    val connectedSeconds: Long = 0,
    val trafficStats: TrafficStats? = null,
    val downloadBytesPerSec: Long? = null,
    val uploadBytesPerSec: Long? = null,
    /** False until the first check through the tunnel has finished. */
    val internetChecked: Boolean = false,
    /** Null after a check means traffic did not get through the tunnel. */
    val internetLatencyMs: Long? = null,
    val subscriptionInfo: SubscriptionInfo? = null,
    /** This app's memory (the in-process core included), for the Home screen. */
    val memoryBytes: Long? = null,
)
