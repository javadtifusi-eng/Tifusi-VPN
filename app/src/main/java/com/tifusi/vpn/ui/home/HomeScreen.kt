package com.tifusi.vpn.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.components.LegacyHandoffCard
import com.tifusi.vpn.ui.components.PowerButton
import com.tifusi.vpn.ui.components.ProtocolGrid
import com.tifusi.vpn.ui.components.StatusCard
import com.tifusi.vpn.ui.localized
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.VpnConnectionState
import com.tifusi.vpn.vpn.VpnProtocol
import java.util.Locale

@Composable
fun HomeScreen(
    state: HomeUiState,
    onToggleConnection: () -> Unit,
    onSelectProtocol: (VpnProtocol) -> Unit,
    onServerClick: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val connectionState = state.connectionState
    val isConnected = connectionState is VpnConnectionState.Connected
    val isConnecting = connectionState is VpnConnectionState.Connecting

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StatusCard(
            statusLabel = when {
                isConnected -> stringResource(R.string.status_connected)
                isConnecting -> stringResource(R.string.status_connecting)
                else -> stringResource(R.string.status_disconnected)
            },
            durationLabel = formatDuration(state.connectedSeconds),
            serverName = state.selectedProfile?.countryName ?: state.selectedProfile?.name,
            serverLocation = state.selectedProfile?.serverAddress,
            flagEmoji = state.selectedProfile?.countryFlagEmoji,
            downloadLabel = formatBytes(state.trafficStats?.rxBytes),
            uploadLabel = formatBytes(state.trafficStats?.txBytes),
            speedLabel = "—",
            isConnected = isConnected,
            onServerClick = onServerClick,
        )

        PowerButton(
            isConnected = isConnected,
            isConnecting = isConnecting,
            label = if (isConnected || isConnecting) {
                stringResource(R.string.action_disconnect)
            } else {
                stringResource(R.string.action_connect)
            },
            onClick = onToggleConnection,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // Surface certificate/validation failures inline rather than failing silently.
        when (connectionState) {
            is VpnConnectionState.Invalid -> ErrorBlock(
                messages = connectionState.issues.map { it.localized(context) },
                onDismiss = onDismissMessage,
            )

            is VpnConnectionState.Failed -> ErrorBlock(
                messages = listOf(connectionState.failure.localized(context)),
                onDismiss = onDismissMessage,
            )

            is VpnConnectionState.RequiresSystemSettings -> LegacyHandoffCard(
                profile = connectionState.profile,
                onDismiss = onDismissMessage,
            )

            else -> Unit
        }

        Text(
            text = stringResource(R.string.select_protocol),
            style = MaterialTheme.typography.titleLarge,
        )

        ProtocolGrid(
            selected = state.selectedProtocol,
            onSelect = onSelectProtocol,
        )

        if (state.selectedProfile == null) {
            Text(
                text = stringResource(R.string.no_profile_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = TifusiTextSecondary,
            )
        }
    }
}

@Composable
private fun ErrorBlock(messages: List<String>, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        messages.forEach { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.action_dismiss))
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}
