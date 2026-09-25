package com.tifusi.vpn.ui.servers

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
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
import com.tifusi.vpn.ui.components.ScreenTitle
import com.tifusi.vpn.ui.components.flagIn
import com.tifusi.vpn.ui.components.protocolLabel
import com.tifusi.vpn.ui.theme.AccentCyan
import com.tifusi.vpn.ui.theme.PanelCard
import com.tifusi.vpn.ui.theme.PingFast
import com.tifusi.vpn.ui.theme.PingSlow
import com.tifusi.vpn.ui.theme.ProtoGrey
import com.tifusi.vpn.ui.theme.ProtoIkev2
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiNeonRed
import com.tifusi.vpn.ui.theme.TifusiSurface
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.Hysteria2Link
import com.tifusi.vpn.vpn.IkeProbe
import com.tifusi.vpn.vpn.VlessLink
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import com.tifusi.vpn.vpn.XrayProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    val context = LocalContext.current
    val open = rememberSaveable { mutableStateOf(setOf("sub", "ikev2", "manual")) }

    val fromSubscription = profiles.filter { it.id.startsWith(SubscriptionClient.ID_PREFIX) && it.protocol != VpnProtocol.IKEV2 }
    val ikev2 = profiles.filter { it.protocol == VpnProtocol.IKEV2 }
    val manual = profiles.filter { !it.id.startsWith(SubscriptionClient.ID_PREFIX) && it.protocol != VpnProtocol.IKEV2 }

    fun pingAll() {
        val targets = profiles.filter { pingable(it) }
        targets.forEach { pings[it.id] = PING_RUNNING }
        // Two at a time, not more: each probe spins up its own Xray instance, and on a low-end
        // phone running several at once starves the UI (and any live tunnel). A per-probe timeout
        // frees the slot so one dead server never blocks the queue.
        val gate = kotlinx.coroutines.sync.Semaphore(2)
        targets.forEach { profile ->
            scope.launch {
                // IKEv2 is one small UDP exchange, so it skips the Xray queue.
                pings[profile.id] = if (profile.protocol == VpnProtocol.IKEV2) {
                    withContext(Dispatchers.IO) { IkeProbe.delayMs(profile.serverAddress) } ?: PING_TIMEOUT
                } else gate.withPermit {
                    withContext(Dispatchers.IO) {
                        withTimeoutOrNull(8_000) {
                            XrayProbe.delayMs(context, profile.vlessLink.orEmpty())
                        } ?: PING_TIMEOUT
                    }
                }
            }
        }
    }

    fun share(profile: VpnProfile) {
        val link = profile.vlessLink ?: profile.hysteria2Link ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            hasSubscription = subscription.savedLink != null,
            onScanQr = onScanQr,
            onAdd = onAddManually,
            onPingAll = ::pingAll,
            onRefresh = onRefreshSubscription,
            onRemove = { confirmRemoveSubscription = true },
        )
        ScreenTitle(stringResource(R.string.configs_title), Modifier.padding(start = 22.dp, top = 4.dp, bottom = 10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.swipe_to_delete), color = TifusiTextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = ::pingAll) {
                Text(stringResource(R.string.ping_all), color = AccentCyan, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (subscription.savedLink == null) {
                SubscriptionCard(
                    state = subscription,
                    onLinkChange = onSubscriptionLinkChange,
                    onImport = onImportSubscription,
                    onRefresh = onRefreshSubscription,
                    onScanQr = onScanQr,
                )
            } else {
                SubscriptionStatus(subscription)
            }

            if (profiles.isEmpty()) {
                Text(stringResource(R.string.no_servers_yet), style = MaterialTheme.typography.bodyLarge, color = TifusiTextSecondary)
            }

            val groups = listOf(
                Triple("sub", stringResource(R.string.group_subscription) to stringResource(R.string.group_sub_hint), fromSubscription),
                Triple("ikev2", "IKEv2" to stringResource(R.string.group_ikev2_hint), ikev2),
                Triple("manual", stringResource(R.string.group_manual) to stringResource(R.string.group_local_hint), manual),
            ).filter { it.third.isNotEmpty() }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                groups.forEach { (key, titles, group) ->
                    val expanded = key in open.value
                    item(key = "head:$key") {
                        GroupHeader(
                            title = titles.first,
                            hint = titles.second,
                            count = group.size,
                            expanded = expanded,
                            isRefreshing = key == "sub" && subscription.isLoading,
                            onRefresh = if (key == "sub") onRefreshSubscription else null,
                            onToggle = { open.value = if (expanded) open.value - key else open.value + key },
                        )
                    }
                    if (expanded) {
                        // Once measured, the fastest servers come first; unmeasured and timeouts last.
                        val ordered = if (group.any { (pings[it.id] ?: 0L) > 0L }) {
                            group.sortedBy { pings[it.id]?.takeIf { v -> v > 0L } ?: Long.MAX_VALUE }
                        } else {
                            group
                        }
                        items(ordered, key = { it.id }) { profile ->
                            SwipeToDelete(onDelete = { pendingDelete = profile }) {
                                ServerRow(
                                    profile = profile,
                                    ping = pings[profile.id],
                                    isSelected = profile.id == selectedProfileId,
                                    onClick = { onSelectProfile(profile) },
                                    onEdit = { onEditProfile(profile) },
                                    onShare = if (profile.vlessLink != null || profile.hysteria2Link != null) {
                                        { share(profile) }
                                    } else null,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
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
private fun TopBar(
    hasSubscription: Boolean,
    onScanQr: () -> Unit,
    onAdd: () -> Unit,
    onPingAll: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onScanQr) { Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr_code), tint = Color.White) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_manually), tint = Color.White) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu_more), tint = Color.White) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ping_all)) }, onClick = { menu = false; onPingAll() })
                if (hasSubscription) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.subscription_update)) }, onClick = { menu = false; onRefresh() })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subscription_remove), color = TifusiNeonRed) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // The row springs back; deleting waits for the confirmation dialog.
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(TifusiNeonRed.copy(alpha = 0.25f)).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Text(stringResource(R.string.action_delete), color = TifusiNeonRed, fontWeight = FontWeight.SemiBold) }
        },
    ) { content() }
}

