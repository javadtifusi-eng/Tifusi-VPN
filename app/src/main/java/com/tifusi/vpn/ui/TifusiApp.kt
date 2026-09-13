package com.tifusi.vpn.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.home.HomeScreen
import com.tifusi.vpn.ui.home.HomeViewModel
import com.tifusi.vpn.ui.profile.ProfileScreen
import com.tifusi.vpn.ui.servers.AddServerScreen
import com.tifusi.vpn.ui.servers.AddServerViewModel
import com.tifusi.vpn.ui.servers.ScanQrScreen
import com.tifusi.vpn.ui.servers.ServersScreen
import com.tifusi.vpn.ui.servers.SubscriptionViewModel
import com.tifusi.vpn.ui.services.ServicesScreen
import com.tifusi.vpn.ui.theme.TifusiBackground
import com.tifusi.vpn.ui.theme.TifusiNeonBlue
import com.tifusi.vpn.ui.theme.TifusiSurface
import com.tifusi.vpn.ui.theme.TifusiTextSecondary

private enum class TifusiDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Default.Home),
    SERVERS("servers", R.string.nav_servers, Icons.Default.Dns),
    PROFILE("profile", R.string.nav_profile, Icons.Default.Person),
    SERVICES("services", R.string.nav_services, Icons.Default.Description),
}

private const val ROUTE_ADD_SERVER = "add_server"
private const val ROUTE_SCAN_QR = "scan_qr"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TifusiApp(homeViewModel: HomeViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    // Activity-scoped so the Servers tab can hand a profile to the edit form.
    val addServerViewModel: AddServerViewModel = viewModel()
    val subscriptionViewModel: SubscriptionViewModel = viewModel()
    val subscriptionState by subscriptionViewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = TifusiBackground,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TifusiBackground),
                actions = {
                    IconButton(onClick = { navController.navigateToTab(TifusiDestination.PROFILE) }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = TifusiSurface) {
                TifusiDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.navigateToTab(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TifusiNeonBlue,
                            selectedTextColor = TifusiNeonBlue,
                            unselectedIconColor = TifusiTextSecondary,
                            unselectedTextColor = TifusiTextSecondary,
                            indicatorColor = TifusiSurface,
                        ),
                    )
                }
            }
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
                    onSelectProtocol = homeViewModel::selectProtocol,
                    onServerClick = { navController.navigateToTab(TifusiDestination.SERVERS) },
                    onDismissMessage = homeViewModel::dismissMessage,
                )
            }

            composable(TifusiDestination.SERVERS.route) {
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
                    onCancel = { navController.popBackStack() },
                )
            }

            composable(TifusiDestination.PROFILE.route) { ProfileScreen() }

            composable(TifusiDestination.SERVICES.route) { ServicesScreen() }

            composable(ROUTE_ADD_SERVER) {
                AddServerScreen(
                    viewModel = addServerViewModel,
                    onDone = { navController.popBackStack() },
                )
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
