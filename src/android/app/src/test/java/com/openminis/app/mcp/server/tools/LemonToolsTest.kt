package com.openminis.app.mcp.server.tools

import com.openminis.app.security.AuditEntry
import com.openminis.app.security.Capability
import com.openminis.app.security.Decision
import com.openminis.app.security.GateCommand
import com.openminis.app.security.PermissionMode
import com.openminis.app.security.Reversibility
import com.openminis.app.security.RiskLevel
import com.openminis.app.security.SecurityGate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-lemon-integration] Contract for the bundled catalog tools.
 *
 * These run as plain JVM tests: `LemonCatalog.all(null)` returns an empty map
 * when the asset is absent from the test classpath, which is the same shape a
 * broken build would hit — so the assertions cover the FAILURE path (error
 * result, never a crash) rather than a live catalog.
 */
class LemonToolsTest {

    @Test
    fun `catalog tools are registered with object schemas`() {
        val tools = lemonTools(deps())
        assertEquals(listOf("lemon_list", "lemon_call"), tools.map { it.name })
        tools.forEach {
            assertEquals("object", it.schema.getString("type"))
            assertTrue("description must explain the tool", it.description.length > 20)
        }
    }

    @Test
    fun `lemon_call requires a name`() {
        val schema = lemonTools(deps()).first { it.name == "lemon_call" }.schema
        val required = schema.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("name", required.getString(0))
    }

    @Test
    fun `missing catalog is an error result, never a crash`() {
        val tools = lemonTools(deps())
        val list = tools.first { it.name == "lemon_list" }.handler(JSONObject())
        assertTrue("no asset on the JVM classpath must surface as isError", list.isError)
    }

    @Test
    fun `lemon_call without name is rejected before touching the catalog`() {
        val res = lemonTools(deps()).first { it.name == "lemon_call" }.handler(JSONObject())
        assertTrue(res.isError)
    }

    /** Allow-everything gate: these tests never reach the dispatcher. */
    private class AllowGate : SecurityGate {
        override fun setPermissionMode(mode: PermissionMode) {}
        override fun getPermissionMode(): PermissionMode = PermissionMode.ALLOW_ALL
        override fun classify(toolName: String, toolArgs: String): GateCommand =
            GateCommand(toolName, toolArgs, Capability.PROCESS, Reversibility.REVERSIBLE, "test")
        override fun classifyRisk(command: String): RiskLevel = RiskLevel.NORMAL
        override fun decide(cmd: GateCommand, mode: PermissionMode): Decision = Decision.Allow("test")
        override fun preview(cmd: GateCommand): String = ""
        override fun audit(cmd: GateCommand, decision: Decision, result: String?) {}
        override fun getAuditTrail(): List<AuditEntry> = emptyList()
    }

    private fun deps() = McpToolDeps(
        context = null,
        gate = AllowGate(),
        shellMode = { PermissionMode.ALLOW_ALL },
    )
}
