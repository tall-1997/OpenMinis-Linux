package com.openminis.app.ui.chat

import com.openminis.app.sandbox.SessionWorkspace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [GH#325] Retention marker contract: an explicit clear/compact is remembered
 * on disk (so a restart cannot auto-delete the session), and an explicit
 * session delete still removes everything.
 */
class ChatSessionRetentionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun explicitEmptyMarksTheSessionRetained() {
        val files = tmp.newFolder("files")
        assertFalse(ChatSessionRetention.isRetained(files, "s1"))
        ChatSessionRetention.markExplicitlyEmptied(files, "s1", "clear")
        assertTrue(ChatSessionRetention.isRetained(files, "s1"))
    }

    @Test
    fun unsafeIdsNeverCreateAMarker() {
        val files = tmp.newFolder("files")
        ChatSessionRetention.markExplicitlyEmptied(files, "..", "clear")
        assertFalse(ChatSessionRetention.isRetained(files, ".."))
    }

    @Test
    fun explicitDeleteRemovesMarkerWithTheSessionTree() {
        val files = tmp.newFolder("files")
        ChatSessionRetention.markExplicitlyEmptied(files, "s1", "compact")
        assertTrue(ChatSessionRetention.isRetained(files, "s1"))
        SessionWorkspace.deleteEntire(files, "s1")
        assertFalse(ChatSessionRetention.isRetained(files, "s1"))
    }
}
