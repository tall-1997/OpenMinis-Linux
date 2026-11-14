package com.openminis.app.ui.git

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.openminis.app.sandbox.FileChangeHub
import kotlinx.coroutines.launch

/**
 * [T-file-hub] Live refresh: FileChangeHub watches the session workspace
 * on the HOST side of the PRoot bind (guest writes land on the same
 * inodes, so agent edits fire inotify), and the panel re-probes git
 * status when working-tree files change. The coalescer already merges
 * inotify bursts and filters .git internals; the timestamp floor caps
 * auto-refresh at one per 2s and a running mutation (busy) skips the
 * slot — GitCli reloads the panel after its own mutations anyway.
 *
 * [T-git-refresh-scope] Only workspace-tree and .git-metadata events
 * trigger the refresh — the hub also watches memory/mounts/sdcard but a
 * memory_write or camera file no more means the repo changed than it
 * means the sky fell.
 *
 * [T-git-refresh-dirty] Changes landing during a busy stretch set the
 * pendingAutoRefresh flag instead of being swallowed; the tail of
 * refresh() drains it.
 */
internal fun GitPanelViewModel.observeWorkspaceChangesFromExt() {
    val app = getApplication<Application>()
    if (!FileChangeHub.watch(sessionId, app)) return
    fileWatchJob = viewModelScope.launch {
        FileChangeHub.eventsFor(sessionId).collect { event ->
            val p = event.guestPath
            val relevant = p.startsWith("/var/minis/workspace/") || p.contains("/.git/")
            if (!relevant) return@collect
            val now = System.currentTimeMillis()
            if (now - lastAutoRefreshMs < GitPanelViewModel.AUTO_REFRESH_FLOOR_MS) return@collect
            if (_state.value.busy) {
                pendingAutoRefresh = true
                return@collect
            }
            lastAutoRefreshMs = now
            refresh()
        }
    }
}
