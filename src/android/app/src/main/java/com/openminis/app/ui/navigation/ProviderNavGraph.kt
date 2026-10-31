package com.openminis.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.settings.AddAgentLoopGroupsScreen
import com.openminis.app.ui.settings.AddAgentLoopModelsScreen
import com.openminis.app.ui.settings.AddCustomModelScreen
import com.openminis.app.ui.settings.AddModelsToGroupScreen
import com.openminis.app.ui.settings.AddProviderScreen
import com.openminis.app.ui.settings.ModelEntryDetailScreen
import com.openminis.app.ui.settings.ModelGroupDetailScreen
import com.openminis.app.ui.settings.ModelGroupsScreen
import com.openminis.app.ui.settings.ProviderDetailScreen
import com.openminis.app.ui.settings.ProviderListScreen
import com.openminis.app.ui.settings.ShadowVoiceDetailScreen

/**
 * [T-android-nav-split] Provider / model domain destinations, extracted from
 * AppNavigation.kt (same `NavGraphBuilder` extension pattern as
 * [automationRuleEntries]): the provider list + add/detail flows, the shadow
 * voice service, and every model-group / model-picker screen (including the
 * agent-loop pickers). Route strings are byte-identical to the pre-split
 * registrations; composable bodies moved as-is.
 *
 * The whole group reads only [providerRepository] plus the route arguments,
 * so that is the one parameter threaded through.
 */
internal fun NavGraphBuilder.providerRoutes(
    navController: NavHostController,
    providerRepository: ProviderRepository,
) {
    composable(Routes.PROVIDER_LIST) {
        ProviderListScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onAddProvider = { navController.safeNavigate(Routes.ADD_PROVIDER) },
            onProviderClick = { instanceId ->
                navController.safeNavigate(Routes.providerDetail(instanceId))
            },
            onVoiceServiceClick = { instanceId ->
                navController.safeNavigate(Routes.shadowVoiceDetail(instanceId))
            },
        )
    }

    composable(
        route = Routes.SHADOW_VOICE_DETAIL,
        arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
        ShadowVoiceDetailScreen(
            instanceId = instanceId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.ADD_PROVIDER) {
        AddProviderScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onSaved = { navController.safePopBackStack() },
        )
    }

    composable(
        route = Routes.PROVIDER_DETAIL,
        arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
        ProviderDetailScreen(
            instanceId = instanceId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onModelEntryClick = { entryId ->
                navController.safeNavigate(Routes.modelEntryDetail(instanceId, entryId))
            },
            onAddCustomModel = {
                navController.safeNavigate(Routes.addCustomModel(instanceId))
            },
            onVoiceServiceClick = { id ->
                navController.safeNavigate(Routes.shadowVoiceDetail(id))
            },
        )
    }

    composable(Routes.MODEL_GROUPS) {
        ModelGroupsScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onGroupClick = { groupId ->
                navController.safeNavigate(Routes.modelGroupDetail(groupId))
            },
            onAddAgentLoopModels = {
                navController.safeNavigate(Routes.ADD_MODELS_TO_AGENT_LOOP)
            },
            onAddAgentLoopGroups = {
                navController.safeNavigate(Routes.ADD_GROUPS_TO_AGENT_LOOP)
            },
        )
    }

    composable(
        route = Routes.MODEL_GROUP_DETAIL,
        arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
        ModelGroupDetailScreen(
            groupId = groupId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
            onAddModels = {
                navController.safeNavigate(Routes.addModelsToGroup(groupId))
            },
        )
    }

    composable(
        route = Routes.ADD_MODELS_TO_GROUP,
        arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
        AddModelsToGroupScreen(
            groupId = groupId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    // T185: full-screen picker for adding entries to the agent-loop
    // usable set (replaces the T182 ModalBottomSheet so the visual
    // matches AddModelsToGroupScreen — same shared
    // modelEntryPickerItems composable in ui/components/).
    composable(Routes.ADD_MODELS_TO_AGENT_LOOP) {
        AddAgentLoopModelsScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    // T185: companion picker for adding model groups to the agent-loop
    // set. Simpler layout (no per-provider sectioning) but same
    // selection/confirm semantics as the entries picker.
    composable(Routes.ADD_GROUPS_TO_AGENT_LOOP) {
        AddAgentLoopGroupsScreen(
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    // T182: AgentLoopModels is no longer a standalone screen. The
    // picker now lives as the last section inside ModelGroupsScreen
    // (mirrors iOS Views/Providers/ModelGroupsView.swift L77-79's
    // `AgentLoopModelsSection`). Routes.AGENT_LOOP_MODELS is left
    // declared for back-compat with any deep-link string we may
    // have shipped to early users; safePopBackStack lands them on
    // the prior screen if it ever fires.
    composable(Routes.AGENT_LOOP_MODELS) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            navController.safePopBackStack()
        }
    }

    composable(
        route = Routes.MODEL_ENTRY_DETAIL,
        arguments = listOf(
            navArgument("instanceId") { type = NavType.StringType },
            navArgument("entryId") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        // [T-android-model-entry-route-slash-crash] Decode back the
        // %2F-encoded composite ids so ModelEntryDetailScreen can match
        // entry.id == "<instanceId>/<modelId>" (which carries a literal '/')
        // against the repo. Mirrors the encode in Routes.modelEntryDetail.
        //
        // NavType.StringType.parseValue() ALREADY runs Uri.decode on the raw
        // segment, so a second manual decode here was redundant: an entryId
        // carrying a real '%' (a literal, e.g. "gpt-4o%20") came back
        // mangled or dropped. Left as the plain argument read.
        val instanceId = backStackEntry.arguments?.getString("instanceId")
            ?: return@composable
        val entryId = backStackEntry.arguments?.getString("entryId")
            ?: return@composable
        ModelEntryDetailScreen(
            instanceId = instanceId,
            entryId = entryId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(
        route = Routes.ADD_CUSTOM_MODEL,
        arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
        AddCustomModelScreen(
            instanceId = instanceId,
            providerRepository = providerRepository,
            onBack = { navController.safePopBackStack() },
        )
    }
}
