package com.openminis.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.settings.LogDetailScreen
import com.openminis.app.ui.settings.LogManagementScreen
import com.openminis.app.ui.settings.LogcatPanelScreen

/**
 * [T-logcat-panel] The three log destinations, extracted from AppNavigation.kt
 * (same `NavGraphBuilder` extension pattern as [backupRoutes] /
 * [providerRoutes]).
 *
 * The split is what keeps the new live-panel route inside AppNavigation's
 * architecture ceiling without raising it: the whole log cluster reads nothing
 * but the route arguments, so [navController] is the one parameter threaded
 * through, and the bodies moved as-is. Route strings are unchanged.
 *
 * [Routes.logDetail] carries the file name in its path, so the detail screen
 * keeps its `navArgument("fileName")` declaration exactly as before.
 */
internal fun NavGraphBuilder.logsRoutes(navController: NavHostController) {
    composable(Routes.LOGS) {
        LogManagementScreen(
            onBack = { navController.safePopBackStack() },
            onLogFileClick = { fileName ->
                navController.safeNavigate(Routes.logDetail(fileName))
            },
            onOpenLogcat = { navController.safeNavigate(Routes.LOGCAT_PANEL) },
        )
    }

    composable(Routes.LOGCAT_PANEL) {
        LogcatPanelScreen(onBack = { navController.safePopBackStack() })
    }

    composable(
        route = Routes.LOG_DETAIL,
        arguments = listOf(navArgument("fileName") { type = NavType.StringType }),
    ) { backStackEntry ->
        val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
        LogDetailScreen(
            fileName = fileName,
            onBack = { navController.safePopBackStack() },
        )
    }
}
