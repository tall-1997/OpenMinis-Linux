package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [T-prompt-cache-diag] The fingerprint must be stable for identical inputs
 * and change for anything that changes the wire prompt (system text, tool
 * order, a tool description, a parameter).
 */
class PromptCacheFingerprintTest {

    private fun tool(name: String, description: String = "do $name") = AgentToolDefinition(
        name = name,
        description = description,
        parameters = mapOf(
            "arg" to AgentToolParam(type = "string", description = "an arg"),
        ),
        required = listOf("arg"),
    )

    private val tools = listOf(tool("shell_execute"), tool("file_read"))

    @Test
    fun `same system and tools give the same fingerprint`() {
        val a = promptCacheFingerprint("system", tools)
        val b = promptCacheFingerprint("system", tools)
        assertEquals(a, b)
        assertEquals(16, a.length)
    }

    @Test
    fun `system change is visible`() {
        assertNotEquals(
            promptCacheFingerprint("system", tools),
            promptCacheFingerprint("system plus one line", tools),
        )
    }

    @Test
    fun `tool order matters because it is serialized in order`() {
        assertNotEquals(
            promptCacheFingerprint("system", tools),
            promptCacheFingerprint("system", tools.reversed()),
        )
    }

    @Test
    fun `tool description and parameter changes are visible`() {
        assertNotEquals(
            promptCacheFingerprint("system", listOf(tool("shell_execute", "changed"))),
            promptCacheFingerprint("system", listOf(tool("shell_execute"))),
        )
    }

    @Test
    fun `null and empty system behave the same`() {
        assertEquals(promptCacheFingerprint(null, tools), promptCacheFingerprint("", tools))
    }
}
