package com.tifusi.vpn.ui.home

import com.tifusi.vpn.ui.components.HomeHero
import com.tifusi.vpn.ui.components.SlideToConnect
import com.tifusi.vpn.ui.components.TrafficMeter
import com.tifusi.vpn.ui.components.flag
import com.tifusi.vpn.ui.components.plainName
import com.tifusi.vpn.ui.components.protocolLabel
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import com.tifusi.vpn.data.SubscriptionClient
import com.tifusi.vpn.data.SubscriptionInfo
import com.tifusi.vpn.ui.components.LegacyHandoffCard
import com.tifusi.vpn.ui.components.ProtocolGrid
import com.tifusi.vpn.ui.localized
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.VpnConnectionState
import com.tifusi.vpn.vpn.VpnProtocol
import java.util.Locale
import kotlin.math.ceil

@Composable
fun HomeScreen(
    state: HomeUiState,
    onToggleConnection: () -> Unit,
    onSelectProtocol: (VpnProtocol) -> Unit,
    onServerClick: () -> Unit,
    onDismissMessage: () -> Unit,
    onRetestLatency: () -> Unit = {},
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
        val profile = state.selectedProfile
        HomeHero(
            statusLabel = when {
                isConnected -> stringResource(R.string.status_connected)
                isConnecting -> stringResource(R.string.status_connecting)
                else -> stringResource(R.string.status_disconnected)
            },
            isConnected = isConnected,
            durationLabel = formatDuration(state.connectedSeconds),
            flag = profile?.flag(),
            country = profile?.plainName() ?: "—",
            protocol = profile?.let(::protocolLabel) ?: "—",
            // Only for servers that came from the subscription the numbers describe.
            quotaLabel = state.subscriptionInfo
                ?.takeIf { profile?.id?.startsWith(SubscriptionClient.ID_PREFIX) == true }
                ?.let { quotaLabel(it) },
            latencyLabel = when {
                !isConnected -> null
                !state.internetChecked -> "…"
                state.internetLatencyMs != null -> stringResource(R.string.internet_ok, state.internetLatencyMs.toInt())
                else -> stringResource(R.string.internet_fail)
            },
            onLatencyClick = if (isConnected) onRetestLatency else null,
            onServerClick = onServerClick,
        )

        TrafficMeter(
            downloadBytesPerSec = state.downloadBytesPerSec.takeIf { isConnected },
            uploadBytesPerSec = state.uploadBytesPerSec.takeIf { isConnected },
            downloadTotal = state.trafficStats?.rxBytes.takeIf { isConnected },
            uploadTotal = state.trafficStats?.txBytes.takeIf { isConnected },
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(TifusiSurface)
                .border(1.dp, TifusiCardBorder, RoundedCornerShape(16.dp))
                .padding(12.dp),
        )

        SlideToConnect(
            isConnected = isConnected,
            isConnecting = isConnecting,
            label = when {
                isConnecting -> stringResource(R.string.status_connecting)
                isConnected -> stringResource(R.string.slide_disconnect)
                else -> stringResource(R.string.slide_connect)
            },
            onToggle = onToggleConnection,
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

@Composable
private fun quotaLabel(info: SubscriptionInfo): String {
    val secondsLeft = info.expireEpochSec?.let { it - System.currentTimeMillis() / 1000 }
    val days = when {
        secondsLeft == null -> stringResource(R.string.quota_no_expiry)
        secondsLeft <= 0 -> stringResource(R.string.quota_expired)
        else -> stringResource(R.string.quota_days_left, ceil(secondsLeft / 86_400.0).toInt())
    }
    val data = info.limitBytes?.let { limit ->
        stringResource(R.string.quota_data_left, formatBytes((limit - info.usedBytes).coerceAtLeast(0)))
    } ?: stringResource(R.string.quota_unlimited)
    return "⏳ $days   ·   📦 $data"
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
