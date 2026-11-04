package com.openminis.app.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.settings.MemoryCenterScreen
import com.openminis.app.ui.settings.MemoryManagementScreen

/**
 * [T-memory-center] Destinations for persistent memory.
 *
 * Extracted from `AppNavigation` for the architecture ratchet (that file is at
 * its line ceiling and may only shrink), and because the two destinations belong
 * together: the hub is the only entry point, the file manager is one step in.
 */
internal fun NavGraphBuilder.memoryNavGraph(
    navController: NavController,
    memoryRepository: MemoryRepository?,
) {
    composable(Routes.MEMORY) {
        MemoryCenterScreen(
            onBack = { navController.safePopBackStack() },
            onOpenFiles = { navController.safeNavigate(Routes.MEMORY_FILES) },
            onOpenKnowledge = { navController.safeNavigate(Routes.KNOWLEDGE) },
        )
    }

    composable(Routes.MEMORY_FILES) {
        if (memoryRepository != null) {
            MemoryManagementScreen(
                memoryRepository = memoryRepository,
                onBack = { navController.safePopBackStack() },
                onFileClick = { fileName, isGlobal ->
                    navController.safeNavigate(Routes.memoryFileEdit(fileName, isGlobal))
                },
                onEvolutionClick = { navController.safeNavigate(Routes.EVOLUTION) },
            )
        }
    }
}
