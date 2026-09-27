package com.tifusi.vpn.ui.home

import com.tifusi.vpn.vpn.Ikev2AuthType
import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.tifusi.vpn.ui.theme.PanelCard
import com.tifusi.vpn.ui.theme.PanelLine
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiSurfaceVariant
import com.tifusi.vpn.ui.theme.TifusiTextPrimary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tifusi.vpn.R
import com.tifusi.vpn.data.AppSettings
import com.tifusi.vpn.ui.components.DiscIcon
import com.tifusi.vpn.ui.components.LegacyHandoffCard
import com.tifusi.vpn.ui.components.Panel
import com.tifusi.vpn.ui.components.PanelDivider
import com.tifusi.vpn.ui.components.PanelIcon
import com.tifusi.vpn.ui.components.PanelRow
import com.tifusi.vpn.ui.components.SlideToConnect
import com.tifusi.vpn.ui.localized
import com.tifusi.vpn.ui.theme.AccentCyan
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.VpnConnectionState
import java.util.Locale

@Composable
fun HomeScreen(
    state: HomeUiState,
    onToggleConnection: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by AppSettings.state.collectAsState()
    val connectionState = state.connectionState
    val isConnected = connectionState is VpnConnectionState.Connected
    val isConnecting = connectionState is VpnConnectionState.Connecting
    val zero = stringResource(R.string.zero_kb)

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Logo(Modifier.padding(start = 6.dp, top = 10.dp, bottom = 16.dp))

        // Scrolls on short screens, so a taller panel or an error never pushes the slider off-screen.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // PSK profiles run on the platform client, whose speeds come from device totals; the built-in engine's are exact.
        val approx = if (isConnected && state.selectedProfile?.ikev2AuthType == Ikev2AuthType.PSK) "≈" else ""
        StatusCard(state = state, isConnected = isConnected, isConnecting = isConnecting)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedTile(
                label = stringResource(R.string.speed_download),
                value = state.downloadBytesPerSec.takeIf { isConnected }?.let { approx + formatBytes(it) } ?: "—",
                icon = Icons.Default.ArrowDownward,
                tint = TifusiNeonGreen,
                modifier = Modifier.weight(1f),
            )
            SpeedTile(
                label = stringResource(R.string.speed_upload),
                value = state.uploadBytesPerSec.takeIf { isConnected }?.let { approx + formatBytes(it) } ?: "—",
                icon = Icons.Default.ArrowUpward,
                tint = AccentCyan,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))

        if (state.selectedProfile == null) {
            Text(
                stringResource(R.string.no_profile_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = TifusiTextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        }

        // Outside the scrolling panel, right above the slider: on a small screen an error below
        // the panel would be scrolled out of sight.
        when (connectionState) {
            is VpnConnectionState.Invalid -> ErrorBlock(connectionState.issues.map { it.localized(context) }, onDismissMessage)
            is VpnConnectionState.Failed -> ErrorBlock(listOf(connectionState.failure.localized(context)), onDismissMessage)
            is VpnConnectionState.RequiresSystemSettings -> Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                LegacyHandoffCard(profile = connectionState.profile, onDismiss = onDismissMessage)
            }
            else -> Unit
        }

        SlideToConnect(
            isConnected = isConnected,
            isConnecting = isConnecting,
            label = when {
                isConnecting -> stringResource(R.string.status_connecting)
                isConnected -> stringResource(R.string.slide_disconnect)
                else -> stringResource(R.string.slide_connect)
            },
            onToggle = onToggleConnection,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
        )
    }
}

/** The white TIFUSI mark with its name underneath. */
@Composable
fun Logo(modifier: Modifier = Modifier, markHeight: Int = 34, nameSize: Float = 11f) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.ic_logo_mark),
            contentDescription = stringResource(R.string.app_name),
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(width = (markHeight * 1.83f).dp, height = markHeight.dp),
        )
        Text(
            "TIFUSI",
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = nameSize.sp,
            letterSpacing = (nameSize * 0.36f).sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun ErrorBlock(messages: List<String>, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        messages.forEach { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
    }
}

/** Server, state, time connected and latency, the first thing the eye lands on. */
@Composable
private fun StatusCard(state: HomeUiState, isConnected: Boolean, isConnecting: Boolean) {
    val accent = when {
        isConnected -> TifusiNeonGreen
        isConnecting -> AccentCyan
        else -> TifusiTextSecondary
    }
    val glow by animateColorAsState(accent, label = "status")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(glow.copy(alpha = 0.22f), PanelCard)))
            .border(1.dp, glow.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(84.dp).clip(CircleShape).background(glow.copy(alpha = 0.16f))
                .border(2.dp, glow, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = glow, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(
                when {
                    isConnected -> R.string.status_connected
                    isConnecting -> R.string.status_connecting
                    else -> R.string.status_disconnected
                },
            ),
            color = glow,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        val profile = state.selectedProfile
        if (profile != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(profile.countryFlagEmoji, profile.name).joinToString("  "),
                color = TifusiTextPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isConnected) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(Icons.Default.Timer, ltr(formatDuration(state.connectedSeconds)))
                val latency = when {
                    !state.internetChecked -> "…"
                    state.internetLatencyMs != null -> "${state.internetLatencyMs} ms"
                    else -> stringResource(R.string.internet_fail)
                }
                Chip(Icons.Default.NetworkPing, ltr(latency))
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(TifusiSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TifusiTextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(text, color = TifusiTextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun SpeedTile(label: String, value: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(PanelCard)
            .border(1.dp, PanelLine, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(26.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.size(8.dp))
            Text(label, color = TifusiTextSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(ltr(value), color = TifusiTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun formatDuration(seconds: Long): String =
    String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)

/** Keeps "≈1.75 MB" in its own order inside Persian (RTL) text, which otherwise shows "MB 1.75≈". */
// The translated zero ("صفر KB") is already in reading order, so only numbers are isolated.
private fun ltr(value: String) = if (value.firstOrNull()?.let { it.isDigit() || it == '≈' } == true) "\u2066$value\u2069" else value

internal fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.2f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}
