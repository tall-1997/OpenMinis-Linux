package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-cache-tool-order] Evidence guard for the "tool definitions change
 * order / serialize nondeterministically" hypothesis.
 *
 * Prompt caching treats `tools` as part of the prefix (providers serialize
 * tools before system/messages), so a reordered or differently-spelled schema
 * invalidates the whole prompt even when the tool set is unchanged. The chain
 * is deterministic today:
 *  - `AgentTools.makeAgentTools` appends definitions in a fixed literal order;
 *    `enabledToolNames` is only a membership filter (the canonical order is
 *    kept) and `find_tools` is appended last;
 *  - `parameters` are `mapOf`/`associate` (LinkedHashMap), so declaration order
 *    is preserved;
 *  - `toOpenAIJson`/`toAnthropicJson`/`toGeminiJson` iterate those maps and
 *    `org.json.JSONObject` preserves insertion order.
 *
 * These tests fail if someone starts feeding a HashSet/Map with unstable
 * iteration into the schema, or reorders the tool list.
 */
class ToolSchemaSerializationStabilityTest {

    @Test
    fun `the full tool schema is byte-identical across builds`() {
        val first = AgentTools.makeAgentTools().map { it.toOpenAIJson().toString() }
        val second = AgentTools.makeAgentTools().map { it.toOpenAIJson().toString() }
        assertTrue("tool list must not be empty", first.isNotEmpty())
        assertEquals(first, second)
    }

    @Test
    fun `the core tool list is byte-identical across builds`() {
        val first = AgentTools.makeAgentTools(includeLongTail = false)
            .map { it.name to it.toOpenAIJson().toString() }
        val second = AgentTools.makeAgentTools(includeLongTail = false)
            .map { it.name to it.toOpenAIJson().toString() }
        assertEquals(first, second)
        // find_tools is appended last, so enabling a long-tail tool later
        // cannot reshuffle the tools that were already cached.
        assertEquals("find_tools", first.last().first)
    }

    @Test
    fun `enabled long-tail tools keep the canonical order`() {
        val base = AgentTools.makeAgentTools(includeLongTail = false).map { it.name }
        val withOne = AgentTools.makeAgentTools(
            includeLongTail = false,
            enabledToolNames = setOf("execute_code"),
        ).map { it.name }
        // The newly enabled tool comes from the canonical list, not from the
        // caller's set iteration order.
        assertEquals(base.toSet() + "execute_code", withOne.toSet())
        assertTrue(withOne.indexOf("execute_code") < withOne.indexOf("find_tools"))
        assertEquals(base.last(), withOne.last())
    }

    @Test
    fun `parameter declaration order is preserved in the wire JSON`() {
        val tool = AgentToolDefinition(
            name = "demo",
            description = "order probe",
            parameters = linkedMapOf(
                "zebra" to AgentToolParam("string", "last alphabetically, first declared"),
                "alpha" to AgentToolParam("string", "first alphabetically, last declared"),
            ),
            required = listOf("zebra"),
        )
        val json = tool.toOpenAIJson().toString()
        assertTrue(
            "declaration order must survive serialization: $json",
            json.indexOf("\"zebra\"") < json.indexOf("\"alpha\""),
        )
        // Same object serialized twice must be identical.
        assertEquals(json, tool.toOpenAIJson().toString())
    }
}
