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
}
