package com.tifusi.vpn.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.tifusi.vpn.data.SubscriptionInfo
import com.tifusi.vpn.ui.components.CellBackground
import com.tifusi.vpn.ui.components.InfoCell
import com.tifusi.vpn.ui.components.SectionLabel
import com.tifusi.vpn.ui.components.formatBytes
import com.tifusi.vpn.ui.theme.TifusiCardBorder
import com.tifusi.vpn.ui.theme.TifusiNeonGreen
import com.tifusi.vpn.ui.theme.TifusiTextSecondary
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.tifusi.vpn.BuildConfig
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.theme.TifusiNeonBlue

private val LanguageOptions = listOf(
    "en" to R.string.language_en,
    "fa" to R.string.language_fa,
)

@Composable
fun ProfileScreen(subscription: SubscriptionInfo?) {
    var selectedTag by remember {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-'))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        subscription?.let { SubscriptionCard(it) }

        SectionLabel(stringResource(R.string.language))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CellBackground)
                .border(1.dp, TifusiCardBorder, RoundedCornerShape(14.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LanguageOptions.forEach { (tag, labelRes) ->
                val selected = selectedTag == tag
                Text(
                    stringResource(labelRes),
                    color = if (selected) Color.Black else TifusiTextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color.White else Color.Transparent)
                        .selectable(
                            selected = selected,
                            onClick = {
                                selectedTag = tag
                                // AppCompat persists the choice and recreates the activity in that locale,
                                // which also flips layout direction to RTL for Persian.
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                            },
                        )
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

/** The account at a glance: name, days and data left, as the panel last reported them. */
@Composable
private fun SubscriptionCard(info: SubscriptionInfo) {
    val daysLeft = info.expireEpochSec?.let { ((it - System.currentTimeMillis() / 1000) / 86_400).coerceAtLeast(0) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF16171A), Color(0xFF0C0C0E))))
            .border(1.dp, TifusiCardBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1C1C1F)),
                contentAlignment = Alignment.Center,
            ) {
                Text(info.username?.take(1)?.uppercase() ?: "T", color = TifusiNeonBlue, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(info.username ?: "—", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                info.status?.let { Text(it, color = if (it == "active") TifusiNeonGreen else TifusiTextSecondary, fontSize = 12.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoCell(stringResource(R.string.profile_days_left), daysLeft?.toString() ?: "∞", Modifier.weight(1f))
            InfoCell(
                stringResource(R.string.profile_data_left),
                info.limitBytes?.let { formatBytes((it - info.usedBytes).coerceAtLeast(0)) } ?: "∞",
                Modifier.weight(1f),
            )
        }
        info.limitBytes?.takeIf { it > 0 }?.let { limit ->
            val left = ((limit - info.usedBytes).coerceAtLeast(0).toFloat() / limit).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1C1C1F))) {
                Box(
                    Modifier
                        .fillMaxWidth(left)
                        .height(6.dp)
                        .background(Brush.horizontalGradient(listOf(TifusiNeonGreen, TifusiNeonBlue))),
                )
            }
        }
    }
}

/** Accepts `@name`, `name` or a t.me link, as a panel admin might type it. */
internal fun telegramUsername(raw: String?): String? {
    val name = raw?.trim()
        ?.substringAfterLast('/')
        ?.removePrefix("@")
        ?: return null
    return name.takeIf { Regex("[A-Za-z0-9_]{4,32}").matches(it) }
}

/** Opens the chat in the Telegram app, or in the browser when Telegram is not installed. */
internal fun openTelegram(context: Context, username: String) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$username"))
    try {
        context.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$username")))
    }
}
