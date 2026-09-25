package com.tifusi.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.home.HomeScreen
import com.tifusi.vpn.ui.home.HomeViewModel
import com.tifusi.vpn.ui.servers.AddServerScreen
import com.tifusi.vpn.ui.servers.AddServerViewModel
import com.tifusi.vpn.ui.servers.ScanQrScreen
import com.tifusi.vpn.ui.servers.ServersScreen
import com.tifusi.vpn.ui.servers.SubscriptionViewModel
import com.tifusi.vpn.ui.settings.DnsSettingsPage
import com.tifusi.vpn.ui.settings.RouteSettingsPage
import com.tifusi.vpn.ui.settings.SettingsPage
import com.tifusi.vpn.ui.settings.SettingsScreen
import com.tifusi.vpn.ui.settings.SpeedTestPage
import com.tifusi.vpn.ui.settings.SubscriptionInfoPage
import com.tifusi.vpn.ui.settings.SubscriptionSettingsPage
import com.tifusi.vpn.ui.settings.TunnelSettingsPage
import com.tifusi.vpn.ui.theme.AccentCyan
import com.tifusi.vpn.ui.theme.TifusiBackground
import com.tifusi.vpn.ui.theme.TifusiTextSecondary

internal enum class TifusiDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Outlined.Home),
    CONFIGS("configs", R.string.nav_configs, Icons.AutoMirrored.Filled.FormatListBulleted),
    SETTINGS("settings", R.string.settings, Icons.Outlined.Settings),
}

private const val ROUTE_ADD_SERVER = "add_server"
private const val ROUTE_SCAN_QR = "scan_qr"
private fun pageRoute(page: SettingsPage) = "settings/${page.name.lowercase()}"

@Composable
fun TifusiApp(homeViewModel: HomeViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    // Activity-scoped so the Configs tab can hand a profile to the edit form.
    val addServerViewModel: AddServerViewModel = viewModel()
    val subscriptionViewModel: SubscriptionViewModel = viewModel()
    val subscriptionState by subscriptionViewModel.state.collectAsStateWithLifecycle()
    val back: () -> Unit = { navController.popBackStack() }

    Scaffold(
        containerColor = TifusiBackground,
        bottomBar = {
            BottomBar(
                current = TifusiDestination.entries.firstOrNull { currentRoute == it.route || currentRoute?.startsWith(it.route + "/") == true },
                onSelect = { navController.navigateToTab(it) },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TifusiDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TifusiDestination.HOME.route) {
                HomeScreen(
                    state = homeState,
                    onToggleConnection = homeViewModel::toggleConnection,
                    onOpenRouting = { navController.navigate(pageRoute(SettingsPage.ROUTE)) },
                    onDismissMessage = homeViewModel::dismissMessage,
                )
            }

            composable(TifusiDestination.CONFIGS.route) {
                ServersScreen(
                    profiles = homeState.profiles,
                    selectedProfileId = homeState.selectedProfile?.id,
                    onSelectProfile = homeViewModel::selectProfile,
                    onEditProfile = { profile ->
                        addServerViewModel.loadDraft(profile, isExisting = true)
                        navController.navigate(ROUTE_ADD_SERVER)
                    },
                    onDeleteProfile = homeViewModel::deleteProfile,
                    onAddManually = {
                        addServerViewModel.startNew(homeState.selectedProtocol)
                        navController.navigate(ROUTE_ADD_SERVER)
                    },
                    subscription = subscriptionState,
                    onSubscriptionLinkChange = subscriptionViewModel::onLinkChange,
                    onImportSubscription = subscriptionViewModel::importLink,
                    onRefreshSubscription = subscriptionViewModel::refresh,
                    onRemoveSubscription = subscriptionViewModel::remove,
                    onScanQr = { navController.navigate(ROUTE_SCAN_QR) },
                )
            }

            composable(ROUTE_SCAN_QR) {
                ScanQrScreen(
                    onSubscriptionScanned = { link ->
                        // Same path as typing the link and tapping "Get servers".
                        subscriptionViewModel.onLinkChange(link)
                        subscriptionViewModel.importLink()
                        navController.popBackStack()
                    },
                    onCancel = back,
                )
            }

            composable(TifusiDestination.SETTINGS.route) {
                SettingsScreen(onOpen = { navController.navigate(pageRoute(it)) })
            }
            composable(pageRoute(SettingsPage.SUBSCRIPTION_INFO)) { SubscriptionInfoPage(homeState.subscriptionInfo, back) }
            composable(pageRoute(SettingsPage.TUNNEL)) { TunnelSettingsPage(back) }
            composable(pageRoute(SettingsPage.DNS)) { DnsSettingsPage(back) }
            composable(pageRoute(SettingsPage.ROUTE)) { RouteSettingsPage(back) }
            composable(pageRoute(SettingsPage.SPEED)) { SpeedTestPage(back) }
            composable(pageRoute(SettingsPage.SUBSCRIPTION)) {
                SubscriptionSettingsPage(
                    state = subscriptionState,
                    onLinkChange = subscriptionViewModel::onLinkChange,
                    onImport = subscriptionViewModel::importLink,
                    onRefresh = subscriptionViewModel::refresh,
                    onScanQr = { navController.navigate(ROUTE_SCAN_QR) },
                    onBack = back,
                )
            }

            composable(ROUTE_ADD_SERVER) {
                AddServerScreen(viewModel = addServerViewModel, onDone = back)
            }
        }
    }
}

@Composable
internal fun BottomBar(current: TifusiDestination?, onSelect: (TifusiDestination) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TifusiBackground)
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        TifusiDestination.entries.forEach { destination ->
            val color = if (destination == current) AccentCyan else TifusiTextSecondary
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(destination) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(destination.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Text(stringResource(destination.labelRes), color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

private fun NavHostController.navigateToTab(destination: TifusiDestination) {
    navigate(destination.route) {
        // Keep a single copy of each tab on the back stack.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
