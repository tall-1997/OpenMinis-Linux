package com.openminis.app.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话在不同 API 类型之间切换后，历史里另一种 API 的工具 ID 不能导致请求失败。
 * OpenAI Responses 的 id 含 `|`，Anthropic 只接受字母数字、下划线、连字符。
 * tool_use 与它配对的 tool_result 必须被清洗成同一个 id。
 */
class ToolIdNormalizationTest {

    @Test
    fun `pipe separated ids become acceptable to every api`() {
        assertEquals("call_abc-fc_def", sanitizeToolId("call_abc|fc_def"))
    }

    @Test
    fun `already clean ids are unchanged`() {
        assertEquals("toolu_01abc", sanitizeToolId("toolu_01abc"))
    }

    @Test
    fun `paired tool use and tool result share one sanitized id`() {
        val history = listOf(
            listOf(AgentContentPart.ToolUse("call_abc|fc_def", "shell", JSONObject())),
            listOf(AgentContentPart.ToolResult("call_abc|fc_def", "shell", "ok")),
        )
        val normalized = ToolIdNormalization.normalize(history)
        val useId = (normalized[0].single() as AgentContentPart.ToolUse).id
        val resultId = (normalized[1].single() as AgentContentPart.ToolResult).id
        assertEquals("call_abc-fc_def", useId)
        assertEquals(useId, resultId)
    }

    @Test
    fun `ids that collide after sanitizing stay unique and still paired`() {
        val history = listOf(
            listOf(
                AgentContentPart.ToolUse("call_a|b", "shell", JSONObject()),
                AgentContentPart.ToolUse("call_a-b", "file", JSONObject()),
            ),
            listOf(
                AgentContentPart.ToolResult("call_a|b", "shell", "1"),
                AgentContentPart.ToolResult("call_a-b", "file", "2"),
            ),
        )
        val normalized = ToolIdNormalization.normalize(history)
        val useIds = normalized[0].filterIsInstance<AgentContentPart.ToolUse>().map { it.id }
        val resultIds = normalized[1].filterIsInstance<AgentContentPart.ToolResult>().map { it.id }
        assertEquals(useIds, resultIds)
        assertEquals(2, useIds.distinct().size)
    }

    @Test
    fun `non tool parts pass through untouched`() {
        val history = listOf(listOf(AgentContentPart.Text("hello")))
        val normalized = ToolIdNormalization.normalize(history)
        assertEquals("hello", (normalized.single().single() as AgentContentPart.Text).text)
    }

    @Test
    fun `combined and bare forms of one call normalize to a single id`() {
        // Model switch / DB reload can leave the call stored combined and the
        // result bare (or vice versa). They are one logical call: independent
        // assignment would split the repaired pair again.
        val history = listOf(
            listOf(AgentContentPart.ToolUse("call_abc|fc_def", "shell", JSONObject())),
            listOf(AgentContentPart.ToolResult("call_abc", "shell", "ok")),
        )
        val normalized = ToolIdNormalization.normalize(history)
        val useId = (normalized[0].single() as AgentContentPart.ToolUse).id
        val resultId = (normalized[1].single() as AgentContentPart.ToolResult).id
        assertEquals(useId, resultId)
        val again = ToolIdNormalization.normalize(normalized)
        assertEquals(normalized, again)
    }

    @Test
    fun `bare id without its combined complement keeps its own identity`() {
        val history = listOf(
            listOf(AgentContentPart.ToolUse("call_abc", "shell", JSONObject())),
            listOf(AgentContentPart.ToolResult("call_abc", "shell", "ok")),
        )
        val normalized = ToolIdNormalization.normalize(history)
        assertEquals("call_abc", (normalized[0].single() as AgentContentPart.ToolUse).id)
        assertEquals("call_abc", (normalized[1].single() as AgentContentPart.ToolResult).id)
    }

    @Test
    fun `blank tool ids pass through without crashing request assembly`() {
        // Relay gateways can emit tool_calls[].id=""; a persisted blank id must
        // not throw for every later request in the session.
        val history = listOf(
            listOf(AgentContentPart.ToolUse("", "shell", JSONObject())),
            listOf(AgentContentPart.ToolResult("", "shell", "ok")),
            listOf(AgentContentPart.ToolUse("call_ok", "file", JSONObject())),
        )
        val normalized = ToolIdNormalization.normalize(history)
        assertEquals("", (normalized[0].single() as AgentContentPart.ToolUse).id)
        assertEquals("call_ok", (normalized[2].single() as AgentContentPart.ToolUse).id)
    }

    @Test
    fun `a bare call id colliding with a combined id stays a distinct call`() {
        // A bare `call_a` that is a DIFFERENT call (not the split-carrier
        // complement of `call_a|fc_1`) must not merge with it: two tool_uses
        // would otherwise share one id and providers reject that with a 400.
        val history = listOf(
            listOf(
                AgentContentPart.ToolUse("call_a|fc_1", "shell", JSONObject()),
                AgentContentPart.ToolUse("call_a", "file", JSONObject()),
            ),
            listOf(
                AgentContentPart.ToolResult("call_a|fc_1", "shell", "one"),
                AgentContentPart.ToolResult("call_a", "file", "two"),
            ),
        )
        val normalized = ToolIdNormalization.normalize(history)
        val useIds = normalized[0].filterIsInstance<AgentContentPart.ToolUse>().map { it.id }
        val resultIds = normalized[1].filterIsInstance<AgentContentPart.ToolResult>().map { it.id }
        assertEquals(2, useIds.distinct().size)
        assertEquals(useIds, resultIds)
    }
}
