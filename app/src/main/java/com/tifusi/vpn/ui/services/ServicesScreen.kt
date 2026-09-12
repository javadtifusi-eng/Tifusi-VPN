package com.tifusi.vpn.ui.services

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiSurface
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.Ikev2VpnManager

/** Shows what this particular device supports, so a user on an older Samsung knows why. */
@Composable
fun ServicesScreen() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "-"
    }
    val ikev2Supported = Ikev2VpnManager.isSupported()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.nav_services), style = MaterialTheme.typography.headlineMedium)

        InfoCard(stringResource(R.string.android_version, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        InfoCard(
            text = stringResource(
                if (ikev2Supported) R.string.android_version_note_ok else R.string.ikev2_unsupported_version
            ),
            color = if (ikev2Supported) TifusiNeonGreen else MaterialTheme.colorScheme.error,
        )
        InfoCard(stringResource(R.string.wireguard_supported))
        InfoCard(stringResource(R.string.legacy_supported))
        InfoCard(stringResource(R.string.app_version, versionName), color = TifusiTextSecondary)
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
