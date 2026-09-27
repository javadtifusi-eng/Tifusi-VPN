package com.tifusi.vpn.ui.servers

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tifusi.vpn.data.VpnProfileRepository
import com.tifusi.vpn.vpn.CertificateProblem
import com.tifusi.vpn.vpn.CertificateStore
import com.tifusi.vpn.vpn.ValidationIssue
import com.tifusi.vpn.vpn.VlessLink
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProfileValidator
import com.tifusi.vpn.vpn.VpnProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Backs the add/edit form. Shared by manual entry, QR import and editing so that every path ends
 * in the same review-and-validate step before anything is persisted.
 */
class AddServerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnProfileRepository(application)

    private val _draft = MutableStateFlow(newDraft(VpnProtocol.IKEV2))
    val draft: StateFlow<VpnProfile> = _draft.asStateFlow()

    private val _issues = MutableStateFlow<List<ValidationIssue>>(emptyList())
    val issues: StateFlow<List<ValidationIssue>> = _issues.asStateFlow()

    private val _importError = MutableStateFlow<CertificateProblem?>(null)
    val importError: StateFlow<CertificateProblem?> = _importError.asStateFlow()

    var isEditing by mutableStateOf(false)
        private set

    /** The draft the user was last shown non-blocking warnings for; a second save confirms. */
    private var warnedDraft: VpnProfile? = null

    fun startNew(protocol: VpnProtocol) {
        load(newDraft(protocol), editing = false)
    }

    fun loadDraft(profile: VpnProfile, isExisting: Boolean) {
        load(profile, editing = isExisting)
        // Scanned configs are checked immediately so certificate problems show before any typing.
        if (!isExisting) {
            viewModelScope.launch {
                _issues.value = withContext(Dispatchers.Default) { VpnProfileValidator.validate(profile) }
            }
        }
    }

    private fun load(profile: VpnProfile, editing: Boolean) {
        _draft.value = profile
        _issues.value = emptyList()
        _importError.value = null
        warnedDraft = null
        isEditing = editing
    }

    fun update(transform: (VpnProfile) -> VpnProfile) {
        _draft.update(transform)
    }

    /**
     * The link is the whole VLESS configuration. The address shown in the server list, and the name
     * while the user has not typed one, come from it, so manual servers read like subscribed ones.
     */
    fun setVlessLink(raw: String) {
        val value = raw.trim()
        val link = runCatching { VlessLink.parse(value) }.getOrNull()
        update {
            it.copy(
                vlessLink = value,
                serverAddress = link?.address.orEmpty(),
                name = if (it.name.isBlank()) link?.remark.orEmpty() else it.name,
            )
        }
    }

    fun importServerCa(uri: Uri) = importFile(uri) { bytes ->
        // .crt/.cer files are often DER rather than PEM; normalise to PEM for storage.
        val pem = CertificateStore.certificateBytesToPem(bytes)
        CertificateStore.validate(CertificateStore.parseCaCertificate(pem), expectCa = true)
        update { it.copy(serverRootCaCertPem = pem) }
    }

    fun importPkcs12(uri: Uri) = importFile(uri) { bytes ->
        // Can't be opened until the password is typed; it is fully checked on save.
        update {
            it.copy(
                pkcs12Base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                userCertPem = null,
                userPrivateKeyPem = null,
            )
        }
    }

    fun removePkcs12() {
        update { it.copy(pkcs12Base64 = null, pkcs12Password = null) }
    }

    fun importClientCertificate(uri: Uri) = importFile(uri) { bytes ->
        val pem = CertificateStore.certificateBytesToPem(bytes)
        CertificateStore.validate(CertificateStore.parseCertificate(pem), expectCa = false)
        update { it.copy(userCertPem = pem) }
    }

    fun importPrivateKey(uri: Uri) = importFile(uri) { bytes ->
        val pem = String(bytes, Charsets.US_ASCII).trim()
        CertificateStore.parsePrivateKeyPem(pem)
        update { it.copy(userPrivateKeyPem = pem) }
    }

    private fun importFile(uri: Uri, handle: (ByteArray) -> Unit) {
        viewModelScope.launch {
            _importError.value = null
            try {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        val data = stream.readBytes()
                        if (data.size > MAX_IMPORT_BYTES) {
                            throw CertificateProblem.Unparseable("file larger than 1 MB")
                        }
                        data
                    }
                } ?: throw CertificateProblem.Unparseable("file could not be opened")
                handle(bytes)
            } catch (e: CertificateProblem) {
                _importError.value = e
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            val profile = _draft.value
            val found = withContext(Dispatchers.Default) { VpnProfileValidator.validate(profile) }
            _issues.value = found

            if (found.any { it.isBlocking }) return@launch
            // Warnings (e.g. no server CA pinned) are shown once; saving the same draft again
            // is the user's explicit confirmation.
            if (found.isNotEmpty() && warnedDraft != profile) {
                warnedDraft = profile
                return@launch
            }

            repository.saveProfile(profile)
            repository.setSelectedProfile(profile.id)
            onSaved()
        }
    }

    private fun newDraft(protocol: VpnProtocol) = VpnProfile(
        id = UUID.randomUUID().toString(),
        name = "",
        protocol = protocol,
        serverAddress = "",
    )

    companion object {
        private const val MAX_IMPORT_BYTES = 1024 * 1024
    }
}
