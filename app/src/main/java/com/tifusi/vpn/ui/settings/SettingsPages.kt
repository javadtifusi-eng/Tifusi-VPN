package com.tifusi.vpn.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tifusi.vpn.R
import com.tifusi.vpn.data.AppSettings
import com.tifusi.vpn.data.SpeedTest
import com.tifusi.vpn.data.SubscriptionInfo
import com.tifusi.vpn.data.TunnelSettings
import com.tifusi.vpn.ui.components.Panel
import com.tifusi.vpn.ui.components.PanelDivider
import com.tifusi.vpn.ui.components.PanelRow
import com.tifusi.vpn.ui.components.PanelSwitch
import com.tifusi.vpn.ui.profile.AccountCard
import com.tifusi.vpn.ui.servers.SubscriptionCard
import com.tifusi.vpn.ui.servers.SubscriptionUiState
import com.tifusi.vpn.ui.theme.AccentCyan
import com.tifusi.vpn.ui.theme.PanelCard
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun Note(text: String) {
    Text(text, color = TifusiTextSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

/** A list of choices with a check on the selected one. */
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Panel {
        options.forEachIndexed { index, (value, label) ->
            if (index > 0) PanelDivider()
            PanelRow(label = label, onClick = { onSelect(value) }) {
                if (value == selected) Icon(Icons.Default.Check, contentDescription = null, tint = AccentCyan)
            }
        }
    }
}

@Composable
fun TunnelSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by AppSettings.state.collectAsState()
    SubPage(stringResource(R.string.tunnel_settings), onBack) {
        Note(stringResource(R.string.mtu) + " — " + stringResource(R.string.mtu_hint))
        Choices(TunnelSettings.MTUS.map { it to it.toString() }, settings.mtu) { mtu -> AppSettings.update(context) { it.copy(mtu = mtu) } }
        Note(stringResource(R.string.applies_next_connect))
    }
}

@Composable
fun DnsSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by AppSettings.state.collectAsState()
    SubPage(stringResource(R.string.dns_settings), onBack) {
        Note(stringResource(R.string.dns_hint))
        Choices(
            TunnelSettings.DNS_PRESETS.map { (name, servers) -> name to "$name  ·  ${servers.joinToString(", ")}" },
            settings.dns,
        ) { dns -> AppSettings.update(context) { it.copy(dns = dns) } }
        Note(stringResource(R.string.applies_next_connect))
    }
}

@Composable
fun RouteSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by AppSettings.state.collectAsState()
    SubPage(stringResource(R.string.route_settings), onBack) {
        Panel {
            PanelRow(label = stringResource(R.string.bypass_iran), hint = stringResource(R.string.bypass_iran_hint)) {
                PanelSwitch(settings.bypassIran) { on -> AppSettings.update(context) { it.copy(bypassIran = on) } }
            }
        }
        Note(stringResource(R.string.applies_next_connect))
    }
}

@Composable
fun SubscriptionSettingsPage(
    state: SubscriptionUiState,
    onLinkChange: (String) -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onScanQr: () -> Unit,
    onBack: () -> Unit,
) {
    SubPage(stringResource(R.string.subscription_settings), onBack) {
        SubscriptionCard(state = state, onLinkChange = onLinkChange, onImport = onImport, onRefresh = onRefresh, onScanQr = onScanQr)
    }
}

@Composable
fun SubscriptionInfoPage(info: SubscriptionInfo?, onBack: () -> Unit) {
    SubPage(stringResource(R.string.subscription_info), onBack) {
        if (info != null) AccountCard(info) else Note(stringResource(R.string.no_subscription_info))
    }
}

@Composable
fun SpeedTestPage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<Double?>(null) }
    var result by remember { mutableStateOf<SpeedTest.Result?>(null) }
    var failed by remember { mutableStateOf(false) }

    SubPage(stringResource(R.string.speed_test), onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelCard).padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.speed_download), color = TifusiTextSecondary, fontSize = 13.sp)
            val shown = if (running) live else result?.mbps
            Text(
                shown?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                color = Color.White,
                fontSize = 54.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text("Mbps", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        result?.let {
            Note(stringResource(if (it.throughVpn) R.string.speed_through_vpn else R.string.speed_direct))
        }
        if (failed) Note(stringResource(R.string.speed_failed))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(if (running) PanelCard else Color.White)
                .clickable(enabled = !running) {
                    running = true
                    failed = false
                    live = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { SpeedTest.run { mbps -> live = mbps } }
                        result = r
                        failed = r == null
                        running = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (running) R.string.speed_testing else R.string.speed_start),
                color = if (running) TifusiTextSecondary else Color.Black,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

