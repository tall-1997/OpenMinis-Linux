package com.openminis.app.mcp.server

import com.openminis.app.security.AuditEntry
import com.openminis.app.security.AuditChainVerification
import com.openminis.app.security.Capability
import com.openminis.app.security.Decision
import com.openminis.app.security.GateCommand
import com.openminis.app.security.PermissionMode
import com.openminis.app.security.PermissionProfile
import com.openminis.app.security.PermissionRule
import com.openminis.app.security.Reversibility
import com.openminis.app.security.RiskLevel
import com.openminis.app.security.SecurityGate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-tool-surface] The seven tools added to close the MCP surface gap
 * (schedule_task x4, alarm_reminder, context_apps_query, file_write).
 * JVM tests run with context = null, so the host-dependent paths degrade
 * to "no host context" — which is itself a contract worth pinning — while
 * argument validation runs BEFORE the context check and is fully testable.
 */
class McpToolDispatcherExtendedTest {

    private class FakeGate(
        var nextDecision: Decision = Decision.Allow("fine"),
    ) : SecurityGate {
        override fun setPermissionMode(mode: PermissionMode) {}
        override fun getPermissionMode(): PermissionMode = PermissionMode.ALLOW_ALL
        override fun classify(toolName: String, toolArgs: String): GateCommand =
            GateCommand(toolName, toolArgs, Capability.PROCESS, Reversibility.REVERSIBLE, "fake")
        override fun classifyRisk(command: String): RiskLevel = RiskLevel.NORMAL
        override fun decide(cmd: GateCommand, mode: PermissionMode): Decision = nextDecision
        override fun preview(cmd: GateCommand): String = "fake preview"
        override fun audit(cmd: GateCommand, decision: Decision, result: String?) {}
        override fun getAuditTrail(): List<AuditEntry> = emptyList()
        override fun setPermissionRules(rules: List<PermissionRule>) {}
        override fun setAuthorityProfile(profile: PermissionProfile?) {}
        override fun verifyAuditChain(): AuditChainVerification =
            AuditChainVerification(ok = true)
    }

    private fun dispatcher(): McpToolDispatcher =
        @Suppress("USELESS_ELVIS")
        McpToolDispatcher(null as android.content.Context?, FakeGate(), { PermissionMode.ALLOW_ALL })

    private fun call(name: String, args: String = "{}"): McpServerCore.CallResult =
        dispatcher().callTool(name, JSONObject(args))

    private fun firstText(r: McpServerCore.CallResult): String =
        r.content.getJSONObject(0).getString("text")

    // ─── listTools surface ────────────────────────────────────────────────

    @Test
    fun `listTools exposes all twelve tools`() {
        val tools = dispatcher().listTools()
        assertEquals(12, tools.length())
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(
            listOf(
                "device_info", "shell_exec", "ui_read", "ui_action", "file_read",
                "file_write", "schedule_task_create", "schedule_task_list",
                "schedule_task_update", "schedule_task_delete",
                "alarm_reminder_create", "context_apps_query",
            ),
            names,
        )
        for (i in 0 until tools.length()) {
            assertEquals("object", tools.getJSONObject(i).getJSONObject("inputSchema").getString("type"))
        }
    }

    // ─── file_write argument validation (pre-context) ─────────────────────

    @Test
    fun `file write rejects missing path`() {
        val r = call("file_write", """{"content":"x"}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("'path' is required"))
    }

    @Test
    fun `file write rejects oversized content`() {
        val big = "x".repeat(McpToolDispatcher.MAX_FILE_WRITE_CHARS + 1)
        val r = call("file_write", """{"path":"/var/minis/workspace/a.txt","content":"$big"}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("exceeds"))
    }

    @Test
    fun `file write rejects denied path prefix with -32003`() {
        val r = call("file_write", """{"path":"/etc/passwd","content":"x"}""")
        assertTrue(r.isError)
        assertEquals(
            McpServerCore.ErrorCode.PATH_DENIED,
            r.errorCode,
        )
    }

    @Test
    fun `file write rejects traversal that survives normalization`() {
        val r = call(
            "file_write",
            """{"path":"/var/minis/workspace/../../etc/shadow","content":"x"}""",
        )
        assertTrue(r.isError)
    }

    @Test
    fun `file write without host context degrades gracefully`() {
        val r = call("file_write", """{"path":"/var/minis/workspace/ok.txt","content":"x"}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("no host context"))
    }

    // ─── schedule_task_create validation ──────────────────────────────────

    @Test
    fun `schedule task create rejects missing prompt`() {
        val r = call("schedule_task_create", """{"schedule":"30m"}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("'prompt' is required"))
    }

    @Test
    fun `schedule task create rejects bad schedule`() {
        val r = call("schedule_task_create", """{"schedule":"whenever","prompt":"x"}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("schedule must look like"))
    }

    @Test
    fun `schedule task create without context degrades after validation`() {
        val r = call("schedule_task_create", """{"schedule":"30m","prompt":"x"}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("no host context"))
    }

    // ─── alarm_reminder_create validation ─────────────────────────────────

    @Test
    fun `alarm reminder rejects delay below 60s`() {
        val r = call("alarm_reminder_create", """{"delay_sec":30,"message":"m"}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("60-86400"))
    }

    @Test
    fun `alarm reminder rejects delay above 86400s`() {
        val r = call("alarm_reminder_create", """{"delay_sec":100000,"message":"m"}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("60-86400"))
    }

    @Test
    fun `alarm reminder rejects missing message`() {
        val r = call("alarm_reminder_create", """{"delay_sec":120}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("'message' is required"))
    }

    // ─── schedule_task_update / delete validation ─────────────────────────

    @Test
    fun `schedule task update rejects missing id`() {
        val r = call("schedule_task_update", """{"enabled":false}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("'id' is required"))
    }

    @Test
    fun `schedule task delete rejects missing id`() {
        val r = call("schedule_task_delete", """{}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("'id' is required"))
    }

    // ─── context_apps_query degradation ───────────────────────────────────

    @Test
    fun `context apps query without host context degrades gracefully`() {
        val r = call("context_apps_query", """{"filter":"minis"}""")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("no host context"))
    }

    @Test
    fun `schedule task list without host context degrades gracefully`() {
        val r = call("schedule_task_list")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("no host context"))
    }
}
