package com.tifusi.vpn.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val TifusiColorScheme = darkColorScheme(
    primary = TifusiNeonBlue,
    secondary = TifusiNeonBlueDeep,
    tertiary = TifusiNeonGreen,
    background = TifusiBackground,
    surface = TifusiSurface,
    surfaceVariant = TifusiSurfaceVariant,
    error = TifusiNeonRed,
    onPrimary = TifusiTextPrimary,
    onBackground = TifusiTextPrimary,
    onSurface = TifusiTextPrimary,
    outline = TifusiCardBorder,
)

@Composable
fun TifusiVpnTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = TifusiBackground.toArgb()
            window.navigationBarColor = TifusiBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = TifusiColorScheme,
        typography = TifusiTypography,
        content = content,
    )
}
