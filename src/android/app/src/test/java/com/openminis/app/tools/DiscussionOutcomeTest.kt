package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-discussion-outcome] A split discussion must end as one answerable question,
 * not as a summary that reads like agreement.
 */
class DiscussionOutcomeTest {

    private fun line(speaker: String, text: String) = GroupChat.Line(speaker, text)

    @Test
    fun plainDiscussionHasNothingToAsk() {
        val outcome = DiscussionOutcome.of(
            listOf(
                line("工程师", "接口按现有风格实现即可。"),
                line("测试工程师", "我会补一组边界用例。"),
            ),
        )
        assertFalse(outcome.hasDisagreement)
        assertEquals("", outcome.question)
    }

    @Test
    fun anExplicitObjectionBecomesAQuestion() {
        val outcome = DiscussionOutcome.of(
            listOf(
                line("工程师", "方案 A 结构更简单。"),
                line("测试工程师", "VERDICT: OBJECT\nOBJECTION: 方案 A 无法覆盖离线场景"),
            ),
        )
        assertTrue(outcome.hasDisagreement)
        assertEquals(1, outcome.objections.size)
        assertTrue(outcome.question.contains("测试工程师"))
        assertTrue(outcome.question.contains("离线场景"))
        assertEquals(1, outcome.question.count { it == '?' })
    }

    @Test
    fun severalObjectionsNameEachRole() {
        val outcome = DiscussionOutcome.of(
            listOf(
                line("工程师", "VERDICT: OBJECT\nOBJECTION: 依赖太重"),
                line("测试工程师", "VERDICT: OBJECT\nOBJECTION: 没有回滚路径"),
            ),
        )
        assertTrue(outcome.hasDisagreement)
        assertEquals(2, outcome.objections.size)
        assertTrue(outcome.question.contains("依赖太重"))
        assertTrue(outcome.question.contains("没有回滚路径"))
        assertTrue(outcome.question.contains("did not converge"))
    }

    @Test
    fun aLaterAcceptClosesTheObjection() {
        val outcome = DiscussionOutcome.of(
            listOf(
                line("工程师", "VERDICT: OBJECT\nOBJECTION: 依赖太重"),
                line("工程师", "VERDICT: ACCEPT\n已把依赖换成内置实现。"),
            ),
        )
        assertFalse(outcome.hasDisagreement)
    }

    @Test
    fun theHostIsNotAReviewer() {
        val outcome = DiscussionOutcome.of(
            listOf(
                line("Host", "VERDICT: OBJECT\nOBJECTION: 主持人不该投票"),
                line("工程师", "实现完成。"),
            ),
            host = "Host",
        )
        assertFalse(outcome.hasDisagreement)
    }

    @Test
    fun longObjectionsAreClipped() {
        val long = "x".repeat(DiscussionOutcome.MAX_OBJECTION_CHARS + 50)
        val outcome = DiscussionOutcome.of(listOf(line("工程师", "VERDICT: OBJECT\nOBJECTION: $long")))
        assertTrue(outcome.question.length < 500)
        assertTrue(outcome.question.contains("x".repeat(DiscussionOutcome.MAX_OBJECTION_CHARS)))
    }

    @Test
    fun extraObjectionsAreCountedNotListed() {
        val outcome = DiscussionOutcome.of(
            (1..5).map { line("评审$it", "VERDICT: OBJECT\nOBJECTION: 意见 $it") },
        )
        assertTrue(outcome.question.contains("and 2 more"))
    }
}
