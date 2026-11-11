package com.openminis.app.git

import com.openminis.app.git.GitHostRepoTransfer.Access
import com.openminis.app.git.GitHostRepoTransfer.Result
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [GitHostRepoTransfer] against real JGit repos in temp dirs:
 * the credential classification (`[#33]` native push/pull serves embedded-
 * credential and local-path remotes, falls back for everything else) and the
 * transfer semantics (ff-only pull, non-fast-forward refusal) — the same
 * object model the dual-source panel runs on the host.
 */
class GitHostRepoTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** New repo with one commit; returns the repo dir and the Git handle. */
    private fun initRepo(name: String): Pair<File, Git> {
        val dir = tmp.newFolder(name)
        val git = Git.init().setDirectory(dir).call()
        File(dir, "a.txt").writeText("line1\n")
        git.add().addFilepattern("a.txt").call()
        git.commit().setMessage("initial commit").setAuthor("T", "t@x").call()
        return dir to git
    }

    private fun initBare(name: String): File {
        val dir = tmp.newFolder(name)
        Git.init().setBare(true).setDirectory(dir).call()
        return dir
    }

    /** Wire `origin` to the bare remote and send the initial commit. */
    private fun wireAndPush(work: File, bare: File): Result {
        Git.open(work).use {
            it.remoteAdd()
                .setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toString()))
                .call()
        }
        return GitHostRepoTransfer.push(work)
    }

    private fun commitFile(work: File, git: Git, name: String, text: String) {
        File(work, name).writeText(text)
        git.add().addFilepattern(name).call()
        git.commit().setMessage("add $name").setAuthor("T", "t@x").call()
    }

    // ── resolveAccess ───────────────────────────────────────────────────────

    @Test
    fun `local path remotes need no credentials`() {
        assertEquals(Access.NativeNoCredentials, GitHostRepoTransfer.resolveAccess("/mnt/data/bare.git"))
        assertEquals(Access.NativeNoCredentials, GitHostRepoTransfer.resolveAccess("file:///x/bare.git"))
        assertEquals(Access.NativeNoCredentials, GitHostRepoTransfer.resolveAccess("D:\\tmp\\bare.git"))
    }

    @Test
    fun `embedded credentials parse from the remote url`() {
        val a = GitHostRepoTransfer.resolveAccess("https://user:pass@github.com/o/r.git")
        assertTrue(a is Access.Native)
        val tokenOnly = GitHostRepoTransfer.resolveAccess("https://TOKEN@github.com/o/r.git")
        assertTrue(tokenOnly is Access.Native)
    }

    @Test
    fun `clean https, ssh and missing remote all fall back`() {
        assertEquals(Access.Fallback, GitHostRepoTransfer.resolveAccess("https://github.com/o/r.git"))
        assertEquals(Access.Fallback, GitHostRepoTransfer.resolveAccess("ssh://git@github.com/o/r.git"))
        assertEquals(Access.Fallback, GitHostRepoTransfer.resolveAccess(null))
    }

    // ── optional desktop authentication ────────────────────────────────────

    @Test
    fun `missing JGSS classes accept JVM and Android name formats`() {
        for (name in listOf("org/ietf/jgss/GSSManager", "org.ietf.jgss.Oid")) {
            assertTrue(name, GitHostRepoTransfer.isUnavailableNegotiate(NoClassDefFoundError(name)))
        }
        assertTrue(
            GitHostRepoTransfer.isUnavailableNegotiate(
                NoClassDefFoundError("Failed resolution of: Lorg/ietf/jgss/GSSContext;"),
            ),
        )
    }

    @Test
    fun `nested initialization cause identifies unavailable JGSS`() {
        val missing = ClassNotFoundException("org.ietf.jgss.GSSException")
        val wrapped = ExceptionInInitializerError(IllegalStateException("initializing auth", missing))
        assertTrue(GitHostRepoTransfer.isUnavailableNegotiate(wrapped))
    }

    @Test
    fun `subsequent Negotiate initialization failures fall back`() {
        for (name in listOf(
                "org.eclipse.jgit.transport.HttpAuthMethod\$Negotiate",
                "org/eclipse/jgit/transport/HttpAuthMethod\$Negotiate",
                "org.eclipse.jgit.util.GSSManagerFactory\$DefaultGSSManagerFactory",
            )) {
            assertTrue(
                name,
                GitHostRepoTransfer.isUnavailableNegotiate(
                    NoClassDefFoundError("Could not initialize class $name"),
                ),
            )
        }
    }

    @Test
    fun `unrelated linkage and misleading names do not trigger fallback`() {
        for (error in listOf(
                NoClassDefFoundError("java/lang/ProcessHandle"),
                NoClassDefFoundError("org.eclipse.jgit.transport.HttpAuthMethod\$Basic"),
                NoClassDefFoundError("org.eclipse.jgit.transport.HttpAuthMethod\$NegotiateExtra"),
                NoClassDefFoundError("other.org.ietf.jgss.GSSManager"),
                NoClassDefFoundError("org.eclipse.jgit.util.GSSManagerFactoryExtra"),
                NoClassDefFoundError(),
                NoSuchMethodError("org.ietf.jgss.GSSManager.missingMethod"),
                UnsatisfiedLinkError("org.ietf.jgss.nativeLibrary"),
                ExceptionInInitializerError(IllegalStateException("org.ietf.jgss.GSSManager")),
            )) {
            assertFalse(error.toString(), GitHostRepoTransfer.isUnavailableNegotiate(error))
        }
    }

    @Test(timeout = 1000)
    fun `cyclic cause chains terminate and still inspect each cause`() {
        val first = NoClassDefFoundError("unrelated.First")
        val second = IllegalStateException("unrelated.Second")
        first.initCause(second)
        second.initCause(first)
        assertFalse(GitHostRepoTransfer.isUnavailableNegotiate(first))

        val missing = NoClassDefFoundError("org/ietf/jgss/GSSName")
        val outer = ExceptionInInitializerError(missing)
        missing.initCause(outer)
        assertTrue(GitHostRepoTransfer.isUnavailableNegotiate(outer))
    }

    // ── push ────────────────────────────────────────────────────────────────

    @Test
    fun `push to a local bare remote round-trips`() {
        val (work, git) = initRepo("push-work")
        val bare = initBare("push-bare.git")
        val r = wireAndPush(work, bare)
        assertTrue("expected Done, got $r", r is Result.Done)
        git.close()
        Git.open(bare).use { assertNotNull(it.repository.resolve("HEAD")) }
    }

    @Test
    fun `second push with nothing new reports up to date`() {
        val (work, git) = initRepo("uptodate-work")
        val bare = initBare("uptodate-bare.git")
        wireAndPush(work, bare)
        val r = GitHostRepoTransfer.push(work)
        assertEquals("up to date", (r as Result.Done).message)
        git.close()
    }

    @Test
    fun `non-fast-forward push is refused with git wording`() {
        val (work, git) = initRepo("nonff-work")
        val bare = initBare("nonff-bare.git")
        wireAndPush(work, bare)
        // A second clone moves the bare remote forward.
        val cloneDir = tmp.newFolder("nonff-clone")
        Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(cloneDir).call().use { clone ->
            File(cloneDir, "b.txt").writeText("from clone\n")
            clone.add().addFilepattern("b.txt").call()
            clone.commit().setMessage("clone commit").setAuthor("T", "t@x").call()
            clone.push().call()
        }
        // The original work repo rewrites its history — now behind + diverged.
        git.commit().setAmend(true).setMessage("rewritten").setAuthor("T", "t@x").call()
        val r = GitHostRepoTransfer.push(work)
        assertTrue("expected Error, got $r", r is Result.Error)
        assertTrue((r as Result.Error).message.contains("non-fast-forward"))
        git.close()
    }

    @Test
    fun `push falls back for a clean https remote`() {
        val (work, git) = initRepo("fbhttps-work")
        Git.open(work).use {
            it.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish("https://github.com/o/r.git")).call()
        }
        assertEquals(Result.Fallback, GitHostRepoTransfer.push(work))
        git.close()
    }

    @Test
    fun `push and pull fall back when origin is not configured`() {
        val (work, git) = initRepo("fbnone-work")
        assertEquals(Result.Fallback, GitHostRepoTransfer.push(work))
        assertEquals(Result.Fallback, GitHostRepoTransfer.pull(work))
        git.close()
    }

    // ── pull (ff-only) ──────────────────────────────────────────────────────

    @Test
    fun `pull fast-forwards a moved upstream`() {
        val (work, git) = initRepo("ffpull-work")
        val bare = initBare("ffpull-bare.git")
        wireAndPush(work, bare)
        val cloneDir = tmp.newFolder("ffpull-clone")
        Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(cloneDir).call().use { clone ->
            File(cloneDir, "from-clone.txt").writeText("remote change\n")
            clone.add().addFilepattern("from-clone.txt").call()
            clone.commit().setMessage("clone commit").setAuthor("T", "t@x").call()
            clone.push().call()
        }
        val r = GitHostRepoTransfer.pull(work)
        assertTrue("expected Done, got $r", r is Result.Done)
        assertTrue(File(work, "from-clone.txt").exists())
        git.close()
    }

    @Test
    fun `pull refuses a diverged upstream without merging`() {
        val (work, git) = initRepo("divpull-work")
        val bare = initBare("divpull-bare.git")
        wireAndPush(work, bare)
        git.commit().setAmend(true).setMessage("rewritten").setAuthor("T", "t@x").call()
        val r = GitHostRepoTransfer.pull(work)
        assertTrue("expected Error, got $r", r is Result.Error)
        assertTrue((r as Result.Error).message.contains("diverged"))
        // No merge commit appeared on the rewritten branch.
        assertEquals("rewritten", git.log().call().iterator().next().shortMessage)
        git.close()
    }

    @Test
    fun `pull reports no upstream when the remote branch is missing`() {
        val (work, git) = initRepo("noupull-work")
        val bare = initBare("noupull-bare.git")
        Git.open(work).use {
            it.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toString())).call()
        }
        val r = GitHostRepoTransfer.pull(work)
        val msg = (r as Result.Done).message
        assertNotNull(msg)
        assertTrue(msg!!.startsWith("no upstream for "))
        git.close()
    }

    @Test
    fun `pull on an up-to-date repo says so`() {
        val (work, git) = initRepo("synpull-work")
        val bare = initBare("synpull-bare.git")
        wireAndPush(work, bare)
        val r = GitHostRepoTransfer.pull(work)
        assertEquals("up to date", (r as Result.Done).message)
        git.close()
    }

    // ── guard ───────────────────────────────────────────────────────────────

    @Test
    fun `non-repo directory falls back instead of erroring`() {
        assertEquals(Result.Fallback, GitHostRepoTransfer.push(tmp.newFolder("notarepo")))
        assertEquals(Result.Fallback, GitHostRepoTransfer.pull(tmp.newFolder("notarepo2")))
    }
}
