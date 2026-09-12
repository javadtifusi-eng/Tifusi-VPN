package com.tifusi.vpn.ui.servers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.localized
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.Ikev2AuthType
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import java.text.DateFormat

private val WarningColor = Color(0xFFFFB74D)
private val AnyFile = arrayOf("*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(viewModel: AddServerViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val issues by viewModel.issues.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(if (viewModel.isEditing) R.string.edit_server else R.string.add_server),
            style = MaterialTheme.typography.headlineMedium,
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VpnProtocol.entries.forEach { protocol ->
                FilterChip(
                    selected = draft.protocol == protocol,
                    onClick = { viewModel.update { it.copy(protocol = protocol) } },
                    label = { Text(protocol.displayName()) },
                )
            }
        }

        FormField(stringResource(R.string.field_name), draft.name) { v ->
            viewModel.update { it.copy(name = v) }
        }
        FormField(stringResource(R.string.field_server_address), draft.serverAddress, keyboardType = KeyboardType.Uri) { v ->
            viewModel.update { it.copy(serverAddress = v.trim()) }
        }

        when (draft.protocol) {
            VpnProtocol.IKEV2 -> Ikev2Section(draft, viewModel)
            VpnProtocol.WIREGUARD -> WireGuardSection(draft, viewModel)
            VpnProtocol.L2TP -> {
                CredentialsFields(draft, viewModel)
                FormField(stringResource(R.string.field_ipsec_preshared_key), draft.l2tpIpsecPresharedKey, secret = true) { v ->
                    viewModel.update { it.copy(l2tpIpsecPresharedKey = v) }
                }
            }
            VpnProtocol.PPTP -> CredentialsFields(draft, viewModel)
        }

        importError?.let {
            Text(it.localized(context), color = MaterialTheme.colorScheme.error)
        }

        if (issues.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                issues.forEach { issue ->
                    Text(
                        text = issue.localized(context),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (issue.isBlocking) MaterialTheme.colorScheme.error else WarningColor,
                    )
                }
            }
        }

        val onlyWarnings = issues.isNotEmpty() && issues.none { it.isBlocking }
        Button(
            onClick = { viewModel.save(onDone) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TifusiNeonBlue),
        ) {
            Text(stringResource(if (onlyWarnings) R.string.save_anyway else R.string.action_save))
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Ikev2Section(draft: VpnProfile, viewModel: AddServerViewModel) {
    FormField(stringResource(R.string.field_remote_id), draft.remoteIdentifier) { v ->
        viewModel.update { it.copy(remoteIdentifier = v.trim()) }
    }
    FormField(stringResource(R.string.field_local_id), draft.localIdentifier) { v ->
        viewModel.update { it.copy(localIdentifier = v.trim()) }
    }

    Text(stringResource(R.string.field_auth_type), style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Ikev2AuthType.entries.forEach { type ->
            FilterChip(
                selected = draft.ikev2AuthType == type,
                onClick = { viewModel.update { it.copy(ikev2AuthType = type) } },
                label = { Text(stringResource(type.labelRes())) },
            )
        }
    }

    when (draft.ikev2AuthType) {
        Ikev2AuthType.PSK ->
            FormField(stringResource(R.string.field_preshared_key), draft.presharedKey, secret = true) { v ->
                viewModel.update { it.copy(presharedKey = v) }
            }

        Ikev2AuthType.USERNAME_PASSWORD -> {
            CredentialsFields(draft, viewModel)
            ServerCaSection(draft, viewModel)
        }

        Ikev2AuthType.CERTIFICATE -> {
            ClientCertificateSection(draft, viewModel)
            ServerCaSection(draft, viewModel)
        }
    }
}

@Composable
private fun ServerCaSection(draft: VpnProfile, viewModel: AddServerViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importServerCa)
    }
    val summary = remember(draft.serverRootCaCertPem) {
        summarizeCertificate(draft.serverRootCaCertPem, expectCa = true)
    }

    Text(stringResource(R.string.server_ca_certificate), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.server_ca_hint), style = MaterialTheme.typography.bodyMedium, color = TifusiTextSecondary)
    OutlinedButton(onClick = { picker.launch(AnyFile) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.import_ca_file))
    }
    FormField(stringResource(R.string.paste_pem), draft.serverRootCaCertPem, singleLine = false) { v ->
        viewModel.update { it.copy(serverRootCaCertPem = v) }
    }
    CertSummaryView(summary)
}

