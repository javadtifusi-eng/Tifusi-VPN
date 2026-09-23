package com.tifusi.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiNeonRed
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import kotlin.math.log10
import kotlin.math.roundToInt

private const val LEDS = 24
private val LedOff = Color(0xFF151517)

/**
 * Download and upload as two rows of LEDs, lit on a log scale up to 10 MB/s, with the live speed
 * and the tunnel's total beside each. Null speeds (not connected) leave every LED dark.
 */
@Composable
fun TrafficMeter(
    downloadBytesPerSec: Long?,
    uploadBytesPerSec: Long?,
    downloadTotal: Long?,
    uploadTotal: Long?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LedRow("▼", TifusiNeonGreen, downloadBytesPerSec, downloadTotal)
        LedRow("▲", TifusiNeonBlue, uploadBytesPerSec, uploadTotal)
    }
}

@Composable
private fun LedRow(arrow: String, color: Color, bytesPerSec: Long?, total: Long?) {
    val lit = ((level(bytesPerSec ?: 0)) * LEDS).roundToInt()
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(arrow, color = color, style = mono.copy(fontSize = 15.sp), modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
        Row(modifier = Modifier.weight(1f).height(20.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(LEDS) { i ->
                val on = i < lit
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                !on -> LedOff
                                i >= LEDS - 4 -> TifusiNeonRed
                                i >= LEDS - 8 -> Color.White
                                else -> color
                            },
                        ),
                )
            }
        }
        Column(modifier = Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
            Text(bytesPerSec?.let { formatBytes(it) + "/s" } ?: "—", color = color, style = mono.copy(fontSize = 13.sp), maxLines = 1)
            Text(total?.let(::formatBytes) ?: "", color = TifusiTextSecondary, style = mono.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal), maxLines = 1)
        }
    }
}

/** 0..1 on a log scale, so a few KB/s already lights something and 10 MB/s fills the row. */
private fun level(bytesPerSec: Long): Float {
    if (bytesPerSec <= 0) return 0f
    return (log10(1.0 + bytesPerSec / 1024.0) / log10(1.0 + 10240.0)).toFloat().coerceIn(0f, 1f)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
}
