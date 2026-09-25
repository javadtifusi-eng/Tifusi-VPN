package com.tifusi.vpn.ui.components

import com.tifusi.vpn.ui.theme.AccentGreen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import kotlin.math.roundToInt

internal val CellBackground = Color(0xFF0B0B0D)

/** Status, time, country and protocol in one card, V2Box style; speeds live in [TrafficMeter]. */
@Composable
fun HomeHero(
    statusLabel: String,
    isConnected: Boolean,
    durationLabel: String,
    flag: String?,
    country: String,
    protocol: String,
    /** Days and data left, and the latency line (tap to measure again); null hides each. */
    quotaLabel: String?,
    latencyLabel: String?,
    onLatencyClick: (() -> Unit)?,
    onServerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF16171A), Color(0xFF0C0C0E))))
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell(label = stringResource(R.string.hero_status), modifier = Modifier.weight(1f)) {
                Text(statusLabel, color = if (isConnected) TifusiNeonGreen else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Cell(label = stringResource(R.string.hero_time), modifier = Modifier.weight(1f)) {
                Text(durationLabel, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell(label = stringResource(R.string.hero_country), modifier = Modifier.weight(1f).clickable(onClick = onServerClick)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    flag?.let { Text(it, fontSize = 20.sp, modifier = Modifier.padding(end = 6.dp)) }
                    Text(country, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                }
            }
            Cell(label = stringResource(R.string.hero_protocol), modifier = Modifier.weight(1f).clickable(onClick = onServerClick)) {
                Text(protocol, color = TifusiNeonBlue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp)
            }
        }
        if (quotaLabel != null || latencyLabel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                quotaLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TifusiTextSecondary, modifier = Modifier.weight(1f))
                }
                latencyLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TifusiTextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (onLatencyClick != null) Modifier.clickable(onClick = onLatencyClick) else Modifier)
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Cell(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CellBackground)
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = TifusiTextSecondary, fontSize = 10.5.sp)
        content()
    }
}

/**
 * A knob dragged across the bar connects, and dragged back disconnects; past 70% of the way it
 * commits, short of that it springs back. Tapping while connecting cancels, like the old button.
 */
@Composable
fun SlideToConnect(
    isConnected: Boolean,
    isConnecting: Boolean,
    label: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var drag by remember { mutableFloatStateOf(0f) }
    val barColor = if (isConnected) AccentGreen else Color.White
    val textColor = if (isConnected) Color.White else Color.Black

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(barColor)
            .then(if (isConnecting) Modifier.clickable(onClick = onToggle) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val knob = with(density) { 50.dp.toPx() }
        val travel = (constraints.maxWidth - knob - with(density) { 14.dp.toPx() }).coerceAtLeast(1f)
        Text(label, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.alpha(1f - drag / travel))
        // Disconnected: the knob waits at the start; connected: at the end.
        val rest = if (isConnected) travel else 0f
        val position = (if (isConnected) rest - drag else drag).coerceIn(0f, travel)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((with(density) { 7.dp.toPx() } + position).roundToInt(), 0) }
                .size(50.dp)
                .clip(CircleShape)
                .background(if (isConnected) Color.White else Color.Black)
                .pointerInput(isConnected, isConnecting, travel) {
                    if (isConnecting) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag > travel * 0.7f) onToggle()
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                    ) { change, amount ->
                        change.consume()
                        // Towards the end of the bar is positive; in RTL that is leftwards.
                        val towardEnd = if (rtl) -amount else amount
                        drag = (drag + if (isConnected) -towardEnd else towardEnd).coerceIn(0f, travel)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = if (isConnected) AccentGreen else Color.White)
            } else {
                Text(if (rtl) "‹" else "›", color = if (isConnected) AccentGreen else Color.White, fontSize = 22.sp)
            }
        }
    }
}
