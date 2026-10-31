package com.openminis.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.navigation.Routes
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.ui.sandbox.RootfsManagementScreen
import com.openminis.app.ui.settings.MountDetailScreen
import com.openminis.app.ui.settings.MountedFoldersScreen
import com.openminis.app.ui.settings.SessionStorageDetailScreen
import com.openminis.app.ui.settings.SharedFolderDetailScreen
import com.openminis.app.ui.settings.SharedFoldersScreen
import com.openminis.app.ui.settings.StorageManagementScreen

/**
 * [T-android-nav-split] Storage / mount domain destinations, extracted from
 * AppNavigation.kt (same `NavGraphBuilder` extension pattern as
 * [automationRuleEntries]): the storage overview + per-session storage, the
 * rootfs management screens, and the mounted / shared folders pair. Route
 * strings are byte-identical to the pre-split registrations; composable
 * bodies moved as-is.
 *
 * Reads [chatRepository] (DAO for the storage stats) and the
 * application-scoped [mountedFoldersStore] singleton (T219-5 contract: UI
 * CRUD must share state with PRootKernel, never a second instance).
 *
 * The FILE_BROWSER / CHAT_FILES destinations these screens hand off to stay
 * in AppNavigation.kt — they are shared with the chat domain and write the
 * htmlBrowserUrl state isolated there.
 */
internal fun NavGraphBuilder.storageRoutes(
    navController: NavHostController,
    chatRepository: ChatRepository,
    mountedFoldersStore: MountedFoldersStore,
) {
    composable(Routes.STORAGE) {
        StorageManagementScreen(
            chatDao = chatRepository.dao,
            onBack = { navController.safePopBackStack() },
            onRootfsClick = { navController.safeNavigate(Routes.ROOTFS_MANAGEMENT) },
            onSessionClick = { sessionId ->
                navController.safeNavigate(Routes.sessionStorageDetail(sessionId))
            },
        )
    }

    composable(
        route = Routes.SESSION_STORAGE_DETAIL,
        arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
        val context = androidx.compose.ui.platform.LocalContext.current
        SessionStorageDetailScreen(
            sessionId = sessionId,
            chatDao = chatRepository.dao,
            onBack = { navController.safePopBackStack() },
            onBrowseFiles = { _ ->
                // Same resolver as Chat Files. A filed session's workspace
                // lives in minis-workspaces/<folder>, not the private tree
                // this screen used to open directly.
                val rootfs = RootfsManager.getInstance(context.applicationContext)
                val varMinis = java.io.File(rootfs.rootfsDir, "var/minis")
                FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                    rootPath = rootfs.rootfsDir,
                    initialPath = varMinis.takeIf { it.exists() },
                    rootLabel = "Session Files",
                    linuxRootPath = "/",
                    sessionId = sessionId,
                    appContext = context.applicationContext,
                )
                navController.safeNavigate(Routes.FILE_BROWSER)
            },
        )
    }

    composable(Routes.ROOTFS_MANAGEMENT) {
        val context = androidx.compose.ui.platform.LocalContext.current
        RootfsManagementScreen(
            onBack = { navController.safePopBackStack() },
            onBrowseFiles = {
                val rootfs = RootfsManager.getInstance(context.applicationContext)
                FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                    rootPath = rootfs.rootfsDir,
                    rootLabel = "/",
                )
                navController.safeNavigate(Routes.FILE_BROWSER)
            },
            // [T-android-mirror-manual-select] Without this callback the
            // mirror rows fall back to the declaration-site no-op default
            // and MirrorCategoryDetailScreen (manual mirror selection,
            // iOS MirrorCategoryDetailView parity) was unreachable.
            onMirrorCategoryClick = { category ->
                navController.safeNavigate(Routes.mirrorCategoryDetail(category.key))
            },
        )
    }

    composable(
        route = Routes.MIRROR_CATEGORY_DETAIL,
        arguments = listOf(navArgument("categoryKey") { type = NavType.StringType }),
    ) { backStackEntry ->
        val categoryKey = backStackEntry.arguments?.getString("categoryKey") ?: return@composable
        val category = com.openminis.app.ui.sandbox.MirrorCatalog.categoryFromKey(categoryKey)
            ?: return@composable
        com.openminis.app.ui.sandbox.MirrorCategoryDetailScreen(
            category = category,
            onBack = { navController.safePopBackStack() },
        )
    }

    composable(Routes.SHARED_FOLDERS) {
        SharedFoldersScreen(
            onBack = { navController.safePopBackStack() },
            onFolderClick = { folderId ->
                navController.safeNavigate(Routes.sharedFoldersDetail(folderId))
            },
        )
    }

    composable(
        route = Routes.SHARED_FOLDERS_DETAIL,
        arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val folderId = backStackEntry.arguments?.getString("folderId") ?: return@composable
        val ctx = androidx.compose.ui.platform.LocalContext.current
        SharedFolderDetailScreen(
            folderId = folderId,
            onBack = { navController.safePopBackStack() },
            onBrowseFiles = {
                val rootfs = RootfsManager.getInstance(ctx.applicationContext)
                val hostPath = java.io.File(rootfs.rootfsDir, "var/minis/$folderId")
                val label = when (folderId) {
                    "shared" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_shared)
                    "skills" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_skills)
                    else -> folderId
                }
                FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                    rootPath = hostPath,
                    rootLabel = label,
                    // Route reads through PRoot bind mounts so the host
                    // dirs that back /var/minis/{shared,skills}
                    // resolve, matching how chat-files browse works.
                    linuxRootPath = "/var/minis/$folderId",
                    appContext = ctx.applicationContext,
                )
                navController.safeNavigate(Routes.FILE_BROWSER)
            },
        )
    }

    composable(Routes.MOUNTED_FOLDERS) {
        MountedFoldersScreen(
            store = mountedFoldersStore,
            onBack = { navController.safePopBackStack() },
            onMountClick = { mountId ->
                navController.safeNavigate(Routes.mountedFoldersDetail(mountId))
            },
        )
    }

    composable(
        route = Routes.MOUNTED_FOLDERS_DETAIL,
        arguments = listOf(navArgument("mountId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val mountId = backStackEntry.arguments?.getString("mountId") ?: return@composable
        val context = androidx.compose.ui.platform.LocalContext.current
        MountDetailScreen(
            store = mountedFoldersStore,
            mountId = mountId,
            onBack = { navController.safePopBackStack() },
            onBrowseFiles = {
                val entry = mountedFoldersStore.entries.value.firstOrNull { it.id == mountId }
                val hostPath = entry?.resolvedHostPath
                if (hostPath != null) {
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = java.io.File(hostPath),
                        rootLabel = entry.name,
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                } else {
                    // resolvedHostPath null = SAF tree from a non-externalstorage
                    // provider (cloud / Drive). Picker normally rejects these at
                    // add time, so this is a defensive fallback.
                    android.widget.Toast.makeText(
                        context,
                        "Mount path unavailable",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}