@Composable
private fun SubscriptionStatus(state: SubscriptionUiState) {
    when (val message = state.message) {
        is SubscriptionMessage.Imported -> Text(
            stringResource(R.string.subscription_imported, message.count),
            color = AccentCyan,
            style = MaterialTheme.typography.bodySmall,
        )
        is SubscriptionMessage.Failed -> Text(message.error.label(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        null -> Unit
    }
}

@Composable
private fun GroupHeader(
    title: String,
    hint: String,
    count: Int,
    expanded: Boolean,
    isRefreshing: Boolean,
    onRefresh: (() -> Unit)?,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PanelCard)
            .clickable(onClick = onToggle)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            tint = AccentCyan,
            modifier = Modifier.rotate(if (expanded) 0f else -90f),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("$title ($count)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = TifusiTextSecondary, fontSize = 11.5.sp)
        }
        if (onRefresh != null) {
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentCyan)
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.subscription_update), tint = AccentCyan)
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionCard(
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
internal fun SubscriptionError.label(): String = when (this) {
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
    onShare: (() -> Unit)?,
) {
    val flag = profile.countryFlagEmoji ?: flagIn(profile.name)
    val name = profile.name.let { if (flag != null) it.replace(flag, "") else it }.trim().ifBlank { profile.serverAddress }
    val endpoint = endpoint(profile)
    val label = protocolLabel(profile)
    val (tabColor, tabText) = when (profile.protocol) {
        VpnProtocol.VLESS -> AccentCyan to Color(0xFF00343C)
        VpnProtocol.IKEV2 -> ProtoIkev2 to Color(0xFFEAFFF6)
        VpnProtocol.HYSTERIA2 -> ProtoGrey to Color.White
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(PanelCard)
            .then(if (isSelected) Modifier.border(1.5.dp, AccentCyan, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Protocol, written sideways down a narrow tab like V2Box.
        Box(
            modifier = Modifier.width(30.dp).fillMaxHeight().background(tabColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = tabText,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                modifier = Modifier.vertical().rotate(-90f),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                listOfNotNull(name, flag).joinToString(" "),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    endpoint?.let { "${it.first}:${it.second}" } ?: profile.serverAddress,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TifusiTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PingPill(ping)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 10.dp)) {
            RoundButton(Icons.Default.Edit, stringResource(R.string.action_edit), onEdit)
            if (onShare != null) RoundButton(Icons.Default.Share, stringResource(R.string.action_share), onShare)
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).background(ProtoGrey).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PingPill(ping: Long?) {
    val (text, background, color) = when {
        ping == null -> Triple("—", Color(0xFF1C1C1F), TifusiTextSecondary)
        ping == PING_RUNNING -> Triple("…", Color(0xFF1C1C1F), TifusiTextSecondary)
        ping == PING_TIMEOUT -> Triple("timeout", TifusiNeonRed.copy(alpha = 0.22f), TifusiNeonRed)
        ping < 900 -> Triple("${ping}ms", PingFast, Color(0xFFEAFFF6))
        else -> Triple("${ping}ms", PingSlow, Color(0xFFF4F0D0))
    }
    Text(
        text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Swaps width and height before [rotate], so sideways text takes its rotated room in layout. */
private fun Modifier.vertical() = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: 200))
    layout(placeable.height, placeable.width) {
        placeable.place(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2)
    }
}

/** Host and port a profile dials, for display and ping. */
private fun endpoint(profile: VpnProfile): Pair<String, Int>? = when (profile.protocol) {
    VpnProtocol.VLESS -> runCatching { VlessLink.parse(profile.vlessLink.orEmpty()) }.getOrNull()?.let { it.address to it.port }
    VpnProtocol.HYSTERIA2 -> runCatching { Hysteria2Link.parse(profile.hysteria2Link.orEmpty()) }.getOrNull()?.let { link -> link.port.toIntOrNull()?.let { link.address to it } }
    VpnProtocol.IKEV2 -> profile.serverAddress to 4500
}

/** VLESS through Xray, IKEv2 by its daemon's answer; Hysteria2 runs outside both. */
private fun pingable(profile: VpnProfile) = profile.protocol == VpnProtocol.VLESS || profile.protocol == VpnProtocol.IKEV2

