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
}
