package com.openminis.app.ui.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.MemoryCenterStats
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
        val context = LocalContext.current
        MemoryCenterScreen(
            onBack = { navController.safePopBackStack() },
            onOpenFiles = { navController.safeNavigate(Routes.MEMORY_FILES) },
            onOpenKnowledge = { navController.safeNavigate(Routes.KNOWLEDGE) },
            // [T-memory-center-stats] Note count comes from Room; the rest is a
            // bounded filesystem walk. Wrapped so a half-initialised AppGraph
            // shows "no numbers" instead of crashing the hub.
            loadStats = {
                val notes = runCatching {
                    (context.applicationContext as? MinisApp)?.knowledgeNoteRepository?.listActive()?.size
                }.getOrNull() ?: 0
                MemoryCenterStats.collect(context.filesDir, notes)
            },
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
