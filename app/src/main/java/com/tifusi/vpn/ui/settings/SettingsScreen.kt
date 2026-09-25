package com.tifusi.vpn.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.tifusi.vpn.BuildConfig
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.components.Panel
import com.tifusi.vpn.ui.components.PanelDivider
import com.tifusi.vpn.ui.components.PanelIcon
import com.tifusi.vpn.ui.components.PanelRow
import com.tifusi.vpn.ui.components.PanelValue
import com.tifusi.vpn.ui.components.ScreenTitle
import com.tifusi.vpn.ui.components.SectionCaption
import com.tifusi.vpn.ui.services.AboutContent
import com.tifusi.vpn.ui.theme.AccentCyan
import com.tifusi.vpn.ui.theme.TifusiTextSecondary

enum class SettingsPage { SUBSCRIPTION_INFO, TUNNEL, DNS, ROUTE, SUBSCRIPTION, SPEED }

@Composable
fun SettingsScreen(onOpen: (SettingsPage) -> Unit) {
    var aboutOpen by rememberSaveable { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle(stringResource(R.string.settings), Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ic_logo_mark),
                    contentDescription = stringResource(R.string.app_name),
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(width = 48.dp, height = 26.dp),
                )
                Text("TIFUSI", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 8.5.sp, letterSpacing = 3.sp)
            }
            Text("v${BuildConfig.VERSION_NAME}", color = TifusiTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
        }

        Panel {
            LanguageRow()
            PanelDivider()
            PanelRow(
                label = stringResource(R.string.subscription_info),
                icon = { PanelIcon(Icons.Default.WorkspacePremium) },
                onClick = { onOpen(SettingsPage.SUBSCRIPTION_INFO) },
            )
        }

        SectionCaption(stringResource(R.string.settings_section))
        Panel {
            SettingsLink(Icons.Default.Tv, R.string.tunnel_settings) { onOpen(SettingsPage.TUNNEL) }
            PanelDivider()
            SettingsLink(Icons.Default.Dns, R.string.dns_settings) { onOpen(SettingsPage.DNS) }
            PanelDivider()
            SettingsLink(Icons.Default.CallSplit, R.string.route_settings) { onOpen(SettingsPage.ROUTE) }
            PanelDivider()
            SettingsLink(Icons.Default.Link, R.string.subscription_settings) { onOpen(SettingsPage.SUBSCRIPTION) }
            PanelDivider()
            SettingsLink(Icons.Default.Speed, R.string.speed_test) { onOpen(SettingsPage.SPEED) }
            PanelDivider()
            PanelRow(
                label = stringResource(R.string.nav_services),
                icon = { PanelIcon(Icons.Default.Info) },
                onClick = { aboutOpen = !aboutOpen },
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.rotate(if (aboutOpen) 90f else 0f),
                )
            }
        }

        AnimatedVisibility(visible = aboutOpen) {
            Box(Modifier.padding(top = 10.dp)) { AboutContent() }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsLink(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    PanelRow(label = stringResource(label), icon = { PanelIcon(icon) }, onClick = onClick) {
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AccentCyan)
    }
}

@Composable
private fun LanguageRow() {
    var menu by remember { mutableStateOf(false) }
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
    val options = listOf("en" to stringResource(R.string.language_en), "fa" to stringResource(R.string.language_fa))
    val label = options.firstOrNull { it.first == current }?.second ?: options.first().second
    PanelRow(label = stringResource(R.string.language)) {
        Box {
            PanelValue(label, onClick = { menu = true }, trailingIcon = Icons.Default.UnfoldMore)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                options.forEach { (tag, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            menu = false
                            // AppCompat persists the choice and recreates the activity in that locale,
                            // which also flips layout direction to RTL for Persian.
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                        },
                    )
                }
            }
        }
    }
}

/** A settings page: back arrow, big title, then its content. */
@Composable
fun SubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
        }
        ScreenTitle(title, Modifier.padding(start = 6.dp, bottom = 6.dp))
        content()
        Spacer(Modifier.height(20.dp))
    }
}
