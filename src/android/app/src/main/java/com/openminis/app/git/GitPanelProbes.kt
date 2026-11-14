package com.openminis.app.git

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Host-native repo discovery + probe for the Git panel, extracted from
 * GitPanelViewModel (architecture-policy ratchet caps new files at 800
 * lines and the panel crossed it — the probe chain is self-contained
 * enough to live here: no Compose state, no Android UI types).
 *
 * All failures are logged under the shared "GitPanel" tag so a "not a
 * repository" paint is diagnosable from logcat alone.
 */
object GitPanelProbes {

    private const val TAG = "GitPanel"

    /**
     * Discover the session workspace's repo on the host side (JGit, no
     * shell) and probe its identity. Returns null when the workspace has
     * no repo, when the probe does not confirm one, or when the probe
     * throws twice in a row (transient JGit/VFS hiccups are retried once
     * before ceding — the sandbox fallback takes over from there).
     */
    suspend fun nativeRepo(context: Context, sessionId: String): Pair<File, GitRepoInfo>? = withContext(Dispatchers.IO) {
        val ws = GitHostRepo.workspaceDir(context.filesDir, sessionId)
        val located = GitHostRepo.locate(ws)
        if (located == null) {
            // Field reports: the panel flips between found/missing across
            // 2s auto-refreshes. Log WHICH workspace was searched so a
            // "not a repository" paint is diagnosable from logcat alone —
            // a session bound to a repo-less project folder is legitimate,
            // a workspace that transiently fails to list is not.
            Log.i(TAG, "nativeRepo: no repo under ws=${ws.path} (session=${sessionId.take(8)})")
            return@withContext null
        }
        val (dir, relative) = located
        // [T-git-panel-probe-retry] A single JGit probe exception used to
        // silently kill the native source for that refresh (RefDatabase
        // hiccups are intermittent on-device); the sandbox fallback then
        // had to rescue it and a sandbox failure painted "not a
        // repository". Retry once after a short backoff before ceding.
        val info = runCatching { GitHostRepo.probe(dir) }.getOrNull()
            ?: run {
                Log.w(TAG, "nativeRepo: probe threw, retrying once (repo=${dir.name})")
                kotlinx.coroutines.delay(120)
                runCatching { GitHostRepo.probe(dir) }.getOrNull()
            }
        if (info?.isRepo != true) {
            Log.i(TAG, "nativeRepo: probe not a repo (repo=${dir.name}, info=${info != null})")
            return@withContext null
        }
        val adopted = relative.substringAfterLast('/')
        dir to info.copy(root = GitHostRepo.guestPath(relative), relativeRoot = adopted)
    }
}
