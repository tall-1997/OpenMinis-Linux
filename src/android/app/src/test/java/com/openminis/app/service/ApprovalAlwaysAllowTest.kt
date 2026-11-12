package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#36] Session-scoped tool allowlist: the approval card's "always allow this
 * session" action stores a (tool, args-gist) key; re-calls on the same target
 * auto-approve, different targets (or other tools) still ask. Keys live in
 * memory for the session only — no DB.
 */
class ApprovalAlwaysAllowTest {

    @Test
    fun gistKeyAutoApprovesSameTargetOnly() {
        ApprovalGate.resetSessionAllowAll()
        ApprovalGate.bindSession("allow1")
        val gist = "/var/minis/workspace/a.txt"
        val first = ApprovalGate.requestApproval(
            "allow1", "file_write", "write a.txt",
            mustPrompt = false, argsGist = gist,
        )
        assertTrue(first.isNotEmpty())

        // "Always allow this session" stores the gist key.
        ApprovalGate.allowToolForSession("file_write", "allow1", gist)

        // Same tool + same target → auto-approved (empty id).
        assertEquals(
            "",
            ApprovalGate.requestApproval(
                "allow1", "file_write", "write a.txt again",
                mustPrompt = false, argsGist = gist,
            ),
        )
        // Same tool, DIFFERENT target → still asks.
        assertTrue(
            ApprovalGate.requestApproval(
                "allow1", "file_write", "write b.txt",
                mustPrompt = false, argsGist = "/var/minis/workspace/b.txt",
            ).isNotEmpty(),
        )
        // Other tool → still asks.
        assertTrue(
            ApprovalGate.requestApproval(
                "allow1", "su_exec", "su", mustPrompt = false, argsGist = "",
            ).isNotEmpty(),
        )
        ApprovalGate.resetSessionAllowAll()
    }

    @Test
    fun blankGistGrantsWholeTool() {
        ApprovalGate.resetSessionAllowAll()
        ApprovalGate.bindSession("allow2")
        ApprovalGate.allowToolForSession("file_write", "allow2", "")

        // Any gist of that tool now auto-approves (tool-level key match).
        assertEquals(
            "",
            ApprovalGate.requestApproval(
                "allow2", "file_write", "write anything",
                mustPrompt = false, argsGist = "/var/minis/whatever.txt",
            ),
        )
        // A different tool does not.
        assertTrue(
            ApprovalGate.requestApproval(
                "allow2", "file_edit", "edit x", mustPrompt = false, argsGist = "/x",
            ).isNotEmpty(),
        )
        ApprovalGate.resetSessionAllowAll()
    }

    @Test
    fun sessionIsolationAndCleanup() {
        ApprovalGate.resetSessionAllowAll()
        ApprovalGate.bindSession("allow3")
        ApprovalGate.allowToolForSession("file_write", "allow3", "/a")
        assertTrue(ApprovalGate.isToolAllowedForSession("file_write", "allow3", "/a"))
        assertFalse(ApprovalGate.isToolAllowedForSession("file_write", "other-session", "/a"))

        // Session teardown drops the allowlist (in-memory only).
        ApprovalGate.resetSessionAllowAll("allow3")
        assertFalse(ApprovalGate.isToolAllowedForSession("file_write", "allow3", "/a"))
        ApprovalGate.resetSessionAllowAll()
    }

    @Test
    fun mustPromptNeverSkipsViaAllowlist() {
        ApprovalGate.resetSessionAllowAll()
        ApprovalGate.bindSession("allow4")
        ApprovalGate.allowToolForSession("shell_exec", "allow4", "rm -rf /")
        // Fatal confirms always file a real request, even for an allowed key.
        val id = ApprovalGate.requestApproval(
            "allow4", "shell_exec", "fatal", mustPrompt = true, argsGist = "rm -rf /",
        )
        assertTrue(id.isNotEmpty())
        ApprovalGate.resetSessionAllowAll()
    }

    @Test
    fun requestCarriesGistForUiAllowlist() {
        ApprovalGate.resetSessionAllowAll()
        ApprovalGate.bindSession("allow5")
        val id = ApprovalGate.requestApproval(
            "allow5", "shell_exec", "preview", mustPrompt = false, argsGist = "echo hi",
        )
        val detail = ApprovalGate.detailOf(id)
        assertEquals("shell_exec", detail?.toolName)
        assertEquals("echo hi", detail?.argsGist)
        ApprovalGate.resetSessionAllowAll()
    }
}
