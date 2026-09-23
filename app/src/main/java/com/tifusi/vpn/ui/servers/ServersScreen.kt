package com.tifusi.vpn.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tifusi.vpn.R
import com.tifusi.vpn.data.SubscriptionClient
import com.tifusi.vpn.data.SubscriptionError
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiNeonRed
import com.tifusi.vpn.ui.theme.TifusiSurface
import com.tifusi.vpn.ui.theme.TifusiSurfaceVariant
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.Hysteria2Link
import com.tifusi.vpn.vpn.VlessLink
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private val PingAmber = Color(0xFFFFB300)

/** A ping result: null not measured yet, [PING_RUNNING] measuring, [PING_TIMEOUT] no answer. */
private const val PING_RUNNING = -1L
private const val PING_TIMEOUT = 0L

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
    onRemoveSubscription: () -> Unit,
    onScanQr: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<VpnProfile?>(null) }
    var confirmRemoveSubscription by remember { mutableStateOf(false) }
    val pings = remember { mutableStateMapOf<String, Long>() }
    val scope = rememberCoroutineScope()
    val open = rememberSaveable { mutableStateOf(setOf("sub", "ikev2", "manual")) }

    val fromSubscription = profiles.filter { it.id.startsWith(SubscriptionClient.ID_PREFIX) && it.protocol != VpnProtocol.IKEV2 }
    val ikev2 = profiles.filter { it.protocol == VpnProtocol.IKEV2 }
    val manual = profiles.filter { !it.id.startsWith(SubscriptionClient.ID_PREFIX) && it.protocol != VpnProtocol.IKEV2 }

    fun pingAll() {
        profiles.forEach { profile ->
            val target = endpoint(profile) ?: return@forEach
            if (!pingable(profile)) return@forEach
            pings[profile.id] = PING_RUNNING
            scope.launch { pings[profile.id] = withContext(Dispatchers.IO) { tcpPing(target.first, target.second) } }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(onScanQr = onScanQr, onAdd = onAddManually)

        if (subscription.savedLink == null) {
            SubscriptionCard(
                state = subscription,
                onLinkChange = onSubscriptionLinkChange,
                onImport = onImportSubscription,
                onRefresh = onRefreshSubscription,
                onScanQr = onScanQr,
            )
        }

        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.no_servers_yet),
                style = MaterialTheme.typography.bodyLarge,
                color = TifusiTextSecondary,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.servers_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TifusiTextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = ::pingAll) {
                    Text(stringResource(R.string.ping_all), color = TifusiNeonBlue, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        val groups = listOf(
            Triple("sub", stringResource(R.string.group_subscription), fromSubscription),
            Triple("ikev2", "IKEv2", ikev2),
            Triple("manual", stringResource(R.string.group_manual), manual),
        ).filter { it.third.isNotEmpty() }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { (key, title, group) ->
                val expanded = key in open.value
                item(key = "head:$key") {
                    GroupHeader(
                        title = title,
                        count = group.size,
                        expanded = expanded,
                        onToggle = { open.value = if (expanded) open.value - key else open.value + key },
                    )
                }
                if (expanded && key == "sub") {
                    item(key = "refresh") {
                        RefreshButton(
                            state = subscription,
                            onRefresh = onRefreshSubscription,
                            onRemove = { confirmRemoveSubscription = true },
                        )
                    }
                }
                if (expanded) {
                    // Once measured, the fastest servers come first; unmeasured and timeouts last.
                    val ordered = if (group.any { (pings[it.id] ?: 0L) > 0L }) {
                        group.sortedBy { pings[it.id]?.takeIf { v -> v > 0L } ?: Long.MAX_VALUE }
                    } else {
                        group
                    }
                    items(ordered, key = { it.id }) { profile ->
                        ServerRow(
                            profile = profile,
                            ping = pings[profile.id],
                            isSelected = profile.id == selectedProfileId,
                            onClick = { onSelectProfile(profile) },
                            onEdit = { onEditProfile(profile) },
                            onDelete = { pendingDelete = profile },
                        )
                    }
                }
            }
        }
    }

    if (confirmRemoveSubscription) {
        AlertDialog(
            onDismissRequest = { confirmRemoveSubscription = false },
            title = { Text(stringResource(R.string.subscription_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveSubscription()
                    confirmRemoveSubscription = false
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveSubscription = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
private fun Header(onScanQr: () -> Unit, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        HeaderButton(Icons.Default.Add, stringResource(R.string.add_manually), onAdd)
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(TifusiSurface)
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = TifusiNeonBlue)
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TifusiSurface)
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            tint = TifusiNeonBlue,
            modifier = Modifier.rotate(if (expanded) 0f else 90f),
        )
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun RefreshButton(state: SubscriptionUiState, onRefresh: () -> Unit, onRemove: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, TifusiNeonBlue.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .clickable(enabled = !state.isLoading, onClick = onRefresh)
                .padding(10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TifusiNeonBlue)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = TifusiNeonBlue, modifier = Modifier.size(18.dp))
            }
            Text(
                stringResource(R.string.subscription_update),
                color = TifusiNeonBlue,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, TifusiNeonRed.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .clickable(enabled = !state.isLoading, onClick = onRemove)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.subscription_remove), color = TifusiNeonRed, fontWeight = FontWeight.SemiBold)
        }
      }
        when (val message = state.message) {
            is SubscriptionMessage.Imported -> Text(
                stringResource(R.string.subscription_imported, message.count),
                color = TifusiNeonBlue,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            is SubscriptionMessage.Failed -> Text(
                message.error.label(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            null -> Unit
        }
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
    ping: Long?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val flag = profile.countryFlagEmoji ?: flagIn(profile.name)
    val name = profile.name.let { if (flag != null) it.replace(flag, "") else it }.trim().ifBlank { profile.serverAddress }
    val endpoint = endpoint(profile)

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Protocol, written sideways down a narrow tab like V2Box.
        Box(
            modifier = Modifier
                .width(30.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) TifusiNeonBlue else TifusiSurface)
                .border(1.dp, if (isSelected) TifusiNeonBlue else TifusiCardBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                protocolLabel(profile),
                color = if (isSelected) Color.Black else TifusiTextSecondary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                modifier = Modifier.vertical().rotate(-90f),
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isSelected) TifusiSurfaceVariant else TifusiSurface)
                .border(1.dp, if (isSelected) TifusiNeonBlue else TifusiCardBorder, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            flag?.let { Text(it, fontSize = 26.sp, modifier = Modifier.padding(end = 10.dp)) }
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    endpoint?.let { "${it.first}:${it.second}" } ?: profile.serverAddress,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TifusiTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PingPill(ping)
        }
        Box(
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(TifusiSurface)
                .border(1.dp, TifusiCardBorder, RoundedCornerShape(12.dp))
                .clickable { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_edit), tint = TifusiTextSecondary)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun PingPill(ping: Long?) {
    val (text, color) = when {
        ping == null -> "—" to TifusiTextSecondary
        ping == PING_RUNNING -> "…" to TifusiTextSecondary
        ping == PING_TIMEOUT -> "timeout" to TifusiNeonRed
        ping < 400 -> "${ping}ms" to TifusiNeonGreen
        ping < 900 -> "${ping}ms" to PingAmber
        else -> "${ping}ms" to TifusiNeonRed
    }
    Text(
        text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (ping == null || ping == PING_RUNNING) Color(0xFF1C1C1F) else color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Swaps width and height before [rotate], so sideways text takes its rotated room in layout. */
private fun Modifier.vertical() = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: 200))
    layout(placeable.height, placeable.width) {
        placeable.place(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2)
    }
}

/** What the tab says: the transport a VLESS link actually uses, not just "VLESS". */
private fun protocolLabel(profile: VpnProfile): String = when (profile.protocol) {
    VpnProtocol.IKEV2 -> "IKEv2"
    VpnProtocol.HYSTERIA2 -> "HY2"
    VpnProtocol.VLESS -> runCatching { VlessLink.parse(profile.vlessLink.orEmpty()) }.getOrNull()?.let { link ->
        when {
            link.security == VlessLink.SECURITY_REALITY -> "REALITY"
            link.network == VlessLink.NETWORK_WS -> "WS"
            link.network == VlessLink.NETWORK_GRPC -> "gRPC"
            link.network == VlessLink.NETWORK_XHTTP -> "XHTTP"
            link.network == VlessLink.NETWORK_HTTPUPGRADE -> "HTTPU"
            link.security == VlessLink.SECURITY_TLS -> "TLS"
            else -> "VLESS"
        }
    } ?: "VLESS"
}

/** Host and port a profile dials, for display and ping. */
private fun endpoint(profile: VpnProfile): Pair<String, Int>? = when (profile.protocol) {
    VpnProtocol.VLESS -> runCatching { VlessLink.parse(profile.vlessLink.orEmpty()) }.getOrNull()?.let { it.address to it.port }
    VpnProtocol.HYSTERIA2 -> runCatching { Hysteria2Link.parse(profile.hysteria2Link.orEmpty()) }.getOrNull()?.let { link -> link.port.toIntOrNull()?.let { link.address to it } }
    VpnProtocol.IKEV2 -> profile.serverAddress to 4500
}

/** TCP only: Hysteria2 and IKEv2 are UDP, where a connect time means nothing. */
private fun pingable(profile: VpnProfile) = profile.protocol == VpnProtocol.VLESS

/** Time to open a TCP connection, the usual "ping" of proxy apps; [PING_TIMEOUT] on failure. */
private fun tcpPing(host: String, port: Int): Long = runCatching {
    Socket().use { socket ->
        val start = System.nanoTime()
        socket.connect(InetSocketAddress(host, port), 3000)
        ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
    }
}.getOrDefault(PING_TIMEOUT)

/** The first flag emoji (a pair of regional indicator letters) in a server's name. */
private fun flagIn(text: String): String? {
    val cps = text.codePoints().toArray()
    for (i in 0 until cps.size - 1) {
        if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) return String(cps, i, 2)
    }
    return null
}
