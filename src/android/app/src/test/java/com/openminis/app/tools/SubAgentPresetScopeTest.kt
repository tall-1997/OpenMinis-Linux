package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#530] Pins the sub-agent preset contract:
 *  - the five built-in presets exist and are read-only;
 *  - an explicitly writable preset keeps its kind;
 *  - a preset WITHOUT a tool whitelist defaults to the read-only scope instead
 *    of inheriting the caller's full tool list;
 *  - a read-only scope downgrades a requested worker kind.
 */
class SubAgentPresetScopeTest {

    private fun tool(name: String) = AgentToolDefinition(name = name, description = "", parameters = emptyMap())

    @Test
    fun `five gh530 presets are built in and read only`() {
        val presets = SubAgentTypeStore.builtins()
        val names = listOf("代码审计", "方案规划", "调研", "复现", "提炼")
        for (name in names) {
            val preset = presets.firstOrNull { it.name == name }
            assertTrue("missing preset $name", preset != null)
            assertEquals(SCOPE_READ_ONLY, preset!!.toolScope)
            assertTrue("preset $name must whitelist tools", preset.toolNames.isNotEmpty())
        }
    }

    @Test
    fun `legacy scope derives from the kind`() {
        assertEquals(SCOPE_WRITE, SubAgentTypeStore.legacyScopeFor(SubAgentKind.WORKER))
        assertEquals(SCOPE_READ_ONLY, SubAgentTypeStore.legacyScopeFor(SubAgentKind.PLAN))
    }

    @Test
    fun `read only preset downgrades a requested worker kind`() {
        val preset = SubAgentTypeStore.builtins().first { it.name == "代码审计" }
        assertEquals(SubAgentKind.PLAN, DispatchAgentsTool.effectiveKind(preset, SubAgentKind.WORKER))
        assertEquals(SubAgentKind.EXPLORE, DispatchAgentsTool.effectiveKind(preset, SubAgentKind.EXPLORE))
    }

    @Test
    fun `writable preset keeps the requested kind`() {
        val preset = SubAgentTypeStore.builtins().first { it.name == "编码员" }
        assertEquals(SubAgentKind.WORKER, DispatchAgentsTool.effectiveKind(preset, SubAgentKind.WORKER))
    }

    @Test
    fun `read only preset without a whitelist defaults to read only tools`() {
        val preset = SubAgentType(
            name = "adhoc",
            description = "",
            systemPrompt = "",
            skillNames = emptyList(),
            toolNames = emptyList(),
            kind = SubAgentKind.PLAN,
            toolScope = SCOPE_READ_ONLY,
            builtin = false,
        )
        val filtered = DispatchAgentsTool.filterToolsForType(
            preset,
            listOf(tool("file_read"), tool("web_search"), tool("file_write"), tool("ask_user_question")),
        ).map { it.name }.toSet()
        assertEquals(setOf("file_read", "web_search"), filtered)
    }

    @Test
    fun `write scoped preset without a whitelist keeps every tool`() {
        val preset = SubAgentType(
            name = "worker2",
            description = "",
            systemPrompt = "",
            skillNames = emptyList(),
            toolNames = emptyList(),
            kind = SubAgentKind.WORKER,
            toolScope = SCOPE_WRITE,
            builtin = false,
        )
        val filtered = DispatchAgentsTool.filterToolsForType(
            preset,
            listOf(tool("file_read"), tool("file_write")),
        ).map { it.name }.toSet()
        assertEquals(setOf("file_read", "file_write"), filtered)
    }

    @Test
    fun `read only whitelist cannot smuggle a write tool`() {
        val preset = SubAgentType(
            name = "sneaky",
            description = "",
            systemPrompt = "",
            skillNames = emptyList(),
            toolNames = listOf("file_read", "file_write"),
            kind = SubAgentKind.PLAN,
            toolScope = SCOPE_READ_ONLY,
            builtin = false,
        )
        val filtered = DispatchAgentsTool.filterToolsForType(
            preset,
            listOf(tool("file_read"), tool("file_write")),
        ).map { it.name }.toSet()
        assertEquals(setOf("file_read"), filtered)
    }
}
