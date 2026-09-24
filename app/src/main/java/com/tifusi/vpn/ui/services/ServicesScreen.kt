package com.tifusi.vpn.ui.services

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tifusi.vpn.ui.profile.openTelegram
import com.tifusi.vpn.ui.profile.telegramUsername
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiNeonBlueDeep
import android.content.Intent
import android.net.Uri
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
    val support = telegramUsername(BuildConfig.SUPPORT_TELEGRAM)

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (UpdateChecker.isEnabled) {
            BlackCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (text, dot) = when (val state = update) {
                        UpdateState.Checking -> stringResource(R.string.update_checking) to TifusiTextSecondary
                        UpdateState.UpToDate -> stringResource(R.string.update_up_to_date) to TifusiNeonGreen
                        UpdateState.Failed -> stringResource(R.string.update_failed) to TifusiTextSecondary
                        is UpdateState.Available -> stringResource(R.string.update_available, state.release.versionName) to TifusiNeonBlue
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                    Text(text, fontSize = 13.5.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    when (val state = update) {
                        UpdateState.Checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = TifusiNeonBlue)
                        // The browser or download manager fetches the APK and offers to install it.
                        is UpdateState.Available -> PillButton(stringResource(R.string.update_download), filled = true) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.release.downloadUrl))) }
                        }
                        else -> PillButton(stringResource(R.string.update_check), filled = false) { scope.launch { checkForUpdate() } }
                    }
                }
            }
        }

        BlackCard {
            Text(
                stringResource(if (ikev2Supported) R.string.android_version_note_ok else R.string.ikev2_unsupported_version),
                color = if (ikev2Supported) TifusiNeonGreen else MaterialTheme.colorScheme.error,
                fontSize = 12.5.sp,
            )
        }

        BlackCard {
            Text(stringResource(R.string.about_protocols), color = TifusiTextSecondary, fontSize = 12.5.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("REALITY", "VLESS", "HY2", "IKEv2").forEach {
                    Text(
                        it,
                        color = TifusiNeonBlue,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF16171A))
                            .border(1.dp, TifusiCardBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Both set per build in gradle.properties (tifusi.supportTelegram, tifusi.supportEmail); each
        // tile is hidden when its value is empty, and the e-mail address itself is never shown.
        support?.let { username ->
            ContactTile(Icons.Default.HeadsetMic, stringResource(R.string.contact_us)) { openTelegram(context, username) }
        }
        BuildConfig.SUPPORT_EMAIL.takeIf { it.contains('@') }?.let { email ->
            ContactTile(Icons.Default.Email, stringResource(R.string.contact_email)) {
                runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))) }
            }
        }
    }
}

@Composable
private fun ContactTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TifusiSurface)
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
        }
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 10.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TifusiTextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun BlackCard(
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF16171A), Color(0xFF0C0C0E))))
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

@Composable
private fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (filled) Color.White else Color.Black,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) Brush.linearGradient(listOf(TifusiNeonBlue, TifusiNeonBlueDeep)) else Brush.linearGradient(listOf(Color.White, Color.White)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
