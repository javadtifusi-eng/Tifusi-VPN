package com.tifusi.vpn.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tifusi.vpn.R
import com.tifusi.vpn.data.SubscriptionError
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiSurface
import com.tifusi.vpn.ui.theme.TifusiSurfaceVariant
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.VpnProfile

@Composable
fun ServersScreen(
    profiles: List<VpnProfile>,
    selectedProfileId: String?,
    onSelectProfile: (VpnProfile) -> Unit,
    onEditProfile: (VpnProfile) -> Unit,
    onDeleteProfile: (VpnProfile) -> Unit,
    onAddManually: () -> Unit,
    subscription: SubscriptionUiState,
    onSubscriptionLinkChange: (String) -> Unit,
    onImportSubscription: () -> Unit,
    onRefreshSubscription: () -> Unit,
    onScanQr: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<VpnProfile?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubscriptionCard(
            state = subscription,
            onLinkChange = onSubscriptionLinkChange,
            onImport = onImportSubscription,
            onRefresh = onRefreshSubscription,
            onScanQr = onScanQr,
        )

        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.no_servers_yet),
                style = MaterialTheme.typography.bodyLarge,
                color = TifusiTextSecondary,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ServerRow(
                    profile = profile,
                    isSelected = profile.id == selectedProfileId,
                    onClick = { onSelectProfile(profile) },
                    onEdit = { onEditProfile(profile) },
                    onDelete = { pendingDelete = profile },
                )
            }
        }

        // Manual entry stays as a fallback for servers that aren't on a Tifusi Panel.
        TextButton(onClick = onAddManually, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null, tint = TifusiTextSecondary)
            Text(
                stringResource(R.string.add_manually),
                color = TifusiTextSecondary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_confirm, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteProfile(profile)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SubscriptionCard(
    state: SubscriptionUiState,
    onLinkChange: (String) -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onScanQr: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TifusiSurface)
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.subscription_title), style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = state.link,
            onValueChange = onLinkChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.subscription_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailingIcon = {
                Row {
                    IconButton(onClick = onScanQr) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr_code))
                    }
                    // Long-press copy/paste is unreliable on some phones, so paste is one tap here.
                    IconButton(onClick = { clipboard.getText()?.text?.let { onLinkChange(it.trim()) } }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.subscription_paste))
                    }
                }
            },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onImport,
                enabled = !state.isLoading && state.link.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = TifusiNeonBlue),
            ) {
                Text(stringResource(R.string.subscription_add))
            }
            if (state.savedLink != null) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(stringResource(R.string.subscription_refresh), modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        when (val message = state.message) {
            is SubscriptionMessage.Imported -> Text(
                stringResource(R.string.subscription_imported, message.count),
                color = TifusiNeonBlue,
                style = MaterialTheme.typography.bodyMedium,
            )
            is SubscriptionMessage.Failed -> Text(
                message.error.label(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            null -> Unit
        }
    }
}

@Composable
private fun SubscriptionError.label(): String = when (this) {
    SubscriptionError.NotASubscriptionLink -> stringResource(R.string.sub_error_link)
    SubscriptionError.NotFound -> stringResource(R.string.sub_error_not_found)
    SubscriptionError.DeviceLimit -> stringResource(R.string.sub_error_device_limit)
    SubscriptionError.PanelOutdated -> stringResource(R.string.sub_error_panel_outdated)
    SubscriptionError.NoServers -> stringResource(R.string.sub_error_no_servers)
    is SubscriptionError.Network -> stringResource(R.string.sub_error_network, detail ?: "-")
}

@Composable
private fun ServerRow(
    profile: VpnProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) TifusiSurfaceVariant else TifusiSurface)
            .border(1.dp, if (isSelected) TifusiNeonBlue else TifusiCardBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        profile.countryFlagEmoji?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(profile.name.ifBlank { profile.serverAddress }, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${profile.protocol.name} · ${profile.serverAddress}",
                style = MaterialTheme.typography.bodyMedium,
                color = TifusiTextSecondary,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit), tint = TifusiTextSecondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = TifusiTextSecondary)
        }
    }
}
