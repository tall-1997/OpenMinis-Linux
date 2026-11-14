package com.openminis.app.git

import java.io.File

/**
 * [T-git-panel-zcode-discovery] Workspace-anchored repo discovery, HOST side.
 *
 * ZCode's `resolveRepository` anchors at the workspace path and trusts
 * `git rev-parse --show-toplevel` for everything inside it; the Android
 * counterpart must find the repo WITHOUT a shell, so discovery walks the
 * workspace tree. The two sources (this walk and the sandbox
 * `GitCliCmd.DISCOVER_CMD`) previously disagreed — the host saw only two
 * levels while the sandbox find skipped the workspace's own `.git` — and a
 * repo one source could see and the other could not flipped the panel
 * between its fast and slow paths. Both now cover the SAME set: the
 * workspace itself plus repos nested up to FOUR levels below it.
 *
 * Ties break by most recently active (`.git/HEAD` mtime — every commit and
 * checkout touches it), mirroring the sandbox discovery's activity signal.
 */
object GitHostRepoDiscovery {

    /** Max repo depth below the workspace (ZCode rev-parse resolves any depth). */
    private const val MAX_DEPTH = 4

    /** Backoff before a discovery retry (transient VFS hiccup rescue). */
    private const val DISCOVERY_RETRY_BACKOFF_MS = 120L

    /** True when [dir] contains a `.git` entry (dir or gitfile for worktrees). */
    private fun isRepo(dir: File): Boolean = File(dir, ".git").exists()

    /**
     * The session workspace itself, or a repo nested up to [MAX_DEPTH] levels
     * below it. Returns the host root plus its path relative to the workspace
     * ("" = workspace itself); null when no repo is found.
     */
    fun locate(workspaceDir: File): Pair<File, String>? {
        if (!workspaceDir.isDirectory) return null
        if (isRepo(workspaceDir)) return workspaceDir to ""
        val candidates = ArrayList<Pair<File, String>>()
        walk(workspaceDir, depth = 0, relative = "") { dir, relative ->
            if (isRepo(dir)) candidates += dir to relative
        }
        // [T-git-panel-discovery-retry] One clean pass is not enough on a
        // phone: a transient VFS hiccup (listFiles returning null mid-walk,
        // a .git probe flipping false) silently shrinks the candidate set
        // and the panel flips between "found" and "not a repository" across
        // 2s auto-refreshes. A second pass after a short backoff costs
        // nothing when the first was complete and rescues the flaky case.
        if (candidates.isEmpty()) {
            Thread.sleep(DISCOVERY_RETRY_BACKOFF_MS)
            walk(workspaceDir, depth = 0, relative = "") { dir, relative ->
                if (isRepo(dir)) candidates += dir to relative
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { (dir, _) ->
            File(dir, ".git/HEAD").takeIf { it.isFile }?.lastModified() ?: 0L
        }
    }

    private fun walk(dir: File, depth: Int, relative: String, visit: (File, String) -> Unit) {
        if (depth >= MAX_DEPTH) return
        val children = dir.listFiles(File::isDirectory)
        // A null listing (transient I/O error) must not cut the walk short:
        // retry once before giving up on this subtree.
        var retry = children
        if (retry == null) {
            Thread.sleep(DISCOVERY_RETRY_BACKOFF_MS)
            retry = dir.listFiles(File::isDirectory)
        }
        if (retry == null) return
        for (child in retry) {
            if (child.name == ".git") continue
            val rel = if (relative.isEmpty()) child.name else "$relative/${child.name}"
            visit(child, rel)
            walk(child, depth + 1, rel, visit)
        }
    }

    /** Guest-side absolute path of a repo discovered under the workspace. */
    fun guestPath(relative: String): String =
        if (relative.isBlank()) GUEST_WORKSPACE else "$GUEST_WORKSPACE/$relative"

    /** The workspace's fixed mount point inside the PRoot sandbox. */
    const val GUEST_WORKSPACE = "/var/minis/workspace"
}
