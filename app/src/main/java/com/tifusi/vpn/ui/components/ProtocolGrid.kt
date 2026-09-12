package com.tifusi.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiSurface
import com.tifusi.vpn.ui.theme.TifusiSurfaceVariant
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.VpnProtocol

@Composable
fun ProtocolGrid(
    selected: VpnProtocol,
    onSelect: (VpnProtocol) -> Unit,
    modifier: Modifier = Modifier,
) {
    val protocols = VpnProtocol.entries.toList()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        protocols.chunked(2).forEach { rowProtocols ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowProtocols.forEach { protocol ->
                    ProtocolCard(
                        protocol = protocol,
                        isSelected = protocol == selected,
                        onClick = { onSelect(protocol) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the last row aligned when the protocol count is odd.
                if (rowProtocols.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProtocolCard(
    protocol: VpnProtocol,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) TifusiNeonBlue else TifusiCardBorder

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) TifusiSurfaceVariant else TifusiSurface)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(TifusiSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = protocol.icon(),
                contentDescription = null,
                tint = TifusiNeonBlue,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                text = stringResource(protocol.titleRes()),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = stringResource(protocol.taglineRes()),
                style = MaterialTheme.typography.labelSmall,
                color = TifusiTextSecondary,
            )
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = TifusiNeonBlue,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun VpnProtocol.icon(): ImageVector = when (this) {
    VpnProtocol.IKEV2 -> Icons.Default.Security
    VpnProtocol.L2TP -> Icons.Default.Link
    VpnProtocol.PPTP -> Icons.Default.Hub
    VpnProtocol.WIREGUARD -> Icons.Default.Lock
}

private fun VpnProtocol.titleRes(): Int = when (this) {
    VpnProtocol.IKEV2 -> R.string.protocol_ikev2
    VpnProtocol.L2TP -> R.string.protocol_l2tp
    VpnProtocol.PPTP -> R.string.protocol_pptp
    VpnProtocol.WIREGUARD -> R.string.protocol_wireguard
}

private fun VpnProtocol.taglineRes(): Int = when (this) {
    VpnProtocol.IKEV2 -> R.string.protocol_ikev2_tagline
    VpnProtocol.L2TP -> R.string.protocol_l2tp_tagline
    VpnProtocol.PPTP -> R.string.protocol_pptp_tagline
    VpnProtocol.WIREGUARD -> R.string.protocol_wireguard_tagline
}
