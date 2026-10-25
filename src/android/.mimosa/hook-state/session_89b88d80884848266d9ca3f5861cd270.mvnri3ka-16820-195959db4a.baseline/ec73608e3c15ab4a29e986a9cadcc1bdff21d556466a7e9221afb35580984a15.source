package com.openminis.app.ui.chat

import com.openminis.app.sandbox.ExecutionCoordinator

/**
 * Move every per-session disk resource from the draft directory to the
 * real one, and tear down any shell that was started against the draft id.
 *
 * The draft key leaks into persistent shells (`ExecutionCoordinator`),
 * browser artifacts (`persistBrowserArtifact`), and the `BrowserTabPool`'s
 * cookie/state store. Before this migration ran, a tool invocation that
 * happened before the user's first message would write into the draft's
 * `minis-sessions/__new__{uuid}` directory and become invisible the
 * moment the VM was recreated under the real id — exactly the symptom
 * observed with the Chinese-named TikTok download that appeared to
 * "disappear" after `yt-dlp` reported success.
 */
internal fun ChatViewModel.migrateDraftResources(fromDraft: String, toReal: String) {
    // Stop any shell that was already spun up against the draft id; its
    // -b mount arguments were frozen to the draft directory at launch, so
    // we can't reuse it after the migration.
    runCatching { ExecutionCoordinator.sessionDidTerminate(fromDraft) }

    val base = java.io.File(context.filesDir, "minis-sessions")
    val draftBase = java.io.File(base, fromDraft)
    if (!draftBase.isDirectory) return
    val realBase = java.io.File(base, toReal).apply { mkdirs() }

    com.openminis.app.sandbox.SessionWorkspace.SESSION_SUBDIRS.forEach { subdir ->
        val src = java.io.File(draftBase, subdir)
        if (!src.isDirectory) return@forEach
        val dst = java.io.File(realBase, subdir).apply { mkdirs() }
        src.listFiles()?.forEach { child ->
            val target = java.io.File(dst, child.name)
            runCatching {
                if (!target.exists() && !child.renameTo(target)) {
                    copyRecursive(child, target)
                }
            }.onFailure {
                android.util.Log.w("ChatViewModel",
                    "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
            }
        }
    }
    runCatching { draftBase.deleteRecursively() }

    // Also rename the BrowserTabPool saved-state file (filesDir/browser_tabs/<sid>.json).
    // Otherwise the pool will load empty state on the next re-entry and the
    // user loses their open tabs even though the URLs never truly "went away".
    val tabsDir = java.io.File(context.filesDir, "browser_tabs")
    val draftTabs = java.io.File(tabsDir, "$fromDraft.json")
    if (draftTabs.exists()) {
        val realTabs = java.io.File(tabsDir, "$toReal.json")
        runCatching {
            if (!realTabs.exists()) {
                if (!draftTabs.renameTo(realTabs)) {
                    draftTabs.copyTo(realTabs, overwrite = false)
                    draftTabs.delete()
                }
            }
        }
    }
}

private fun copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
    if (src.isDirectory) {
        dst.mkdirs()
        src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
    } else {
        src.copyTo(dst, overwrite = false)
        src.delete()
        true
    }
}.getOrDefault(false)
