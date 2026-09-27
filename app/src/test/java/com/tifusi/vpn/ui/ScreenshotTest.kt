package com.tifusi.vpn.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.robolectric.Shadows.shadowOf
import com.tifusi.vpn.ui.home.HomeScreen
import com.tifusi.vpn.ui.home.HomeUiState
import com.tifusi.vpn.ui.settings.SettingsScreen
import com.tifusi.vpn.ui.theme.TifusiBackground
import com.tifusi.vpn.ui.theme.TifusiVpnTheme
import com.tifusi.vpn.vpn.Ikev2AuthType
import com.tifusi.vpn.vpn.VpnConnectionState
import com.tifusi.vpn.vpn.VpnFailure
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens on small, typical and large phones, with enlarged fonts and in
 * Persian (RTL), into build/screenshots, so layout problems on phones nobody here owns show up
 * as images. Not an assertion test: CI uploads the images for review.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest(private val device: Device) {

    data class Device(val name: String, val qualifiers: String, val fontScale: Float) {
        override fun toString() = name
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun devices() = listOf(
            // A small, old phone (e.g. Galaxy J/A0x class) and one with Display size turned up.
            Device("small-fa", "fa-w320dp-h569dp-hdpi", 1.0f),
            Device("small-fa-bigfont", "fa-w320dp-h569dp-hdpi", 1.3f),
            Device("typical-fa", "fa-w360dp-h780dp-xxhdpi", 1.0f),
            Device("typical-fa-hugefont", "fa-w360dp-h780dp-xxhdpi", 1.6f),
            Device("typical-en-bigfont", "en-w360dp-h780dp-xxhdpi", 1.3f),
            Device("large-fa", "fa-w412dp-h915dp-xxxhdpi", 1.0f),
        ).map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val profile = VpnProfile(
        id = "1",
        name = "Germany IKEv2 1",
        protocol = VpnProtocol.IKEV2,
        serverAddress = "tifusi.gilangard.ir",
        countryName = "Germany",
        countryFlagEmoji = "🇩🇪",
        ikev2AuthType = Ikev2AuthType.USERNAME_PASSWORD,
        username = "user",
        password = "x",
    )

    private fun shoot(screen: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(device.qualifiers)
        RuntimeEnvironment.setFontScale(device.fontScale)
        // The slider and the status dots animate forever, so the clock is driven by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TifusiVpnTheme {
                Scaffold(
                    containerColor = TifusiBackground,
                    bottomBar = { BottomBar(current = TifusiDestination.HOME, onSelect = {}) },
                ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        shadowOf(Looper.getMainLooper()).idle()
        // Drawn from the view rather than captureToImage(), which waits for an idle that the
        // screens' endless animations never reach.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val out = File("build/screenshots/${screen}_${device.name}.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun homeConnected() = shoot("home-connected") {
        HomeScreen(
            state = HomeUiState(
                profiles = listOf(profile),
                selectedProfile = profile,
                connectionState = VpnConnectionState.Connected,
                connectedSeconds = 754,
                downloadBytesPerSec = 1_830_000,
                uploadBytesPerSec = 212_000,
                internetChecked = true,
                internetLatencyMs = 184,
                memoryBytes = 61_000_000,
            ),
            onToggleConnection = {}, onOpenRouting = {}, onDismissMessage = {},
        )
    }

    @Test
    fun homeFailed() = shoot("home-failed") {
        HomeScreen(
            state = HomeUiState(
                profiles = listOf(profile),
                selectedProfile = profile,
                connectionState = VpnConnectionState.Failed(VpnFailure.Charon("auth_failed")),
            ),
            onToggleConnection = {}, onOpenRouting = {}, onDismissMessage = {},
        )
    }

    @Test
    fun settings() = shoot("settings") { SettingsScreen(onOpen = {}) }
}
