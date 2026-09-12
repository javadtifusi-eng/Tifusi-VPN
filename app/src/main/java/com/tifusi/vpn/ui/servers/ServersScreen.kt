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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tifusi.vpn.R
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
    onScanQr: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<VpnProfile?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAddManually,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = TifusiNeonBlue),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_manually), modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = onScanQr, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Text(stringResource(R.string.scan_qr_code), modifier = Modifier.padding(start = 6.dp))
            }
        }

        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.no_servers_yet),
                style = MaterialTheme.typography.bodyLarge,
                color = TifusiTextSecondary,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
