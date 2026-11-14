package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-file-hub-git-meta] The `.git` metadata watch must cover every
 * state-changing git operation while staying a pure selection (the actual
 * attach is android.os.FileObserver-bound and lives outside JVM tests).
 *
 * Regression: SKIP_DIR_NAMES skipped `.git` entirely, and the coalescer
 * dropped `.git` paths, so commits/pulls made outside the panel (sandbox
 * git CLI, terminal) never reached the panel's auto-refresh. A first
 * attempt watched only refs/heads|refs/remotes flat — inotify is
 * per-directory, so refs in subdirectories (slashed branch names put
 * loose refs in refs/heads/fix/, remotes in refs/remotes/origin/) stayed
 * invisible; gitMetaDirs now walks the whole refs sub-tree.
 */
class FileChangeHubGitMetaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `refs subdirectories are walked - namespaced branches and remotes`() {
        val git = tmp.newFolder(".git")
        // Real-world layout of this repo: slashed branch names + origin remote.
        listOf(
            "refs/heads", "refs/heads/feat", "refs/heads/fix",
            "refs/remotes", "refs/remotes/origin", "refs/tags",
        ).forEach { File(git, it).mkdirs() }

        val dirs = FileChangeHub.gitMetaDirs(git)

        assertEquals(
            listOf(
                git, File(git, "refs"),
                File(git, "refs/heads"), File(git, "refs/heads/feat"), File(git, "refs/heads/fix"),
                File(git, "refs/remotes"), File(git, "refs/remotes/origin"),
                File(git, "refs/tags"),
            ),
            dirs,
        )
    }

    @Test
    fun `walk is depth-capped`() {
        val git = tmp.newFolder(".git")
        File(git, "refs/remotes/origin/pr/42").mkdirs()

        val dirs = FileChangeHub.gitMetaDirs(git)

        assertEquals(
            listOf(
                git, File(git, "refs"), File(git, "refs/remotes"),
                File(git, "refs/remotes/origin"), File(git, "refs/remotes/origin/pr"),
            ),
            dirs,
        )
        assertTrue(dirs.none { it.name == "42" })
    }

    @Test
    fun `heads-only repo degrades to git plus refs tree`() {
        val git = tmp.newFolder(".git")
        File(git, "refs/heads").mkdirs()

        assertEquals(
            listOf(git, File(git, "refs"), File(git, "refs/heads")),
            FileChangeHub.gitMetaDirs(git),
        )
    }

    @Test
    fun `bare git dir yields only itself`() {
        val git = tmp.newFolder(".git")
        assertEquals(listOf(git), FileChangeHub.gitMetaDirs(git))
    }
}
