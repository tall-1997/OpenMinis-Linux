package com.openminis.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One resolver for the two "browse a remote's backups" destinations
 * (BACKUP_DESTINATION_BROWSE and RESTORE_BROWSE), which held byte-identical
 * load-and-pop plumbing apart from where their ViewModel was scoped and what
 * they rendered. Extracted from AppNavigation to keep it under its line
 * ceiling; the two call sites live in the same package.
 *
 * [T-android-sessionlist-prefs-io] Resolving a remote is the FIRST plain +
 * Encrypted SharedPreferences load of the process and it used to sit in a
 * `remember {}` in the composition body. Now: sane first-frame default (render
 * nothing), the read on Dispatchers.IO, and a pop when the destination has
 * since been removed.
 */
@Composable
internal fun RcloneRemoteBrowseDestination(
    remoteName: String,
    navController: NavHostController,
    content: @Composable (
        remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
    ) -> Unit,
) {
    val ctx = LocalContext.current
    var remote by remember(remoteName) {
        mutableStateOf<com.openminis.app.backup.remote.RcloneRemoteStore.Remote?>(null)
    }
    var loaded by remember(remoteName) { mutableStateOf(false) }
    LaunchedEffect(remoteName) {
        remote = withContext(Dispatchers.IO) {
            runCatching {
                com.openminis.app.backup.remote.RcloneRemoteStore(ctx.applicationContext)
                    .remote(remoteName)
            }.getOrNull()
        }
        loaded = true
    }
    val resolved = remote
    if (loaded) {
        if (resolved == null) {
            // The destination was removed since the record was written.
            LaunchedEffect(remoteName) { navController.safePopBackStack() }
        } else {
            content(resolved)
        }
    }
}
