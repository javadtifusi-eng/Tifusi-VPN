package com.tifusi.vpn.ui.servers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
            val message = try {
                val profiles = withContext(Dispatchers.IO) {
                    SubscriptionClient.fetchProfiles(getApplication(), link)
                }
                repository.replaceSubscriptionProfiles(link.trim(), profiles)
                SubscriptionMessage.Imported(profiles.size)
            } catch (e: SubscriptionError) {
                SubscriptionMessage.Failed(e)
            }
            _state.update { it.copy(isLoading = false, message = message) }
        }
    }
}
