package com.openminis.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.knowledge.KnowledgeNoteEditorScreen
import com.openminis.app.ui.knowledge.KnowledgeWorkbenchScreen
import com.openminis.app.ui.knowledge.KnowledgeWorkbenchViewModel

/**
 * [T-m8-knowledge-workbench] Navigation entries for the knowledge workbench:
 * notes list (tabs) and the note editor. Kept out of AppNavigation.kt so the
 * architecture line baseline never rises for new screens.
 */
internal fun NavGraphBuilder.knowledgeNavGraph(
    navController: NavHostController,
    viewModel: KnowledgeWorkbenchViewModel,
) {
    // [T-m8-knowledge-workbench] Single VM shared by the list and the editor
    // so editor edits flow back into the list without a second reload. The VM
    // is remembered by AppNavigation: a NavHost builder is NOT a @Composable
    // context, so remember() cannot live inside a NavGraphBuilder extension.
    composable(Routes.KNOWLEDGE) {
        KnowledgeWorkbenchScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            onOpenNote = { noteId -> navController.navigate(Routes.knowledgeEditor(noteId)) },
        )
    }
    composable(
        route = Routes.KNOWLEDGE_EDITOR,
        arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
    ) { entry ->
        KnowledgeNoteEditorScreen(
            noteId = entry.arguments?.getString("noteId")?.takeIf { it.isNotEmpty() },
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
        )
    }
}
