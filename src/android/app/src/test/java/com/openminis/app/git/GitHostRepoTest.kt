package com.openminis.app.git

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [GitHostRepo] against a real JGit repository in a temp dir —
 * the same object model the sandbox path parses shell output into, so the
 * dual-source panel can be verified without a device.
 */
class GitHostRepoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** New repo with one commit; returns the repo dir and the Git handle. */
    private fun initRepo(): Pair<File, Git> {
        val dir = tmp.newFolder("repo")
        val git = Git.init().setDirectory(dir).call()
        File(dir, "a.txt").writeText("line1\nline2\n")
        git.add().addFilepattern("a.txt").call()
        git.commit().setMessage("initial commit").setAuthor("T", "t@x").call()
        return dir to git
    }

    // ── locate ─────────────────────────────────────────────────────────────

    @Test
    fun `locate picks the workspace itself when it is a repo`() {
        val (dir, git) = initRepo()
        git.close()
        assertEquals(dir to "", GitHostRepo.locate(dir))
    }

    @Test
    fun `locate finds a nested repo and reports its relative path`() {
        val ws = tmp.newFolder("ws")
        val nested = File(ws, "project").apply { mkdirs() }
        val git = Git.init().setDirectory(nested).call()
        git.close()
        val (dir, rel) = GitHostRepo.locate(ws)!!
        assertEquals(nested, dir)
        assertEquals("project", rel)
    }

    @Test
    fun `locate resolves the guest path`() {
        assertEquals("/var/minis/workspace", GitHostRepo.guestPath(""))
        assertEquals("/var/minis/workspace/repo", GitHostRepo.guestPath("repo"))
    }

    @Test
    fun `locate returns null with no repo anywhere`() {
        val ws = tmp.newFolder("empty")
        assertNull(GitHostRepo.locate(ws))
    }

    // ── probe / status ─────────────────────────────────────────────────────

    @Test
    fun `probe reports repo identity`() {
        val (dir, git) = initRepo()
        git.close()
        val info = GitHostRepo.probe(dir)
        assertTrue(info.isRepo)
        assertEquals(dir.absolutePath, info.root)
        assertEquals("master", info.branch.takeIf { it.isNotEmpty() } ?: "main")
    }

    @Test
    fun `probe outside a work tree is not a repo`() {
        assertFalse(GitHostRepo.probe(tmp.newFolder("plain")).isRepo)
    }

    @Test
    fun `status shows the clean tree then an unstaged edit`() {
        val (dir, git) = initRepo()
        val clean = GitHostRepo.status(dir)
        assertTrue(clean.entries.isEmpty())
        assertEquals(0, clean.ahead)
        File(dir, "a.txt").writeText("line1\nline2 changed\n")
        git.add().addFilepattern("a.txt").call()
        val staged = GitHostRepo.status(dir)
        assertTrue(staged.entries.any { it.path == "a.txt" && it.staged })
        git.close()
    }

    // ── log ────────────────────────────────────────────────────────────────

    @Test
    fun `logPage returns commits newest first with parents`() {
        val (dir, git) = initRepo()
        File(dir, "a.txt").writeText("line1\nline2\nline3\n")
        git.add().addFilepattern("a.txt").call()
        git.commit().setMessage("second commit").setAuthor("T", "t@x").call()
        git.close()
        val page = GitHostRepo.logPage(dir, offset = 0, limit = 10)
        assertEquals(2, page.size)
        assertEquals("second commit", page[0].subject)
        assertEquals("initial commit", page[1].subject)
        assertTrue(page[1].parents.isEmpty())
        assertEquals(1, page[0].parents.size)
        assertEquals(page[1].hash, page[0].parents[0])
    }

    @Test
    fun `logPage offset skips older commits`() {
        val (dir, git) = initRepo()
        git.close()
        val page = GitHostRepo.logPage(dir, offset = 1, limit = 10)
        assertTrue(page.isEmpty())
    }

    // ── numstat ────────────────────────────────────────────────────────────

    @Test
    fun `numstatStaged counts staged edits`() {
        val (dir, git) = initRepo()
        File(dir, "a.txt").writeText("line1\nline2 changed\nline3\n")
        git.add().addFilepattern("a.txt").call()
        git.close()
        val stats = GitHostRepo.numstatStaged(dir)
        val stat = stats["a.txt"]
        assertNotNull("staged edit must appear in numstat", stat)
        assertEquals(2, stat!!.added)
        assertEquals(1, stat.deleted)
        assertFalse(stat.binary)
    }

    @Test
    fun `numstatUnstaged counts worktree edits`() {
        val (dir, git) = initRepo()
        git.close()
        File(dir, "a.txt").writeText("line1\nline2 changed\nline3\n")
        val stats = GitHostRepo.numstatUnstaged(dir)
        val stat = stats["a.txt"]
        assertNotNull("worktree edit must appear in numstat", stat)
        assertEquals(2, stat!!.added)
        assertEquals(1, stat.deleted)
    }

    @Test
    fun `stagedDiff returns the unified diff for AI commit messages`() {
        val (dir, git) = initRepo()
        File(dir, "a.txt").writeText("line1\nline2 changed\n")
        git.add().addFilepattern("a.txt").call()
        git.close()
        val diff = GitHostRepo.stagedDiff(dir)
        assertTrue(diff.contains("a.txt"))
        assertTrue(diff.contains("+line2 changed"))
    }

    // ── stage / commit ─────────────────────────────────────────────────────

    @Test
    fun `setStaged and commit round-trip through the host path`() {
        val (dir, git) = initRepo()
        git.close()
        File(dir, "a.txt").writeText("line1\nline2 changed\n")
        GitHostRepo.setStaged(dir, "a.txt", staged = true)
        assertTrue(GitHostRepo.status(dir).entries.any { it.staged })
        val identity = GitHostRepo.commitIdentity(dir, null, null)
        val outcome = GitHostRepo.commit(dir, "host commit", identity)
        assertTrue(outcome.ok)
        val page = GitHostRepo.logPage(dir, offset = 0, limit = 5)
        assertEquals("host commit", page[0].subject)
        assertTrue("index is clean after commit", GitHostRepo.status(dir).entries.isEmpty())
    }

    @Test
    fun `commitIdentity prefers repo-local config over the sandbox identity`() {
        val (dir, git) = initRepo()
        git.close()
        Git.open(dir).use { repo ->
            repo.config.setString("user", null, "name", "Local")
            repo.config.setString("user", null, "email", "local@x")
            repo.config.save()
        }
        assertEquals("Local", GitHostRepo.commitIdentity(dir, "T", "t@x").name)
        assertTrue(GitHostRepo.commitIdentity(dir, null, null).name.isNotBlank())
    }

    @Test
    fun `unstage keeps the file content and marks it unstaged-modified`() {
        val (dir, git) = initRepo()
        git.close()
        File(dir, "a.txt").writeText("line1\nline2 changed\n")
        GitHostRepo.setStaged(dir, "a.txt", staged = true)
        GitHostRepo.setStaged(dir, "a.txt", staged = false)
        val entries = GitHostRepo.status(dir).entries.filter { it.path == "a.txt" }
        assertEquals(1, entries.size)
        assertFalse(entries[0].staged)
        assertEquals('M', entries[0].code)
        assertEquals("line1\nline2 changed\n", File(dir, "a.txt").readText())
    }

    @Test
    fun `status lists an unstaged deletion as a single D row`() {
        val (dir, git) = initRepo()
        git.close()
        File(dir, "a.txt").delete()
        val entries = GitHostRepo.status(dir).entries.filter { it.path == "a.txt" }
        assertEquals(1, entries.size)
        assertEquals('D', entries[0].code)
        assertFalse(entries[0].staged)
    }

    @Test
    fun `status lists both rows for a staged-then-edited file`() {
        val (dir, git) = initRepo()
        git.close()
        File(dir, "a.txt").writeText("line1\nline2 staged\n")
        GitHostRepo.setStaged(dir, "a.txt", staged = true)
        File(dir, "a.txt").writeText("line1\nline2 staged then edited\n")
        val entries = GitHostRepo.status(dir).entries.filter { it.path == "a.txt" }
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.staged })
        assertTrue(entries.any { !it.staged })
    }

    @Test
    fun `numstatStaged does not write loose objects into the repository`() {
        val (dir, git) = initRepo()
        File(dir, "a.txt").writeText("line1\nline2 changed\n")
        git.add().addFilepattern("a.txt").call()
        git.close()
        fun looseCount() = File(dir, ".git/objects").walkBottomUp().count { it.isFile }
        val before = looseCount()
        GitHostRepo.numstatStaged(dir)
        assertEquals(before, looseCount())
    }

    @Test
    fun `numstatUnstaged excludes untracked files`() {
        val (dir, git) = initRepo()
        git.close()
        File(dir, "a.txt").writeText("line1\nline2 changed\nline3\n")
        File(dir, "u.txt").writeText("untracked\n")
        val stats = GitHostRepo.numstatUnstaged(dir)
        assertNotNull(stats["a.txt"])
        assertNull("untracked files must not appear in the unstaged numstat", stats["u.txt"])
    }

    @Test
    fun `stagedDiff on an unborn HEAD lists the staged file`() {
        val dir = tmp.newFolder("unborn")
        val git = Git.init().setDirectory(dir).call()
        File(dir, "a.txt").writeText("line1\n")
        git.add().addFilepattern("a.txt").call()
        git.close()
        val diff = GitHostRepo.stagedDiff(dir)
        assertTrue(diff.contains("a.txt"))
        assertTrue(diff.contains("+line1"))
    }

    @Test
    fun `commitFileDiff returns only the target file section in a multi-file commit`() {
        val (dir, git) = initRepo()
        git.close()
        File(dir, "a.txt").writeText("line1\nline2\nline3\n")
        File(dir, "b.txt").writeText("b1\n")
        val git2 = Git.open(dir)
        git2.add().addFilepattern(".").call()
        git2.commit().setMessage("two files").setAuthor("T", "t@x").call()
        git2.close()
        val hash = GitHostRepo.logPage(dir, offset = 0, limit = 1)[0].hash
        val diff = GitHostRepo.commitFileDiff(dir, hash, "a.txt")
        assertTrue(diff.contains("+line3"))
        assertFalse("b.txt must not leak into a.txt's section", diff.contains("b.txt"))
    }

    @Test
    fun `parseNumstatText flags binary files and counts hunks by markers`() {
        val text = "diff --git a/a b/c.txt b/a b/c.txt\n" +
            "index 0000000..d60b25d\n" +
            "--- a/a b/c.txt\n" +
            "+++ b/a b/c.txt\n" +
            "Binary files differ\n"
        val stats = GitHostRepo.parseNumstatText(text)
        val stat = stats["a b/c.txt"]
        assertNotNull(stat)
        assertTrue(stat!!.binary)
        assertEquals(0, stat.added)
    }

    @Test
    fun `fileDiff staged compares HEAD against the index not the worktree`() {
        val (dir, git) = initRepo()
        git.close()
        File(dir, "a.txt").writeText("line1\nline2 staged\n")
        GitHostRepo.setStaged(dir, "a.txt", staged = true)
        File(dir, "a.txt").writeText("line1\nline2 staged then edited\n")
        val staged = GitHostRepo.fileDiff(dir, "a.txt", staged = true, untracked = false)
        assertTrue(staged.contains("+line2 staged"))
        assertFalse("worktree-only edit must not leak into the staged diff", staged.contains("edited"))
        val unstaged = GitHostRepo.fileDiff(dir, "a.txt", staged = false, untracked = false)
        assertTrue(unstaged.contains("+line2 staged then edited"))
    }

    @Test
    fun `probe rejects a repo whose HEAD object is missing`() {
        val (dir, git) = initRepo()
        git.close()
        File(dir, ".git/HEAD").writeText("0123456789abcdef0123456789abcdef01234567\n")
        assertFalse(GitHostRepo.probe(dir).isRepo)
    }

    // ── commit detail / numstat / diff ─────────────────────────────────────

    @Test
    fun `commitDetail returns full metadata`() {
        val (dir, git) = initRepo()
        git.close()
        val hash = GitHostRepo.logPage(dir, offset = 0, limit = 1)[0].hash
        val detail = GitHostRepo.commitDetail(dir, hash)!!
        assertEquals("initial commit", detail.subject)
        assertEquals("T", detail.author)
        assertEquals("t@x", detail.authorEmail)
        assertTrue(detail.committerEpoch > 0)
    }

    @Test
    fun `commitNumstat and commitFileDiff describe the commit`() {
        val (dir, git) = initRepo()
        git.close()
        val hash = GitHostRepo.logPage(dir, offset = 0, limit = 1)[0].hash
        val files = GitHostRepo.commitNumstat(dir, hash)
        assertEquals(listOf("a.txt"), files.map { it.path })
        assertEquals(2, files[0].added)
        val diff = GitHostRepo.commitFileDiff(dir, hash, "a.txt")
        assertTrue(diff.contains("+line1"))
    }

    @Test
    fun `commitDiff of the root commit lists every file as added`() {
        val (dir, git) = initRepo()
        git.close()
        val hash = GitHostRepo.logPage(dir, offset = 0, limit = 1)[0].hash
        val diff = GitHostRepo.commitDiff(dir, hash)
        assertTrue(diff.contains("+++ b/a.txt"))
        assertTrue(diff.contains("+line1"))
    }
}
