package com.openminis.app.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-nav-split] Backup domain destinations, extracted from
 * AppNavigation.kt (same `NavGraphBuilder` extension pattern as
 * [automationRuleEntries]): the backup/restore hub, its history detail, the
 * two rclone remote browsers, the restore-server picker and the destinations
 * manager. Route strings are byte-identical to the pre-split registrations;
 * composable bodies moved as-is.
 *
 * Self-contained on purpose: everything here hangs off [navController] and
 * the [Routes.BACKUP] parent entry for ViewModel scoping, so no repository
 * parameters are threaded through.
 */
internal fun NavGraphBuilder.backupRoutes(navController: NavHostController) {
    composable(Routes.BACKUP) {
        com.openminis.app.ui.settings.backup.BackupAndRestoreScreen(
            onBack = { navController.safePopBackStack() },
            onManageDestinations = { navController.safeNavigate(Routes.BACKUP_DESTINATIONS) },
            onChooseRestoreServer = { navController.safeNavigate(Routes.RESTORE_SERVERS) },
            onOpenHistoryRecord = { id ->
                navController.safeNavigate("${Routes.BACKUP_HISTORY_DETAIL}/$id")
            },
            onBrowseDestination = { name ->
                navController.safeNavigate(
                    "${Routes.RESTORE_BROWSE}/" + android.net.Uri.encode(name),
                )
            },
        )
    }

    composable(
        "${Routes.BACKUP_HISTORY_DETAIL}/{recordId}",
        arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString("recordId").orEmpty()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // [T-android-sessionlist-prefs-io] Reading the record used to happen
        // in the composition body: BackupHistory.get() prunes + rewrites
        // records.json on first construction and records() reads the whole
        // file — synchronous disk IO on the main thread inside a Compose
        // frame, the exact stall shape that note measures at 119s. Sane
        // first-frame default (nothing loaded, render nothing) + IO.
        var record by remember(id) {
            mutableStateOf<com.openminis.app.backup.BackupHistory.Record?>(null)
        }
        var recordLoaded by remember(id) { mutableStateOf(false) }
        LaunchedEffect(id) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    com.openminis.app.backup.BackupHistory.get(ctx.applicationContext)
                        .records().firstOrNull { it.id == id }
                }.getOrNull()
            }
            record = loaded
            recordLoaded = true
        }
        val loadedRecord = record
        if (recordLoaded && loadedRecord == null) {
            // The record was removed (or pruned) while this screen was on
            // the stack; there is nothing to show, so leave rather than
            // render an empty shell.
            LaunchedEffect(Unit) { navController.safePopBackStack() }
        } else if (loadedRecord != null) {
            // The screen needs a ViewModel for the delete-with-files path
            // (it talks to rclone).
            //
            // [T-android-backup-delete-cancelled-scope] Scoped to the BACKUP
            // entry, not this one — the same parent-sharing shape the
            // RESTORE_BROWSE destination below uses. A bare viewModel()
            // attached it to the BACKUP_HISTORY_DETAIL store, so the
            // safePopBackStack() that follows the delete call cleared that
            // store and cancelled viewModelScope; the deletion now runs on an
            // application-scoped coroutine (see
            // BackupViewModel.removeHistoryRecordWithFiles) so it survives
            // either way, and sharing the parent additionally means the row
            // disappears from the list the moment it finishes rather than on
            // the next ON_RESUME refresh.
            val vmParent = remember(entry) {
                runCatching { navController.getBackStackEntry(Routes.BACKUP) }
                    .getOrDefault(entry)
            }
            val vm: com.openminis.app.ui.settings.backup.BackupViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel(vmParent)
            com.openminis.app.ui.settings.backup.BackupHistoryDetailScreen(
                record = loadedRecord,
                onBack = { navController.safePopBackStack() },
                onRemove = {
                    // Through the shared VM so the list's observed StateFlow
                    // updates alongside the on-disk record.
                    vm.removeHistoryRecord(id)
                    navController.safePopBackStack()
                },
                onRemoveWithFiles = {
                    vm.removeHistoryRecordWithFiles(id)
                    navController.safePopBackStack()
                },
                onOpenDestination = { name ->
                    navController.safeNavigate(
                        "${Routes.BACKUP_DESTINATION_BROWSE}/" +
                            android.net.Uri.encode(name),
                    )
                },
            )
        }
    }

    composable(
        "${Routes.BACKUP_DESTINATION_BROWSE}/{remoteName}",
        arguments = listOf(navArgument("remoteName") { type = NavType.StringType }),
    ) { entry ->
        val name = entry.arguments?.getString("remoteName").orEmpty()
        RcloneRemoteBrowseDestination(name, navController) { remote ->
            // Scoped to this destination: read-only browsing of one remote.
            val vm: com.openminis.app.ui.settings.backup.BackupViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel(entry)
            com.openminis.app.ui.settings.backup.BackupDestinationBrowseScreen(
                remote = remote,
                vm = vm,
                onBack = { navController.safePopBackStack() },
            )
        }
    }

    // [T-android-restore-server-list] Reached from the restore tab's
    // "Choose from Server…" — always, configured or not. Picking a row
    // (or finishing an add) continues to that server's package browser.
    composable(Routes.RESTORE_SERVERS) {
        com.openminis.app.ui.settings.backup.RestoreServersScreen(
            onBack = { navController.safePopBackStack() },
            onPickServer = { name ->
                navController.safeNavigate(
                    "${Routes.RESTORE_BROWSE}/" + android.net.Uri.encode(name),
                )
            },
        )
    }

    composable(
        "${Routes.RESTORE_BROWSE}/{remoteName}",
        arguments = listOf(navArgument("remoteName") { type = NavType.StringType }),
    ) { entry ->
        val name = entry.arguments?.getString("remoteName").orEmpty()
        RcloneRemoteBrowseDestination(name, navController) { remote ->
            // Scoped to the BACKUP entry so the browser and the restore
            // screen share one ViewModel — the picked package has to be
            // visible to the screen that restores it.
            val parent = remember(entry) { navController.getBackStackEntry(Routes.BACKUP) }
            val vm: com.openminis.app.ui.settings.backup.BackupViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel(parent)
            com.openminis.app.ui.settings.backup.RestoreBrowseScreen(
                remote = remote,
                vm = vm,
                onBack = { navController.safePopBackStack() },
                // [T-android-restore-picked-lands-home] Pop to BACKUP, not
                // one level up.
                //
                // This screen is reachable at two different depths:
                // BACKUP → RESTORE_BROWSE when a destination is tapped on
                // the restore tab, but BACKUP → RESTORE_SERVERS →
                // RESTORE_BROWSE when the user came through "Choose from
                // Server…". A single pop is right for the first and one
                // short for the second, so after a multi-GB download and
                // extract the user landed back on the server list — the
                // step BEFORE the one they just completed — with no sign
                // of the package they had waited for. It is on the Backup
                // screen, one more pop away, that the opened package and
                // its category checkboxes actually render.
                //
                // Popping to a named destination is depth-independent, so
                // a future third entry point cannot reintroduce this.
                onPicked = {
                    navController.safePopBackStack(
                        route = Routes.BACKUP,
                        inclusive = false,
                    )
                },
            )
        }
    }

    composable(Routes.BACKUP_DESTINATIONS) {
        com.openminis.app.ui.settings.backup.RcloneDestinationsScreen(
            onBack = { navController.safePopBackStack() },
        )
    }
}
