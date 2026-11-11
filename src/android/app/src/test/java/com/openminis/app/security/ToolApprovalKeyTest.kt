package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [#36] Approval-key construction for the session tool allowlist: gist
 * extraction per tool family and the tool-level / gist-level key shapes.
 */
class ToolApprovalKeyTest {

    @Test
    fun shellToolsGistIsCommand() {
        assertEquals(
            "echo hi",
            ToolApprovalKey.gist("shell_exec", """{"command":"echo hi"}"""),
        )
        assertEquals(
            "echo hi",
            ToolApprovalKey.gist("su_exec", """{"command":"echo hi"}"""),
        )
        // Whitespace is trimmed so a re-issued identical call collides.
        assertEquals(
            "echo hi",
            ToolApprovalKey.gist("env_exec", """{"command":"  echo hi  "}"""),
        )
    }

    @Test
    fun fileToolsGistIsPath() {
        assertEquals(
            "/var/minis/workspace/a.txt",
            ToolApprovalKey.gist("file_write", """{"path":"/var/minis/workspace/a.txt"}"""),
        )
        assertEquals(
            "/var/minis/workspace",
            ToolApprovalKey.gist("list_dir", """{"path":"/var/minis/workspace"}"""),
        )
        assertEquals(
            "/var/minis/a.txt",
            ToolApprovalKey.gist("grep", """{"path":"/var/minis/a.txt","pattern":"x"}"""),
        )
    }

    @Test
    fun sshAndBrowserGistsAreTargetScoped() {
        assertEquals(
            "exec myhost ls -la",
            ToolApprovalKey.gist("ssh_exec", """{"action":"exec","host":"myhost","command":"ls -la"}"""),
        )
        assertEquals(
            "click",
            ToolApprovalKey.gist("browser_use", """{"action":"click","selector":"#ok"}"""),
        )
    }

    @Test
    fun unknownToolOrMissingArgsCollapsesToEmptyGist() {
        assertEquals("", ToolApprovalKey.gist("some_unknown_tool", """{"x":1}"""))
        assertEquals("", ToolApprovalKey.gist("file_write", ""))
        // Non-JSON args fall back to the raw text (still deterministic).
        assertEquals("raw args", ToolApprovalKey.gist("file_write", "raw args"))
    }

    @Test
    fun keyShapes() {
        // Blank gist → tool-level key: covers every call of the tool.
        assertEquals("file_write", ToolApprovalKey.key("file_write", ""))
        // Non-blank gist → target-scoped key.
        assertEquals(
            "file_write|/var/minis/workspace/a.txt",
            ToolApprovalKey.key("file_write", "/var/minis/workspace/a.txt"),
        )
    }
}
