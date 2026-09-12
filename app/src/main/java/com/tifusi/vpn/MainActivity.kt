package com.tifusi.vpn

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tifusi.vpn.ui.TifusiApp
import com.tifusi.vpn.ui.home.HomeViewModel
import com.tifusi.vpn.ui.theme.TifusiVpnTheme

// AppCompatActivity rather than ComponentActivity: required for in-app language switching to
// apply on Android versions before 13.
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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

    TifusiApp(homeViewModel = homeViewModel)
}
