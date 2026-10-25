package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryDiaryMigrator
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * One-shot upgrade from the pre-1.36.13 global workspace:
 *
 *  - copy `minis-global/memory/YYYY-MM-DD.md` into each session's memory dir
 *  - file ungrouped sessions into the default project folder so they share one
 *    workspace, MOVING their private files across
 *  - seed that project from leftover `minis-global/{workspace,attachments,...}`
 *
 * Every ungrouped session is filed, including the ones that already have
 * per-session files. The shipped 1.36.13/1.36.14 build skipped those on the
 * theory that "sessions with isolated files stay unfiled so their files keep
 * being mounted" — which meant the sessions that had actually used the sandbox
 * were exactly the ones left behind. On a device with real history that is
 * nearly all of them, so the default workspace came up almost empty while
 * every session kept its own private copy. Filing them now moves the files
 * across with [WorkspaceMover], so nothing is orphaned.
 *
 * This one-shot pass only covers sessions that were NEVER filed. Sessions that
 * 1.36.14 filed with the old rule carry a `folder_id` but still hold their
 * files privately (a partial filing — the DB row was written, the move was
 * not); those are converged by [ChatRepository.reconcileWorkspaceFiles], which
 * runs on every launch and needs no marker key.
 */
object LegacyWorkspaceMigrator {
    private const val PREFS = "workspace_layout"
    private const val KEY_DONE = "legacy_v1_done"
    /**
     * Bumped when the filing rule changes. 1.36.13/1.36.14 shipped a run that
     * filed only file-less sessions; a device that already completed that run
     * needs a second pass over the sessions it skipped.
     */
    private const val KEY_DONE_V2 = "legacy_v2_filed"

    fun sessionHasIsolatedFiles(filesDir: File, sessionId: String): Boolean {
        val root = SessionWorkspace.base(filesDir, sessionId)
        return SessionWorkspace.SHARED_SUBDIRS.any { sub ->
            File(root, sub).walkTopDown().any { it.isFile() }
        }
    }

    fun seedProjectFromGlobal(filesDir: File, folderId: String) {
        SessionWorkspace.ensureProjectDirs(filesDir, folderId)
        val projectRoot = SessionWorkspace.projectBase(filesDir, folderId)
        for (sub in SessionWorkspace.SHARED_SUBDIRS) {
            val src = File(filesDir, "${SessionWorkspace.GLOBAL_DIR}/$sub")
            val dst = File(projectRoot, sub)
            if (!src.isDirectory()) continue
            val destEmpty = dst.listFiles().isNullOrEmpty()
            if (!destEmpty) continue
            src.copyRecursively(dst, overwrite = false)
        }
    }

    suspend fun run(context: Context, repo: ChatRepository) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Two gates, two jobs. The v1 gate is the original one-shot; the v2
        // gate exists because v1's filing rule was wrong and a device that
        // already ran it must be revisited. A fresh install only ever runs v2.
        if (prefs.getBoolean(KEY_DONE_V2, false)) return
        val filesDir = context.filesDir
        val globalMemory = File(filesDir, "${SessionWorkspace.GLOBAL_DIR}/memory")
        val sessions = repo.listSessions()
        for (s in sessions) {
            MemoryDiaryMigrator.copyInto(
                SessionWorkspace.memoryDir(filesDir, s.id),
                globalMemory,
            )
        }
        val defaultName = context.getString(R.string.group_default_workspace)
        val folder = repo.ensureDefaultWorkspace(defaultName)
        var filed = 0
        var movedBytes = 0L
        var blocked = false
        for (s in sessions) {
            if (s.folderId != null) continue
            // File first, move second. The DB row is the cheap, reversible
            // part; if the move throws we still have the record that this
            // session was meant to be in the project, and the next launch
            // retries the move (it is idempotent).
            if (!repo.setFolderIfUnfiled(folder.id, s.id)) continue
            filed++
            val result = runCatching {
                WorkspaceMover.moveSessionIntoProject(filesDir, s.id, folder.id)
            }.onFailure {
                blocked = true
                AppLogger.warning(
                    "LegacyWorkspaceMigrator",
                    "move failed for session=${s.id} (will retry next launch): ${it.message}",
                )
            }.getOrNull()
            if (result == null || !result.succeeded) blocked = true
            movedBytes += result?.bytesMoved ?: 0L
        }
        seedProjectFromGlobal(filesDir, folder.id)
        AppLogger.info(
            "LegacyWorkspaceMigrator",
            "legacy migrate done: filed=$filed movedBytes=$movedBytes of ${sessions.size} sessions",
        )
        if (!blocked) {
            prefs.edit()
                .putBoolean(KEY_DONE_V2, true)
                .putBoolean(KEY_DONE, true)
                .apply()
        }
    }
}
