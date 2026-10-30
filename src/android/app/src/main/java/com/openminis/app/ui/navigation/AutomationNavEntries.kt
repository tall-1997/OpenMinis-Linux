package com.openminis.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.navigation.Routes
import com.openminis.app.automation.ui.AutomationRuleEditScreen
import com.openminis.app.automation.ui.AutomationRuleRunsScreen

/**
 * [T-android-automation-rules] Automation rule destinations: the rule editor
 * (ruleId null = create) and per-rule run records. Extracted from
 * AppNavigation to keep it under its line ceiling; the trigger wiring lives
 * in the scheduled-tasks hub entry.
 */
internal const val AUTOMATION_RULE_EDIT = "automation_rules/edit?ruleId={ruleId}"
internal const val AUTOMATION_RULE_RUNS = "automation_rules/runs/{ruleId}"

internal fun automationRuleEdit(ruleId: String? = null): String =
    if (ruleId == null) "automation_rules/edit" else "automation_rules/edit?ruleId=$ruleId"

internal fun NavGraphBuilder.automationRuleEntries(navController: NavHostController) {
    // Rule editor (ruleId null = create).
    composable(
        route = AUTOMATION_RULE_EDIT,
        arguments = listOf(
            navArgument("ruleId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { backStackEntry ->
        val ruleId = backStackEntry.arguments?.getString("ruleId")
        AutomationRuleEditScreen(
            ruleId = ruleId,
            onBack = { navController.safePopBackStack() },
        )
    }
    // Per-rule run records.
    composable(
        route = AUTOMATION_RULE_RUNS,
        arguments = listOf(navArgument("ruleId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val ruleId = backStackEntry.arguments?.getString("ruleId") ?: return@composable
        AutomationRuleRunsScreen(
            ruleId = ruleId,
            onBack = { navController.safePopBackStack() },
            onOpenSession = { sessionId ->
                navController.safeNavigate(Routes.chat(sessionId))
            },
        )
    }
}
