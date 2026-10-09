package com.openminis.app.harness.agent

import com.openminis.app.harness.runtime.RoundStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-seam-extraction] 循环片段二（工具轮结果处理）验收：终局裁定表、
 * 内容合并三语义（T263 尾裁 / 直播取长 / 子代理摘要）、截断注记、部件与消息构造。
 */
class ToolRoundOutcomeTest {

    @Test
    fun `status decision table keeps the historical precedence`() {
        // 取消 > 截断修复 > 成败 > 超时
        assertEquals(RoundStatus.CANCELLED, ToolRoundOutcome.decideStatus(true, false, true, true))
        assertEquals(RoundStatus.FAILED, ToolRoundOutcome.decideStatus(true, false, true, false))
        assertEquals(RoundStatus.SUCCESS, ToolRoundOutcome.decideStatus(true, false, false, false))
        assertEquals(RoundStatus.TIMEOUT, ToolRoundOutcome.decideStatus(false, true, false, false))
        assertEquals(RoundStatus.FAILED, ToolRoundOutcome.decideStatus(false, false, false, false))
    }

    @Test
    fun `shell_execute keeps only the tail 80 lines, others keep the banner`() {
        val long = (1..200).joinToString("\n") { "line$it" }
        val tail = ToolRoundOutcome.blockContent("shell_execute", long, "", 0)
        assertEquals(80, tail.lines().size)
        assertEquals("line200", tail.lines().last())
        // file_read 的首行横幅不裁
        val banner = "[path | 10 bytes | 5 lines | showing 1-5 of 5]\nbody"
        assertEquals(banner, ToolRoundOutcome.blockContent("file_read", banner, "", 0))
    }

    @Test
    fun `longer live content wins over the truncated result`() {
        assertEquals("live-streamed", ToolRoundOutcome.blockContent("file_read", "short", "live-streamed", 0))
        assertEquals("result", ToolRoundOutcome.blockContent("file_read", "result", "live", 0))
    }

    @Test
    fun `sub-agent waves collapse to a dispatch summary`() {
        assertEquals("已分发 3 个子代理，点开各自卡片查看当前运行。", ToolRoundOutcome.blockContent("spawn_agent", "x", "y", 3))
    }

    @Test
    fun `truncation note is appended only when repaired`() {
        assertEquals("out", ToolRoundOutcome.withTruncationNote("out", null))
        val noted = ToolRoundOutcome.withTruncationNote("out", "balanced")
        assertEquals("out", noted.take(3))
        assertTrue(noted.contains("repair strategy: balanced"))
        assertTrue(noted.contains("<system-reminder>"))
    }

    @Test
    fun `tool result part and message carry the round fields`() {
        val part = ToolRoundOutcome.toolResultPart("call-1", "file_read", "spilled", true)
        assertEquals("call-1", part.id)
        assertEquals("file_read", part.name)
        assertTrue(part.isError)
        assertEquals("spilled", part.content)
        val message = ToolRoundOutcome.toolResultMessage(listOf(part), "42")
        assertEquals(com.openminis.app.data.model.LLMMessage.Role.USER, message.role)
        assertEquals("42", message.dbMessageId)
        assertEquals(1, message.contentParts.size)
    }
}
