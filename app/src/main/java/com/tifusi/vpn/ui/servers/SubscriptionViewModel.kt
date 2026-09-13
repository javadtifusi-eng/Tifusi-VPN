package com.tifusi.vpn.ui.servers

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tifusi.vpn.data.ConnectionReporter
import com.tifusi.vpn.data.NetworkSnapshot
import com.tifusi.vpn.data.SubscriptionClient
import com.tifusi.vpn.data.SubscriptionError
import com.tifusi.vpn.data.VpnProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SubscriptionUiState(
    val link: String = "",
    val savedLink: String? = null,
    val isLoading: Boolean = false,
    val message: SubscriptionMessage? = null,
)

sealed interface SubscriptionMessage {
    data class Imported(val count: Int) : SubscriptionMessage
    data class Failed(val error: SubscriptionError) : SubscriptionMessage
}

class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnProfileRepository(application)

    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.subscriptionUrl.collect { url ->
                _state.update { it.copy(savedLink = url, link = it.link.ifEmpty { url.orEmpty() }) }
            }
        }
    }

    fun onLinkChange(value: String) {
        _state.update { it.copy(link = value, message = null) }
    }

    fun importLink() = load(_state.value.link)

    fun refresh() {
        _state.value.savedLink?.let(::load)
    }

    private fun load(link: String) {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, message = null) }
        viewModelScope.launch {
            val network = withContext(Dispatchers.IO) { NetworkSnapshot.capture(getApplication()) }
            val startedAt = SystemClock.elapsedRealtime()
            val message = try {
                val result = withContext(Dispatchers.IO) {
                    SubscriptionClient.fetchProfiles(getApplication(), link)
                }
                repository.replaceSubscriptionProfiles(link.trim(), result)
                // A fresh import is the first moment reports queued before any subscription was
                // saved can be attributed, so try sending them right away.
                ConnectionReporter.recordSubscriptionFetch(
                    getApplication(), network, startedAt, null,
                    uploadAfterMs = listOf(ConnectionReporter.UPLOAD_SOON_MS),
                )
                SubscriptionMessage.Imported(result.profiles.size)
            } catch (e: SubscriptionError) {
                ConnectionReporter.recordSubscriptionFetch(getApplication(), network, startedAt, e)
                SubscriptionMessage.Failed(e)
            }
            _state.update { it.copy(isLoading = false, message = message) }
        }
    }
}
