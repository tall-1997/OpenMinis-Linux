package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#25] Pure core of the context-usage breakdown. Runs on the JVM test
 * classpath only: the estimator is [ContextBreakdownMath.sectionTokens]
 * (BPETokenizer's codepoint heuristic — no vocab loaded in unit tests), and
 * no Android type is touched. The tests pin the contract the sheet relies
 * on: blocks sum EXACTLY to the reported total, and that sum stays within
 * the documented ±10% tolerance of an independently measured whole.
 */
class ContextBreakdownTest {

    private val sections = listOf(
        ContextBlockKey.SYSTEM_TOOLS to
            "You are a capable assistant. Available tools:\n- shell_execute\n- file_read\n- file_write\n",
        ContextBlockKey.SKILLS_MCPS to
            "Installed skills: code-review, release-notes.\nMCP servers: none.\n",
        ContextBlockKey.MEMORY to
            "Memory system (currently ENABLED):\n- Project rules: keep functions small; comment in English.\n- Standing notes: ship on Fridays.\n",
        ContextBlockKey.RUNTIME_TAIL to
            "\n\nRuntime context:\n- Current date: 2026-11-12 (Asia/Shanghai)\n- Device language: en-US\n",
        ContextBlockKey.GOAL_PLAN to
            "<goal_context>\nContinue work toward the active goal.\n</goal_context>\n<plan_board>\n1. do it\n2. check it\n</plan_board>\n",
    )

    private val historyTokens = 4_321
    private val pendingAttachments = 1_500 * 2

    @Test
    fun `total is exactly the sum of the emitted blocks`() {
        val breakdown = ContextBreakdownMath.build(
            sections = sections,
            historyTokens = historyTokens,
            pendingAttachmentTokens = pendingAttachments,
            usedTokens = 10_000,
            windowTokens = 200_000,
            maxOutputTokens = 8_192,
        )
        assertEquals(
            "totalTokens must equal the sum of blocks (the sheet's percentages add to 100%)",
            breakdown.blocks.sumOf { it.tokens },
            breakdown.totalTokens,
        )
    }

    @Test
    fun `block sum stays within tolerance of an independently measured whole`() {
        val breakdown = ContextBreakdownMath.build(
            sections = sections,
            historyTokens = historyTokens,
            pendingAttachmentTokens = pendingAttachments,
            usedTokens = 10_000,
            windowTokens = 200_000,
            maxOutputTokens = 8_192,
        )
        // Measure the WHOLE payload in one pass instead of per block — the
        // tolerance claim ("±10% vs the real composition") is about this gap.
        val whole = ContextBreakdownMath.sectionTokens(sections.joinToString("\n\n") { it.second }) +
            historyTokens +
            pendingAttachments
        val gap = Math.abs(breakdown.totalTokens - whole)
        assertTrue(
            "expected |$breakdown.totalTokens - $whole| <= ${ContextBreakdownMath.ESTIMATE_TOLERANCE * whole}",
            gap <= whole * ContextBreakdownMath.ESTIMATE_TOLERANCE,
        )
    }

    @Test
    fun `every block carries a share that sums to one`() {
        val breakdown = ContextBreakdownMath.build(
            sections = sections,
            historyTokens = historyTokens,
            pendingAttachmentTokens = pendingAttachments,
            usedTokens = 0,
            windowTokens = 0,
            maxOutputTokens = 0,
        )
        assertTrue(breakdown.blocks.isNotEmpty())
        val shareSum = breakdown.blocks.sumOf { it.share.toDouble() }
        assertEquals(1.0, shareSum, 1e-6)
    }

    @Test
    fun `empty and blank inputs produce an empty breakdown with zero total`() {
        val breakdown = ContextBreakdownMath.build(
            sections = listOf(
                ContextBlockKey.SYSTEM_TOOLS to "",
                ContextBlockKey.MEMORY to "   \n  ",
            ),
            historyTokens = 0,
            pendingAttachmentTokens = 0,
            usedTokens = 0,
            windowTokens = 0,
            maxOutputTokens = 0,
        )
        assertTrue(breakdown.blocks.isEmpty())
        assertEquals(0, breakdown.totalTokens)
    }

    @Test
    fun `zero-value contributors are dropped but policy totals are kept`() {
        val breakdown = ContextBreakdownMath.build(
            sections = emptyList(),
            historyTokens = 0,
            pendingAttachmentTokens = 0,
            usedTokens = 9_877,
            windowTokens = 128_000,
            maxOutputTokens = 8_192,
        )
        assertTrue(breakdown.blocks.isEmpty())
        assertEquals(0, breakdown.totalTokens)
        assertEquals(9_877, breakdown.usedTokens)
        assertEquals(128_000, breakdown.windowTokens)
        assertEquals(8_192, breakdown.maxOutputTokens)
    }

    @Test
    fun `history and pending attachments land under their own keys`() {
        val breakdown = ContextBreakdownMath.build(
            sections = emptyList(),
            historyTokens = historyTokens,
            pendingAttachmentTokens = pendingAttachments,
            usedTokens = 0,
            windowTokens = 0,
            maxOutputTokens = 0,
        )
        assertEquals(
            listOf(ContextBlockKey.HISTORY, ContextBlockKey.PENDING_ATTACHMENTS),
            breakdown.blocks.map { it.key },
        )
        assertEquals(historyTokens + pendingAttachments, breakdown.totalTokens)
    }
}
