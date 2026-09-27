package com.tifusi.vpn.ui.home

import com.tifusi.vpn.vpn.VpnProtocol
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.tifusi.vpn.data.TunnelSettings
import com.tifusi.vpn.ui.components.DiscIcon
import com.tifusi.vpn.ui.components.LegacyHandoffCard
import com.tifusi.vpn.ui.components.Panel
import com.tifusi.vpn.ui.components.PanelDivider
import com.tifusi.vpn.ui.components.PanelIcon
import com.tifusi.vpn.ui.components.PanelRow
import com.tifusi.vpn.ui.components.PanelSwitch
import com.tifusi.vpn.ui.components.PanelValue
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
    onOpenRouting: () -> Unit,
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
        Panel {
            var levelMenu by remember { mutableStateOf(false) }
            PanelRow(label = stringResource(R.string.log_level)) {
                Box {
                    PanelValue(settings.logLevel, onClick = { levelMenu = true }, trailingIcon = Icons.Default.KeyboardArrowDown)
                    DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                        TunnelSettings.LOG_LEVELS.forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level) },
                                onClick = {
                                    levelMenu = false
                                    AppSettings.update(context) { it.copy(logLevel = level) }
                                },
                            )
                        }
                    }
                }
            }
            PanelDivider()
            // Platform IKEv2 speeds come from device totals; the built-in engine's are exact.
            val estimated = isConnected && state.selectedProfile?.protocol == VpnProtocol.IKEV2 && !settings.ikev2BuiltIn
            val approx = if (estimated) "≈" else ""
            val trafficHint = if (estimated) stringResource(R.string.traffic_estimated) else null
            PanelRow(
                label = stringResource(R.string.memory_usage, ltr(state.memoryBytes?.let(::formatBytes) ?: zero)),
                icon = { PanelIcon(Icons.Default.Memory) },
            )
            PanelDivider()
            PanelRow(
                label = stringResource(R.string.upload_value, ltr(state.uploadBytesPerSec.takeIf { isConnected }?.let { approx + formatBytes(it) } ?: zero)),
                hint = trafficHint,
                icon = { DiscIcon(Icons.Default.ArrowUpward) },
            )
            PanelDivider()
            PanelRow(
                label = stringResource(R.string.download_value, ltr(state.downloadBytesPerSec.takeIf { isConnected }?.let { approx + formatBytes(it) } ?: zero)),
                icon = { DiscIcon(Icons.Default.ArrowDownward) },
            )
            PanelDivider()
            PanelRow(
                label = stringResource(R.string.stop_on_sleep),
                hint = stringResource(R.string.stop_on_sleep_hint),
                icon = { PanelIcon(Icons.Default.BatteryChargingFull, tint = AccentCyan) },
            ) {
                PanelSwitch(settings.stopOnSleep) { on -> AppSettings.update(context) { it.copy(stopOnSleep = on) } }
            }
            if (state.selectedProfile?.protocol == VpnProtocol.IKEV2) {
                PanelDivider()
                PanelRow(
                    label = stringResource(R.string.ikev2_strongswan),
                    hint = stringResource(R.string.ikev2_strongswan_hint),
                    icon = { PanelIcon(Icons.Default.Shield, tint = AccentCyan) },
                ) {
                    PanelSwitch(settings.ikev2BuiltIn) { on -> AppSettings.update(context) { it.copy(ikev2BuiltIn = on) } }
                }
            }
            PanelDivider()
            PanelRow(
                label = stringResource(R.string.routing),
                labelColor = AccentCyan,
                icon = { PanelIcon(Icons.Default.CallSplit, tint = AccentCyan) },
                onClick = onOpenRouting,
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

/** Keeps "≈1.75 MB" in its own order inside Persian (RTL) text, which otherwise shows "MB 1.75≈". */
private fun ltr(value: String) = "\u2066$value\u2069"

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
