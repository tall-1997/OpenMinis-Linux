package com.openminis.app.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.settings.MemoryManagementScreen

/**
 * [T-memory-center] The memory destination.
 *
 * "Memory" opens the diary/file page directly. An intermediate hub with two rows
 * and a paragraph was tried first and read as an empty menu, so the knowledge
 * workbench moved to the bottom of this page instead — one landing page, one
 * secondary destination, no peer entry in the drawer.
 *
 * Lives here rather than in `AppNavigation` because that file is at its line
 * ceiling and may only shrink.
 */
internal fun NavGraphBuilder.memoryNavGraph(
    navController: NavController,
    memoryRepository: MemoryRepository?,
) {
    composable(Routes.MEMORY) {
        if (memoryRepository != null) {
            MemoryManagementScreen(
                memoryRepository = memoryRepository,
                onBack = { navController.safePopBackStack() },
                onFileClick = { fileName, isGlobal ->
                    navController.safeNavigate(Routes.memoryFileEdit(fileName, isGlobal))
                },
                onEvolutionClick = { navController.safeNavigate(Routes.EVOLUTION) },
                onOpenKnowledge = { navController.safeNavigate(Routes.KNOWLEDGE) },
            )
        }
    }
}
