package com.tifusi.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tifusi.vpn.ui.TifusiApp
import com.tifusi.vpn.ui.home.HomeViewModel
import com.tifusi.vpn.ui.theme.TifusiVpnTheme
import com.tifusi.vpn.vpn.VpnConnectionState

// AppCompatActivity rather than ComponentActivity: required for in-app language switching to
// apply on Android versions before 13.
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // English unless the user picked a language in Profile; set before super to avoid a recreate.
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        super.onCreate(savedInstanceState)
        setContent {
            TifusiVpnTheme {
                TifusiRoot()
            }
        }
    }
}

@Composable
private fun TifusiRoot() {
    val homeViewModel: HomeViewModel = viewModel()
    val consentRequest by homeViewModel.consentRequest.collectAsStateWithLifecycle()
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The platform asks for VPN consent once per app install; re-issue the connect afterwards.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            homeViewModel.onConsentGranted()
        } else {
            homeViewModel.onConsentDismissed()
        }
    }

    LaunchedEffect(consentRequest) {
        consentRequest?.let { consentLauncher.launch(it) }
    }

    val connected = uiState.connectionState is VpnConnectionState.Connected
    LaunchedEffect(connected) {
        if (connected) askBatteryExemptionOnce(context)
    }

    TifusiApp(homeViewModel = homeViewModel)
}

/** Asked once, right after the first successful connection, and never again whatever the answer. */
private fun askBatteryExemptionOnce(context: Context) {
    val prefs = context.getSharedPreferences("battery_prompt", Context.MODE_PRIVATE)
    if (prefs.getBoolean("asked", false)) return
    val power = context.getSystemService(PowerManager::class.java) ?: return
    if (power.isIgnoringBatteryOptimizations(context.packageName)) return
    prefs.edit().putBoolean("asked", true).apply()
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    }
}