@Composable
private fun ClientCertificateSection(draft: VpnProfile, viewModel: AddServerViewModel) {
    val p12Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importPkcs12)
    }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importClientCertificate)
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importPrivateKey)
    }

    Text(stringResource(R.string.client_certificate), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.client_cert_either), style = MaterialTheme.typography.bodyMedium, color = TifusiTextSecondary)

    OutlinedButton(onClick = { p12Picker.launch(AnyFile) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.import_p12))
    }

    if (!draft.pkcs12Base64.isNullOrBlank()) {
        FormField(stringResource(R.string.field_p12_password), draft.pkcs12Password, secret = true) { v ->
            viewModel.update { it.copy(pkcs12Password = v) }
        }
        val summary = remember(draft.pkcs12Base64, draft.pkcs12Password) {
            summarizePkcs12(draft.pkcs12Base64, draft.pkcs12Password)
        }
        CertSummaryView(summary)
        TextButton(onClick = viewModel::removePkcs12) {
            Text(stringResource(R.string.action_remove))
        }
    } else {
        OutlinedButton(onClick = { certPicker.launch(AnyFile) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.import_client_cert))
        }
        val certSummary = remember(draft.userCertPem) {
            summarizeCertificate(draft.userCertPem, expectCa = false)
        }
        CertSummaryView(certSummary)

        OutlinedButton(onClick = { keyPicker.launch(AnyFile) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.import_private_key))
        }
        if (!draft.userPrivateKeyPem.isNullOrBlank()) {
            val context = LocalContext.current
            val keyProblem = remember(draft.userPrivateKeyPem) { privateKeyProblem(draft.userPrivateKeyPem) }
            if (keyProblem == null) {
                Text(stringResource(R.string.private_key_ok), color = TifusiNeonGreen)
            } else {
                Text(keyProblem.localized(context), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CertSummaryView(summary: CertSummary?) {
    summary ?: return
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        summary.subject?.let {
            Text(stringResource(R.string.cert_issued_to, it), style = MaterialTheme.typography.bodyMedium)
        }
        summary.notAfter?.let {
            Text(
                stringResource(R.string.cert_valid_until, dateFormat.format(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = TifusiTextSecondary,
            )
        }
        if (summary.problem == null) {
            Text(stringResource(R.string.cert_ok), color = TifusiNeonGreen)
        } else {
            Text(summary.problem.localized(context), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CredentialsFields(draft: VpnProfile, viewModel: AddServerViewModel) {
    FormField(stringResource(R.string.field_username), draft.username) { v ->
        viewModel.update { it.copy(username = v) }
    }
    FormField(stringResource(R.string.field_password), draft.password, secret = true) { v ->
        viewModel.update { it.copy(password = v) }
    }
}

@Composable
private fun WireGuardSection(draft: VpnProfile, viewModel: AddServerViewModel) {
    FormField(stringResource(R.string.field_wg_private_key), draft.wireGuardPrivateKey, secret = true) { v ->
        viewModel.update { it.copy(wireGuardPrivateKey = v.trim()) }
    }
    FormField(stringResource(R.string.field_wg_peer_public_key), draft.wireGuardPeerPublicKey) { v ->
        viewModel.update { it.copy(wireGuardPeerPublicKey = v.trim()) }
    }
    FormField(stringResource(R.string.field_wg_preshared_key), draft.wireGuardPresharedKey, secret = true) { v ->
        viewModel.update { it.copy(wireGuardPresharedKey = v.trim()) }
    }
    FormField(stringResource(R.string.field_wg_address), draft.wireGuardAddress) { v ->
        viewModel.update { it.copy(wireGuardAddress = v) }
    }
    FormField(stringResource(R.string.field_wg_dns), draft.wireGuardDnsServers) { v ->
        viewModel.update { it.copy(wireGuardDnsServers = v) }
    }
    FormField(
        stringResource(R.string.field_wg_port),
        draft.wireGuardEndpointPort?.toString(),
        keyboardType = KeyboardType.Number,
    ) { v ->
        viewModel.update { it.copy(wireGuardEndpointPort = v.filter(Char::isDigit).toIntOrNull()) }
    }
    FormField(stringResource(R.string.field_wg_allowed_ips), draft.wireGuardAllowedIps) { v ->
        viewModel.update { it.copy(wireGuardAllowedIps = v) }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String?,
    secret: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            autoCorrect = false,
        ),
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                }
            }
        } else {
            null
        },
        // PEM blocks are only readable in a fixed-width face.
        textStyle = if (singleLine) LocalTextStyle.current else MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun VpnProtocol.displayName(): String = when (this) {
    VpnProtocol.IKEV2 -> "IKEv2"
    VpnProtocol.WIREGUARD -> "WireGuard"
    VpnProtocol.L2TP -> "L2TP"
    VpnProtocol.PPTP -> "PPTP"
}

private fun Ikev2AuthType.labelRes(): Int = when (this) {
    Ikev2AuthType.PSK -> R.string.auth_psk
    Ikev2AuthType.USERNAME_PASSWORD -> R.string.auth_username_password
    Ikev2AuthType.CERTIFICATE -> R.string.auth_certificate
}
