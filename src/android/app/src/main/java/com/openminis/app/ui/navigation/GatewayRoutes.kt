package com.openminis.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.settings.A2aScreen
import com.openminis.app.ui.settings.LocalGatewayScreen

/**
 * [T-builtin-gateway-native] Registration site for the agent gateways'
 * settings pages.
 *
 * Both gateways are app infrastructure — the binary/payload ships in the APK
 * and the service runs on loopback — so neither belongs in a plugin market a
 * user "browses". Each gets a first-class destination instead, and this
 * function is the single place that wires them: a new gateway is a catalog
 * descriptor plus one line here, not another dozen lines in
 * AppNavigation.kt, which sits against its own line ceiling.
 *
 * The routes themselves live in [Routes] (outside `ui/`) because deep links
 * and MainActivity reference destinations without importing `ui.*`.
 */
internal fun NavGraphBuilder.gatewayRoutes(
    navController: NavHostController,
    providerRepository: ProviderRepository?,
) {
    // Agent2API: status card, start/stop, and the in-app panel view.
    composable(Routes.A2A) { A2aScreen(onBack = { navController.safePopBackStack() }) }

    // Local model gateway: what it is, install state, loopback address, switch.
    composable(Routes.LOCAL_GATEWAY) {
        LocalGatewayScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }
}
