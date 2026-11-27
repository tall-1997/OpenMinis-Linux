package com.openminis.app.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.navigation.Routes

/**
 * [T-lemon-integration] MCP-related nav destinations, extracted from
 * `AppNavigation.kt` when the lemon catalog panel pushed that file past its
 * architecture ceiling. Bodies are unchanged; only the enclosing file moved.
 *
 * `AppNavigation.kt` is the single biggest routing file in the app, and every
 * feature that adds a screen makes it worse. Pulling a self-contained cluster
 * out is the only move the ratchet allows — it never raises a ceiling.
 */
internal fun NavGraphBuilder.mcpNavDestinations(
    navController: NavController,
    mcpRepository: com.openminis.app.data.repository.MCPRepository?,
    envVarRepository: com.openminis.app.data.repository.EnvVarRepository?,
) {
    mcpIntegrationsDestination(navController, mcpRepository, envVarRepository)
    lemonPanelDestination(navController)
    mcpToolsDestination(navController, mcpRepository)
}

private fun NavGraphBuilder.mcpIntegrationsDestination(
    navController: NavController,
    mcpRepository: com.openminis.app.data.repository.MCPRepository?,
    envVarRepository: com.openminis.app.data.repository.EnvVarRepository?,
) {
    composable(Routes.MCP) {
        if (mcpRepository != null) {
            com.openminis.app.ui.settings.MCPIntegrationsScreen(
                mcpRepository = mcpRepository,
                onBack = { navController.safePopBackStack() },
                envVarRepository = envVarRepository,
                onOpenTools = { server -> navController.safeNavigate(Routes.mcpTools(server.id)) },
                onOpenLemonPanel = { port -> openLemonPanel(navController, port) },
            )
        }
    }
}

private fun NavGraphBuilder.mcpToolsDestination(
    navController: NavController,
    mcpRepository: com.openminis.app.data.repository.MCPRepository?,
) {
    composable(
        Routes.MCP_TOOLS,
        arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
    ) { entry ->
        val repo = mcpRepository
        // StringType already Uri.decode()s the segment (see
        // [T-android-double-decode-percent-crash]), so a second manual
        // Uri.decode here only mangled server ids carrying a real '%'.
        val serverId = entry.arguments?.getString("serverId").orEmpty()
        if (repo == null) {
            androidx.compose.runtime.LaunchedEffect(serverId) { navController.safePopBackStack() }
        } else {
            com.openminis.app.ui.settings.MCPToolsScreen(
                mcpRepository = repo,
                serverId = serverId,
                onBack = { navController.safePopBackStack() },
                onManage = { navController.safePopBackStack() },
            )
        }
    }
}

/**
 * Bundled tool catalog panel (`assets/lemon/panel.html`).
 *
 * The port travels as a nav argument rather than being read from config here,
 * so the page always targets the server instance that actually started (the
 * built-in server can shift ports when the preferred one is taken).
 */
private fun NavGraphBuilder.lemonPanelDestination(navController: NavController) {
    composable(
        Routes.LEMON_PANEL,
        arguments = listOf(navArgument("port") { type = NavType.StringType }),
    ) { entry ->
        val port = entry.arguments?.getString("port")?.toIntOrNull() ?: 8765
        com.openminis.app.ui.lemon.LemonPanelScreen(
            port = port,
            onBack = { navController.safePopBackStack() },
        )
    }
}

/** Opens the panel for [port]; the settings row calls this. */
internal fun openLemonPanel(navController: NavController, port: Int) {
    navController.safeNavigate(Routes.lemonPanel(port))
}
