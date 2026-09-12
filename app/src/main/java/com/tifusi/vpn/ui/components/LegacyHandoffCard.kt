package com.tifusi.vpn.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiSurface
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import com.tifusi.vpn.vpn.LegacyFieldKey
import com.tifusi.vpn.vpn.LegacyVpnLauncher
import com.tifusi.vpn.vpn.VpnProfile

/** Tap-to-copy field list plus a Settings shortcut, for protocols the app cannot drive itself. */
@Composable
fun LegacyHandoffCard(
    profile: VpnProfile,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val copiedLabel = stringResource(R.string.copied_to_clipboard)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(TifusiSurface)
            .border(1.dp, TifusiNeonBlue, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.legacy_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.legacy_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = TifusiTextSecondary,
        )

        LegacyVpnLauncher.fieldsFor(profile).forEach { field ->
            val label = stringResource(field.key.labelRes())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, TifusiCardBorder, RoundedCornerShape(12.dp))
                    .clickable {
                        LegacyVpnLauncher.copyField(context, label, field.value)
                        Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = TifusiTextSecondary)
                    // Secrets are copyable but never rendered in clear on screen.
                    val shown = if (field.key.isSecret()) "••••••••" else field.value
                    Text(shown, style = MaterialTheme.typography.bodyLarge)
                }
                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TifusiNeonBlue)
            }
        }

        Button(
            onClick = { LegacyVpnLauncher.openVpnSettings(context) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TifusiNeonBlue),
        ) {
            Text(stringResource(R.string.open_vpn_settings))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.action_dismiss))
        }
    }
}

private fun LegacyFieldKey.isSecret(): Boolean =
    this == LegacyFieldKey.PASSWORD || this == LegacyFieldKey.IPSEC_PRESHARED_KEY

private fun LegacyFieldKey.labelRes(): Int = when (this) {
    LegacyFieldKey.NAME -> R.string.field_name
    LegacyFieldKey.TYPE -> R.string.select_protocol
    LegacyFieldKey.SERVER -> R.string.field_server_address
    LegacyFieldKey.IPSEC_PRESHARED_KEY -> R.string.field_ipsec_preshared_key
    LegacyFieldKey.USERNAME -> R.string.field_username
    LegacyFieldKey.PASSWORD -> R.string.field_password
}
