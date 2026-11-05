// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main: the Settings >
// Network nav destinations and the failure-banner overlay. Extracted out of
// AppNavigation.kt (upstream declares them inline there) because that file
// sits exactly at the architecture-policy line ceiling and may not grow.
package com.openminis.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.openminis.app.navigation.Routes
import com.openminis.app.data.repository.ProviderRepository

/**
 * [port-upstream-1.16] Settings > Network pages (Routing / Tailscale /
 * Proxies), the same three destinations as upstream AppNavigation. The
 * Settings list entry is wired in the next wave; these routes are what the
 * failure banner and deep links navigate to.
 */
fun NavGraphBuilder.networkDestinations(
    navController: NavHostController,
    providerRepository: ProviderRepository,
) {
    composable(Routes.NETWORK) {
        com.openminis.app.ui.settings.NetworkSettingsScreen(
            onBack = { navController.safePopBackStack() },
            providers = providerRepository.instances.map {
                com.openminis.app.ui.settings.NetworkProviderRow(it.id, it.label)
            },
        )
    }
    composable(Routes.NETWORK_TAILSCALE) {
        com.openminis.app.ui.settings.NetworkSettingsScreen(
            onBack = { navController.safePopBackStack() },
            page = com.openminis.app.ui.settings.NetworkPage.TAILSCALE,
        )
    }
    composable(Routes.NETWORK_PROXIES) {
        com.openminis.app.ui.settings.NetworkSettingsScreen(
            onBack = { navController.safePopBackStack() },
            page = com.openminis.app.ui.settings.NetworkPage.PROXIES,
        )
    }
}

/**
 * [port-upstream-1.16] A proxy or Tailscale that keeps failing: a floating
 * banner over every screen (upstream hosts it in MainActivity right after
 * AppNavigation(); here it is emitted with the other overlay hosts).
 */
@Composable
fun NetworkOverlayHost(navController: NavHostController) {
    com.openminis.app.ui.settings.NetworkAlertBannerHost(navController)
}
