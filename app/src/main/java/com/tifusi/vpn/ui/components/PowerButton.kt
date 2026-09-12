package com.tifusi.vpn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiTextSecondary

/**
 * The central connect/disconnect control: a glowing ring whose colour tracks connection state,
 * pulsing while a tunnel is being negotiated.
 */
@Composable
fun PowerButton(
    isConnected: Boolean,
    isConnecting: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ringColor = when {
        isConnected -> TifusiNeonGreen
        isConnecting -> TifusiNeonBlue
        else -> TifusiTextSecondary
    }

    val transition = rememberInfiniteTransition(label = "power-glow")
    val glowAlpha by transition.animateFloat(
        initialValue = if (isConnecting) 0.25f else 0.55f,
        targetValue = if (isConnecting) 0.75f else 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "power-glow-alpha",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .drawBehind {
                    // Outer halo, then the crisp ring, to get the neon look from the mockup.
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(ringColor.copy(alpha = glowAlpha), Color.Transparent),
                            center = Offset(size.width / 2, size.height / 2),
                            radius = size.minDimension / 2,
                        ),
                        radius = size.minDimension / 2,
                    )
                    drawCircle(
                        color = ringColor,
                        radius = size.minDimension / 2 - 14.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx()),
                    )
                }
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(64.dp),
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}
