package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-recovery-layer] 恢复判定的纯函数验收：planner 的 Stubbed / Replay 分流
 * 在宿主部件模型上是否成立。执行与落盘不在此覆盖（需 ViewModel）。
 */
class ChatViewModelRecoveryExtTest {

    private fun use(id: String, name: String) =
        AgentContentPart.ToolUse(id = id, name = name, input = JSONObject())

    private fun result(id: String, name: String, content: String = "ok") =
        AgentContentPart.ToolResult(id = id, name = name, content = content)

    private fun assistant(vararg parts: AgentContentPart) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = parts.toList(),
    )

    @Test
    fun `dangling write tool is stubbed never replayed`() {
        val history = listOf(assistant(use("c1", "shell_execute")))

        val notes = stubNotesFor(history)
        assertEquals(listOf("c1"), notes.keys.toList())
        assertTrue("crash note expected: ${notes["c1"]}", notes["c1"]!!.contains("进程中断"))

        assertTrue(replayableUses(history).isEmpty())
    }

    @Test
    fun `dangling read tool is planned for replay not stubbed`() {
        val history = listOf(assistant(use("c1", "file_read")))

        assertTrue(stubNotesFor(history).isEmpty())
        val replays = replayableUses(history)
        assertEquals(listOf("c1"), replays.map { it.callId })
        assertEquals("file_read", replays.single().toolName)
        assertEquals(0, replays.single().messageIndex)
    }

    @Test
    fun `mcp tools are never replayable`() {
        val history = listOf(assistant(use("c1", "mcp__ctx__resolve")))

        assertEquals(listOf("c1"), stubNotesFor(history).keys.toList())
        assertTrue(replayableUses(history).isEmpty())
    }

    @Test
    fun `answered pairs produce no plan at all`() {
        val history = listOf(
            assistant(use("c1", "shell_execute"), use("c2", "file_read")),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(result("c1", "shell_execute"), result("c2", "file_read")),
            ),
        )

        assertTrue(stubNotesFor(history).isEmpty())
        assertTrue(replayableUses(history).isEmpty())
    }

    @Test
    fun `mixed history splits by tool safety`() {
        val history = listOf(
            assistant(use("w1", "file_write"), use("r1", "search_sessions")),
        )

        assertEquals(listOf("w1"), stubNotesFor(history).keys.toList())
        assertEquals(listOf("r1"), replayableUses(history).map { it.callId })
    }

    @Test
    fun `projection keeps call and result ids linkable`() {
        val history = listOf(assistant(use("c1", "file_read")), )
        val messages = toolMessagesOf(history)
        assertEquals(1, messages.size)
        val withResult = toolMessagesOf(
            listOf(assistant(use("c1", "file_read")), LLMMessage(
                role = LLMMessage.Role.USER, content = "", contentParts = listOf(result("c1", "file_read")),
            )),
        )
        assertEquals(2, withResult.size)
        val call = withResult[0] as com.openminis.app.harness.ToolCall
        val res = withResult[1] as com.openminis.app.harness.ToolResult
        assertEquals(call.id, res.toolCallId)
        assertEquals(messages.size, 1)
    }

    // ─── [T-p1-5-recovery-user-turn] 恢复链端到端（纯函数级）：结果必须落 USER 轮 ───

    @Test
    fun `replayed results land in a USER message after the assistant turn`() {
        val history = listOf(assistant(use("c1", "file_read")))

        val uses = replayableUses(history)
        assertEquals(listOf("c1"), uses.map { it.callId })
        val results = uses.associate { it.messageIndex to listOf(result("c1", "file_read", "file body")) }
        val updated = insertRecoveryToolResults(history, results)

        assertEquals(2, updated.size)
        val assistantMessage = updated[0]
        assertEquals(LLMMessage.Role.ASSISTANT, assistantMessage.role)
        assertTrue(
            "assistant 轮不得携带 tool_result（Anthropic 协议要求其在 user 轮）",
            assistantMessage.contentParts.none { it is AgentContentPart.ToolResult },
        )
        val resultMessage = updated[1]
        assertEquals(LLMMessage.Role.USER, resultMessage.role)
        val part = resultMessage.contentParts.single() as AgentContentPart.ToolResult
        assertEquals("c1", part.id)
        assertEquals("file body", part.content)
    }

    @Test
    fun `two replayable calls on one assistant message share one user result message`() {
        val history = listOf(assistant(use("r1", "file_read"), use("r2", "search_sessions")))

        val uses = replayableUses(history)
        assertEquals(setOf("r1", "r2"), uses.map { it.callId }.toSet())
        // groupBy（不是 associate）：同一条 assistant 消息上的多个重放结果必须
        // 聚合进同一条 user 结果消息，associate 会把同 key 的前一条静默挤掉。
        val results = uses.groupBy({ it.messageIndex }, { result(it.callId, it.toolName) })
        val updated = insertRecoveryToolResults(history, results)

        assertEquals(2, updated.size)
        val resultParts = updated[1].contentParts.filterIsInstance<AgentContentPart.ToolResult>()
        assertEquals(listOf("r1", "r2"), resultParts.map { it.id })
    }

    @Test
    fun `descending insertion keeps earlier message indexes valid across multiple turns`() {
        val history = listOf(
            assistant(use("a1", "file_read")),
            assistant(use("a2", "read_session")),
        )

        val uses = replayableUses(history)
        assertEquals(listOf(0, 1), uses.map { it.messageIndex })
        val results = uses.associate { it.messageIndex to listOf(result(it.callId, it.toolName)) }
        val updated = insertRecoveryToolResults(history, results)

        assertEquals(4, updated.size)
        // 每条 assistant 后紧跟一条 user 结果消息，tool_use ↔ tool_result 成对相邻
        for (i in updated.indices step 2) {
            assertEquals(LLMMessage.Role.ASSISTANT, updated[i].role)
            assertEquals(LLMMessage.Role.USER, updated[i + 1].role)
            val callId = (updated[i].contentParts.single() as AgentContentPart.ToolUse).id
            val resultId = (updated[i + 1].contentParts.single() as AgentContentPart.ToolResult).id
            assertEquals(callId, resultId)
        }
    }

    @Test
    fun `out of range indexes are skipped and no result is lost silently in range`() {
        val history = listOf(assistant(use("c1", "file_read")))

        val updated = insertRecoveryToolResults(
            history,
            mapOf(99 to listOf(result("c1", "file_read")), 0 to listOf(result("c1", "file_read"))),
        )

        assertEquals(2, updated.size)
        assertEquals(1, updated[1].contentParts.size)
    }

    @Test
    fun `empty results map returns history unchanged`() {
        val history = listOf(assistant(use("c1", "file_read")))
        assertEquals(history, insertRecoveryToolResults(history, emptyMap()))
    }
}
