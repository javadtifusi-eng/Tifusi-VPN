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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiSurface
import com.tifusi.vpn.ui.theme.TifusiTextSecondary

@Composable
fun StatusCard(
    statusLabel: String,
    durationLabel: String,
    serverName: String?,
    serverLocation: String?,
    flagEmoji: String?,
    downloadLabel: String,
    uploadLabel: String,
    speedLabel: String,
    /** Tapping the latency re-measures it at once; null leaves it read-only. */
    onSpeedClick: (() -> Unit)? = null,
    /** Days and data left on the subscription; null hides the row. */
    quotaLabel: String?,
    isConnected: Boolean,
    onServerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(TifusiSurface)
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) TifusiNeonGreen else TifusiTextSecondary),
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isConnected) TifusiNeonGreen else Color.White,
                )
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TifusiTextSecondary,
                )
            }

            Box(modifier = Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onServerClick),
            ) {
                flagEmoji?.let {
                    Text(text = it, style = MaterialTheme.typography.titleLarge)
                }
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        text = serverName ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    serverLocation?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TifusiTextSecondary,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TifusiTextSecondary,
                )
            }
        }

        Divider(color = TifusiCardBorder)

        // Equal thirds, so a long value in one slot ("12.5 Mbps", a failure message) can never push
        // its neighbours together. Latency gets its own icon: under the speedometer it read as a
        // third speed figure.
        Row(modifier = Modifier.fillMaxWidth()) {
            TrafficStat(Icons.Default.ArrowDownward, downloadLabel, Modifier.weight(1f))
            TrafficStat(Icons.Default.ArrowUpward, uploadLabel, Modifier.weight(1f))
            TrafficStat(
                Icons.Default.NetworkPing,
                speedLabel,
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (onSpeedClick != null) Modifier.clickable(onClick = onSpeedClick) else Modifier),
            )
        }

        quotaLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = TifusiTextSecondary,
            )
        }
    }
}

@Composable
private fun TrafficStat(icon: ImageVector, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TifusiNeonBlue,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
