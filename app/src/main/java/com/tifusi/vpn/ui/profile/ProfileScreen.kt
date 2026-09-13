package com.tifusi.vpn.ui.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.tifusi.vpn.BuildConfig
import com.tifusi.vpn.R
import com.tifusi.vpn.data.VpnProfileRepository
import com.tifusi.vpn.ui.theme.TifusiNeonBlue

private val LanguageOptions = listOf(
    "en" to R.string.language_en,
    "fa" to R.string.language_fa,
)

@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    val repository = remember { VpnProfileRepository(context) }
    val panelTelegram by repository.supportTelegram.collectAsState(initial = null)
    val telegram = telegramUsername(panelTelegram) ?: telegramUsername(BuildConfig.SUPPORT_TELEGRAM)
    var selectedTag by remember {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-'))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.language), style = MaterialTheme.typography.headlineMedium)

        LanguageOptions.forEach { (tag, labelRes) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedTag == tag,
                        onClick = {
                            selectedTag = tag
                            // AppCompat persists the choice and recreates the activity in that locale,
                            // which also flips layout direction to RTL for Persian.
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedTag == tag,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = TifusiNeonBlue),
                )
                Text(stringResource(labelRes), modifier = Modifier.padding(start = 12.dp))
            }
        }

        // The imported panel's own support comes first, so a public build of the app sends each
        // panel's users to that panel's owner; the build's default covers users with no panel yet.
        telegram?.let { username ->
            Text(
                stringResource(R.string.contact_us),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedButton(onClick = { openTelegram(context, username) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.contact_telegram, "@$username"))
            }
        }
    }
}

/** Accepts `@name`, `name` or a t.me link, as a panel admin might type it. */
private fun telegramUsername(raw: String?): String? {
    val name = raw?.trim()
        ?.substringAfterLast('/')
        ?.removePrefix("@")
        ?: return null
    return name.takeIf { Regex("[A-Za-z0-9_]{4,32}").matches(it) }
}

/** Opens the chat in the Telegram app, or in the browser when Telegram is not installed. */
private fun openTelegram(context: Context, username: String) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$username"))
    try {
        context.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$username")))
    }
}
