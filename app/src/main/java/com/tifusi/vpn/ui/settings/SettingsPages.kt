package com.tifusi.vpn.ui.settings

import kotlin.math.roundToInt
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
fun SubscriptionSettingsPage(
    state: SubscriptionUiState,
    onLinkChange: (String) -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    SubPage(stringResource(R.string.subscription_settings), onBack) {
        SubscriptionCard(state = state, onLinkChange = onLinkChange, onImport = onImport, onRefresh = onRefresh)
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
    var phase by remember { mutableStateOf<SpeedTest.Phase?>(null) }
    var liveDown by remember { mutableStateOf<Double?>(null) }
    var liveUp by remember { mutableStateOf<Double?>(null) }
    var result by remember { mutableStateOf<SpeedTest.Result?>(null) }
    var failed by remember { mutableStateOf(false) }

    SubPage(stringResource(R.string.speed_test), onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelCard).padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SpeedReadout(
                    label = stringResource(R.string.speed_download),
                    mbps = if (running) liveDown else result?.downMbps,
                    active = running && phase == SpeedTest.Phase.DOWNLOAD,
                )
                SpeedReadout(
                    label = stringResource(R.string.speed_upload),
                    mbps = if (running) liveUp else result?.upMbps,
                    active = running && phase == SpeedTest.Phase.UPLOAD,
                )
            }
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
                    phase = SpeedTest.Phase.DOWNLOAD
                    liveDown = null
                    liveUp = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            SpeedTest.run { p, mbps ->
                                phase = p
                                if (p == SpeedTest.Phase.DOWNLOAD) liveDown = mbps else liveUp = mbps
                            }
                        }
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

@Composable
private fun SpeedReadout(label: String, mbps: Double?, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (active) AccentCyan else TifusiTextSecondary, fontSize = 13.sp)
        Text(
            mbps?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text("Mbps", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PingSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by AppSettings.state.collectAsState()
    SubPage(stringResource(R.string.ping_settings), onBack) {
        Panel {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text(stringResource(R.string.ping_method), color = Color.White, fontSize = 15.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1E)).padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    TunnelSettings.PING_METHODS.forEach { method ->
                        val on = settings.pingMethod == method
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) Color(0xFF636366) else Color.Transparent)
                                .clickable { AppSettings.update(context) { it.copy(pingMethod = method) } }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(pingMethodLabel(method), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text(
                    stringResource(
                        when (settings.pingMethod) {
                            "tcp" -> R.string.ping_method_tcp_hint
                            "icmp" -> R.string.ping_method_icmp_hint
                            else -> R.string.ping_method_connection_hint
                        },
                    ),
                    color = TifusiTextSecondary,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            PanelDivider()
            SliderSetting(
                label = stringResource(R.string.ping_timeout, settings.pingTimeoutSec),
                value = settings.pingTimeoutSec,
                range = TunnelSettings.PING_TIMEOUTS,
                minLabel = stringResource(R.string.ping_seconds, TunnelSettings.PING_TIMEOUTS.first),
                maxLabel = stringResource(R.string.ping_seconds, TunnelSettings.PING_TIMEOUTS.last),
            ) { v -> AppSettings.update(context) { it.copy(pingTimeoutSec = v) } }
            PanelDivider()
            SliderSetting(
                label = stringResource(R.string.ping_concurrency, settings.pingConcurrency),
                value = settings.pingConcurrency,
                range = TunnelSettings.PING_CONCURRENCY,
                minLabel = TunnelSettings.PING_CONCURRENCY.first.toString(),
                maxLabel = TunnelSettings.PING_CONCURRENCY.last.toString(),
            ) { v -> AppSettings.update(context) { it.copy(pingConcurrency = v) } }
        }
        Note(stringResource(R.string.ping_endpoint))
        Choices(
            TunnelSettings.PING_ENDPOINTS.map { (name, url) -> name to "$name  ·  ${url.substringAfter("://").substringBefore('/')}" },
            settings.pingEndpoint,
        ) { e -> AppSettings.update(context) { it.copy(pingEndpoint = e) } }
    }
}

private fun pingMethodLabel(method: String) = when (method) {
    "tcp" -> "TCP"
    "icmp" -> "ICMP"
    else -> "Connection"
}

/** A labelled whole-number slider with its bounds written at each end. */
@Composable
private fun SliderSetting(label: String, value: Int, range: IntRange, minLabel: String, maxLabel: String, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = Color.White, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(minLabel, color = Color.White, fontSize = 13.sp)
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AccentCyan),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(maxLabel, color = Color.White, fontSize = 13.sp)
        }
    }
}
