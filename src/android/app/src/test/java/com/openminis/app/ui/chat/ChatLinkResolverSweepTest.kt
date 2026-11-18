package com.openminis.app.ui.chat

import androidx.test.core.app.ApplicationProvider
import com.openminis.app.sandbox.SessionWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [T-chatlink-cross-project-fallback] Root cause of the field report
 * "模型发我的 minis:// 链接点不进去":
 *
 * `susu.wiki_______-3.4.txt` was written by the agent of session A (project
 * `216f98ef`) into ITS OWN `/var/minis/attachments/uploads/`, and the model
 * emitted `minis://attachments/uploads/susu.wiki_______-3.4.txt`. The chat that
 * rendered the link belongs to a different project, whose session-scoped
 * resolver maps `/var/minis/attachments` to a DIFFERENT host dir
 * (`minis-workspaces/<its-own-folder>/attachments`) where the file does not
 * exist. `resolve()` found nothing and silently degraded to
 * [ChatLinkAction.Web] — a web preview of a `minis://` URL is always a dead
 * tap (no toast, no navigation).
 *
 * The fix sweeps every project tree on disk for the same shared-subdir relative
 * path. These tests pin the sweep contract: scoped to SHARED_SUBDIRS, exact
 * relative match, never crossing into another session's private tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChatLinkResolverSweepTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val filesDir get() = context.filesDir

    private fun makeProject(folderId: String, relativePath: String): File {
        val f = File(File(File(filesDir, SessionWorkspace.WORKSPACES_DIR), folderId), relativePath)
        f.parentFile?.mkdirs()
        f.writeText("payload")
        return f
    }

    private fun makeSessionPrivate(sessionId: String, relativePath: String): File {
        val f = File(
            File(
                File(File(filesDir, SessionWorkspace.SESSIONS_DIR), sessionId),
                relativePath.substringBefore('/'),
            ),
            relativePath.substringAfter('/'),
        )
        f.parentFile?.mkdirs()
        f.writeText("secret")
        return f
    }

    @Test
    fun `sweep finds the file in another project's shared subdir`() {
        val created = makeProject("proj-a", "attachments/uploads/susu.wiki_______-3.4.txt")
        val hit = ChatLinkResolver.sweepProjectTrees(
            "attachments/uploads/susu.wiki_______-3.4.txt",
            context,
        )
        assertEquals(created.absolutePath, hit?.absolutePath)
    }

    @Test
    fun `sweep never returns another session's private tree`() {
        // Same relative path planted under minis-sessions/<other-session> —
        // the jail that keeps one session's files out of another's view.
        makeSessionPrivate("victim-session", "attachments/uploads/leak.txt")
        assertNull(
            ChatLinkResolver.sweepProjectTrees("attachments/uploads/leak.txt", context),
        )
    }

    @Test
    fun `sweep rejects paths outside the shared subdirs`() {
        // A path whose first segment is not a shared subdir (memory, or a
        // forged root) must not be swept — no traversal into private scope.
        assertNull(ChatLinkResolver.sweepProjectTrees("memory/notes.md", context))
        assertNull(ChatLinkResolver.sweepProjectTrees("rootfs/etc/passwd", context))
    }

    @Test
    fun `sweep with no projects dir returns null`() {
        // filesDir has no minis-workspaces yet — must be a clean miss, not a throw.
        assertNull(ChatLinkResolver.sweepProjectTrees("attachments/x.txt", context))
    }

    @Test
    fun `sweep requires an exact relative match`() {
        makeProject("proj-b", "attachments/uploads/other.txt")
        assertNull(ChatLinkResolver.sweepProjectTrees("attachments/uploads/other.txt.bak", context))
        assertNull(ChatLinkResolver.sweepProjectTrees("attachments", context))
    }

    @Test
    fun `sweep picks up directories-then-files layout only`() {
        val dirOnly = File(File(File(filesDir, SessionWorkspace.WORKSPACES_DIR), "proj-c"), "attachments")
        dirOnly.mkdirs()
        // Directory exists but the file does not — must stay null.
        assertNull(ChatLinkResolver.sweepProjectTrees("attachments/uploads/missing.bin", context))
        val now = File(dirOnly, "uploads/missing.bin")
        now.parentFile?.mkdirs()
        now.writeText("x")
        assertEquals(
            now.absolutePath,
            ChatLinkResolver.sweepProjectTrees("attachments/uploads/missing.bin", context)?.absolutePath,
        )
        assertTrue(now.isFile)
    }

    @Test
    fun `sweep with null context is a no-op`() {
        assertNull(ChatLinkResolver.sweepProjectTrees("attachments/x.txt", null))
    }
}
