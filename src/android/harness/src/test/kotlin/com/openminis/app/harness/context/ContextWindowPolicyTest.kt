package com.openminis.app.harness.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-context-window-policy] 纯策略层验收：分桶估算的中文口径、巨型截断的头尾+
 * 标记+下限、图片剥离、字节治理的压缩方向与「压不动返回 null」。
 */
class ContextWindowPolicyTest {

    @Test
    fun `cjk punctuation is priced like cjk not like ascii punctuation`() {
        val cjkPunct = "，".repeat(100)
        val asciiPunct = ".".repeat(100)
        assertTrue(
            "full-width punctuation must not be under-priced: cjk=${ContextWindowPolicy.estimateTokens(cjkPunct)} ascii=${ContextWindowPolicy.estimateTokens(asciiPunct)}",
            ContextWindowPolicy.estimateTokens(cjkPunct) > ContextWindowPolicy.estimateTokens(asciiPunct),
        )
        assertEquals(0, ContextWindowPolicy.estimateTokens("   "))
    }

    @Test
    fun `under-budget turns pass through untouched`() {
        val turns = listOf(ContextWindowPolicy.ProjectedUserTurn("1", "short note", 0))
        assertEquals(turns, ContextWindowPolicy.truncateOversizedUserTurns(turns, 100_000))
    }

    @Test
    fun `giant user turn is truncated with head tail and pointer marker`() {
        val giant = (1..20_000).joinToString(" ") { "word$it" }
        val turns = listOf(
            ContextWindowPolicy.ProjectedUserTurn("small", "tiny", 0),
            ContextWindowPolicy.ProjectedUserTurn("giant", giant, 0),
        )
        val out = ContextWindowPolicy.truncateOversizedUserTurns(turns, 3_000)
        val truncatedText = out[1].text
        assertTrue(truncatedText.length < giant.length)
        assertTrue(truncatedText.contains("消息过长已截断"))
        assertTrue("head must survive", truncatedText.startsWith("word1 "))
        assertTrue("tail must survive", truncatedText.endsWith("word20000"))
        assertEquals("small turn untouched", "tiny", out[0].text)
    }

    @Test
    fun `images are stripped only when text truncation is not enough`() {
        val turns = listOf(ContextWindowPolicy.ProjectedUserTurn("pic", "caption", 4))
        val out = ContextWindowPolicy.truncateOversizedUserTurns(turns, 100)
        assertEquals(0, out.single().imageCount)
        assertTrue(out.single().text.contains("张图片因上下文预算已从模型上下文省略"))
    }

    @Test
    fun `fitUserText gives up instead of returning a longer string`() {
        assertNull(ContextWindowPolicy.fitUserText("short", 10_000))
    }

    @Test
    fun `byte budget compaction keeps the tail for command output`() {
        val output = (1..3000).joinToString("\n") { "step $it" } + "\npanic: boom at the end"
        val compacted = ContextWindowPolicy.compactToolOutputForByteBudget("shell_execute", output)!!
        assertTrue(compacted.contains("panic: boom at the end"))
        assertTrue(compacted.contains("请求体体积限制已压缩"))
        assertTrue(compacted.length < output.length)
    }

    @Test
    fun `short outputs are not compacted`() {
        assertNull(ContextWindowPolicy.compactToolOutputForByteBudget("shell_execute", "all good"))
    }
}
