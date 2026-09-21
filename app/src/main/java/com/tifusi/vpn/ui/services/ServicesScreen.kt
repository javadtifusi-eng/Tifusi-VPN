package com.tifusi.vpn.ui.services

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tifusi.vpn.BuildConfig
import com.tifusi.vpn.R
import com.tifusi.vpn.data.LatestRelease
import com.tifusi.vpn.data.UpdateChecker
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiSurface
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.Ikev2VpnManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface UpdateState {
    object Checking : UpdateState
    object UpToDate : UpdateState
    object Failed : UpdateState
    data class Available(val release: LatestRelease) : UpdateState
}

/** About: the app version with an update check, and what this particular device supports. */
@Composable
fun ServicesScreen() {
    val context = LocalContext.current
    val ikev2Supported = Ikev2VpnManager.isSupported()
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }

    suspend fun checkForUpdate() {
        update = UpdateState.Checking
        val latest = withContext(Dispatchers.IO) { UpdateChecker.latestRelease() }
        update = when {
            latest == null -> UpdateState.Failed
            latest.buildNumber > BuildConfig.VERSION_CODE -> UpdateState.Available(latest)
            else -> UpdateState.UpToDate
        }
    }

    if (UpdateChecker.isEnabled) {
        LaunchedEffect(Unit) { checkForUpdate() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.nav_services), style = MaterialTheme.typography.headlineMedium)

        InfoCard(stringResource(R.string.app_version, BuildConfig.VERSION_NAME))
        if (UpdateChecker.isEnabled) {
            when (val state = update) {
                UpdateState.Checking -> InfoCard(stringResource(R.string.update_checking), color = TifusiTextSecondary)
                UpdateState.UpToDate -> InfoCard(stringResource(R.string.update_up_to_date), color = TifusiNeonGreen)
                UpdateState.Failed -> InfoCard(stringResource(R.string.update_failed), color = TifusiTextSecondary)
                is UpdateState.Available -> {
                    InfoCard(
                        stringResource(R.string.update_available, state.release.versionName),
                        color = TifusiNeonGreen,
                    )
                    // The browser or download manager fetches the APK and offers to install it.
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.release.downloadUrl)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.update_download))
                    }
                }
            }
            if (update !is UpdateState.Checking) {
                OutlinedButton(onClick = { scope.launch { checkForUpdate() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_check))
                }
            }
        }

        InfoCard(stringResource(R.string.android_version, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        InfoCard(
            text = stringResource(
                if (ikev2Supported) R.string.android_version_note_ok else R.string.ikev2_unsupported_version
            ),
            color = if (ikev2Supported) TifusiNeonGreen else MaterialTheme.colorScheme.error,
        )
        InfoCard(stringResource(R.string.core_protocols_supported))
    }
}

@Composable
private fun InfoCard(text: String, color: Color = Color.White) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TifusiSurface)
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
    )
}
