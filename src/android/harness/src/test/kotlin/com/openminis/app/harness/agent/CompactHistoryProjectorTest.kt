package com.openminis.app.harness.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-loop-context-cut] 上下文治理投影验收：三路切片、回走边界、
 * preAnchor 剪枝、角色对齐、摘要内联、配对修复。
 */
class CompactHistoryProjectorTest {

    private fun user(id: String? = null, text: String = "u") =
        LLMMessage(role = LLMMessage.Role.USER, content = text, dbMessageId = id)

    private fun assistant(id: String? = null, vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList(), dbMessageId = id)

    private fun toolUse(id: String) = AgentContentPart.ToolUse(id, "shell_execute", org.json.JSONObject("""{"command":"ls"}"""))
    private fun toolResult(id: String, content: String = "ok") =
        AgentContentPart.ToolResult(id, "shell_execute", content, isError = false)

    @Test
    fun `no summary or marker returns full history untouched`() {
        val history = listOf(user("1"), assistant("2"))
        assertTrue(CompactHistoryProjector.project(history, null, null, 60, 3) === history ||
            CompactHistoryProjector.project(history, null, null, 60, 3) == history)
        assertTrue(CompactHistoryProjector.project(history, "s", null, 60, 3) == history)
    }

    @Test
    fun `v2 anchor slices preanchor walkback and injects summary into first post-anchor user`() {
        // h0..h4 pre-anchor, h4 = anchor(被压缩的末条), h5 assistant, h6 user(新指令)
        val h = listOf(
            user("m0", "旧指令0"),
            assistant("m1", toolUse("t1"), toolUse("t2")),
            user("m2", ""),  // tool result 轮
            user("m3", "旧指令3"),
            assistant("m4"),  // anchor
            assistant("m5"),
            user("m6", "新指令"),
        )
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "m4")
        val out = CompactHistoryProjector.project(h, "SUMMARY", marker, 60, 3)
        // 摘要必须内联进锚后首个 user（h6），不是独立消息
        val injected = out.lastOrNull { it.role == LLMMessage.Role.USER }
        assertNotNull(injected)
        assertTrue(injected!!.content.contains("<context-summary>"))
        assertTrue(injected.content.contains("SUMMARY"))
        assertTrue(injected.content.contains("新指令"))
        // 首条必须是 user（角色对齐）
        assertEquals(LLMMessage.Role.USER, out.first().role)
        // 锚后 assistant 保留
        assertTrue(out.any { it.dbMessageId == "m5" })
    }

    @Test
    fun `v2 preanchor prune drops oversized tool results with paired uses`() {
        val big = "x".repeat(1500)
        val h = listOf(
            user("m0", "q"),
            assistant("m1", toolUse("t1"), toolUse("t2")),
            user("m2", ""),  // results
            assistant("m3"),  // anchor
            user("m4", "new"),
        )
        // 修正：tool results 放进 user 消息的 parts
        val h2 = listOf(
            h[0],
            h[1],
            LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = listOf(toolResult("t1", big), toolResult("t2", "ok"))),
            assistant("m3"),
            user("m4", "new"),
        )
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "m3")
        val out = CompactHistoryProjector.project(h2, "S", marker, 60, 3)
        // >1000 字符的 t1 被剪掉，配对的 tool_use 也被剥；t2 保留
        val flat = out.flatMap { it.contentParts }
        assertTrue(flat.none { it is AgentContentPart.ToolResult && it.id == "t1" })
        assertTrue(flat.none { it is AgentContentPart.ToolUse && it.id == "t1" })
        assertTrue(flat.any { it is AgentContentPart.ToolResult && it.id == "t2" })
    }

    @Test
    fun `v2 detached anchor degrades to summary plus verbatim tail`() {
        val h = (0 until 80).map { user("m$it", "msg$it") }
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "gone")
        val out = CompactHistoryProjector.project(h, "S", marker, detachedTailSize = 60, keepRecentUserTurns = 3)
        // 尾部 60 条逐字 + 摘要内联进首个 user
        assertEquals(60, out.size)
        assertTrue(out.first().content.contains("<context-summary>"))
        assertEquals("msg20", out.first().content.substringAfterLast("\n\n"))
    }

    @Test
    fun `v1 legacy marker keeps summary head plus slice from first kept`() {
        val h = listOf(user("m0"), user("m1"), user("m2"))
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 1, firstKeptMessageId = "m1")
        val out = CompactHistoryProjector.project(h, "S", marker, 60, 3)
        assertEquals(3, out.size) // summaryHead + m1 + m2
        assertEquals(LLMMessage.Role.USER, out[0].role)
        assertTrue(out[0].content.contains("S"))
        assertEquals("m1", out[1].dbMessageId)
    }

    @Test
    fun `walkback stops at user text target and never splits a tool round`() {
        // anchor=4；user 文本轮：m3、m0（m2 是 tool result 轮不算轮头）
        val h = listOf(
            user("m0", "turn1"),
            assistant("m1"),
            LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = listOf(toolResult("t1"))),
            user("m3", "turn2"),
            assistant("m4"),
        )
        val wb = CompactHistoryProjector.walkBackUserTurnsBounded(h, anchorIdx = 4, maxUserTextTurns = 2, maxMessages = 100)
        assertEquals("userTextTargetMet", wb.stopReason)
        assertEquals(0, wb.priorIdx) // m0 与 m3 两轮都收进来
        // cap：maxMessages=1 时连首个候选（2 条）都装不下 → 空 preAnchor
        val wb2 = CompactHistoryProjector.walkBackUserTurnsBounded(h, anchorIdx = 4, maxUserTextTurns = 2, maxMessages = 1)
        assertEquals("messageCapWouldExceed", wb2.stopReason)
        assertNull(wb2.priorIdx)
    }

    @Test
    fun `tool round pairing drops dangling results and repairs unanswered uses`() {
        // use t1 无 result；result t2 无 use（悬空）；t3 配对正常
        val h = listOf(
            user("m0", "q"),
            assistant("m1", toolUse("t1"), toolUse("t3")),
            LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = listOf(toolResult("t2"), toolResult("t3"))),
            user("m4", "next"),
        )
        val out = com.openminis.app.harness.agent.ToolRoundPairing.dropOrphanedToolParts(h)
        val flat = out.flatMap { it.contentParts }
        // 悬空 t2 被丢
        assertTrue(flat.none { it is AgentContentPart.ToolResult && it.id == "t2" })
        // 未应答 t1 补了错误占位 result
        val repaired = flat.filterIsInstance<AgentContentPart.ToolResult>().first { it.id == "t1" }
        assertTrue(repaired.isError)
        // t3 配对保留
        assertTrue(flat.any { it is AgentContentPart.ToolUse && it.id == "t3" })
        assertTrue(flat.any { it is AgentContentPart.ToolResult && it.id == "t3" })
    }

    @Test
    fun `tail assistant awaiting execution is exempt from repair`() {
        val h = listOf(
            user("m0", "q"),
            assistant("m1"), // 尾部在飞轮：use 未应答是合法的
        )
        val h2 = listOf(
            h[0],
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(toolUse("t9")), dbMessageId = "m1"),
        )
        val out = com.openminis.app.harness.agent.ToolRoundPairing.dropOrphanedToolParts(h2)
        // 不补占位、不丢 use
        assertTrue(out.flatMap { it.contentParts }.any { it is AgentContentPart.ToolUse && it.id == "t9" })
        assertEquals(2, out.size)
    }
}
