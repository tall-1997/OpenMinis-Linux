package com.openminis.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.settings.A2aScreen

/**
 * [T-builtin-gateway-native] Registration site for the agent gateways'
 * settings pages.
 *
 * The a2a gateway is app infrastructure — the binary/payload ships in the APK
 * and the service runs on loopback — so it does not belong in a plugin market
 * a user "browses". It gets a first-class destination instead, wired here so
 * AppNavigation.kt (which sits against its own line ceiling) stays lean.
 *
 * The routes themselves live in [Routes] (outside `ui/`) because deep links
 * and MainActivity reference destinations without importing `ui.*`.
 */
internal fun NavGraphBuilder.gatewayRoutes(
    navController: NavHostController,
) {
    // Agent2API: status card, start/stop, and the in-app panel view.
    composable(Routes.A2A) { A2aScreen(onBack = { navController.safePopBackStack() }) }
}
