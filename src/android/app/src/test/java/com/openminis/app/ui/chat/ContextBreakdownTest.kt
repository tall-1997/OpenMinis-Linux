package com.openminis.app.ui.chat

import com.openminis.app.data.ContextTokenEstimate
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.api.ChatRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the estimated composition separately from the verified request total. */
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

    @Test
    fun `projection supplies the total window and allowance while blocks stay on the raw ruler`() {
        val system = "Use only the listed tools."
        val tools = listOf(AgentToolDefinition("lookup", "Find an item", emptyMap()))
        val image = AgentContentPart.ImageData(byteArrayOf(1), "image/png")
        val tail = LLMMessage(
            LLMMessage.Role.USER, "keep this", contentParts = listOf(AgentContentPart.Text("keep this"), image),
        )
        val request = ChatRequest(listOf(tail), system, maxTokens = 0, tools = tools)
        val estimate = ContextTokenEstimate(tokens = 4_800, measuredPrefixTokens = 3_250)
        val breakdown = ContextBreakdownMath.forRequest(
            sections = listOf(ContextBlockKey.SYSTEM_TOOLS to system), request, estimate,
            windowTokens = 10_000, maxOutputTokens = 5_200, pendingAttachmentTokens = 1_500,
        )
        val blocks = breakdown.blocks.associateBy { it.key }
        val base = ContextTokenMeter.rawRequestTokens(emptyList(), system, tools)
        assertEquals(base, blocks.getValue(ContextBlockKey.SYSTEM_TOOLS).tokens)
        assertEquals(1_503, blocks.getValue(ContextBlockKey.HISTORY).tokens)
        assertEquals(1_500, blocks.getValue(ContextBlockKey.PENDING_ATTACHMENTS).tokens)
        assertEquals(base + 3_003, breakdown.totalTokens)
        assertEquals(4_800L, breakdown.contextUsage.usedTokens)
        assertEquals(3_250L, breakdown.contextUsage.measuredPrefixTokens)
        assertEquals(10_000L, breakdown.contextUsage.windowTokens)
        assertEquals(5_200, breakdown.maxOutputTokens)
    }

    @Test
    fun `output allowance never exceeds known remaining context even below the old floor`() {
        assertEquals(1_000, projectedOutputAllowance(8_192, 31_000, 32_000))
        assertEquals(1, projectedOutputAllowance(8_192, 31_999, 32_000))
        assertEquals(0, projectedOutputAllowance(8_192, 32_000, 32_000))
        assertEquals(0, projectedOutputAllowance(8_192, Int.MAX_VALUE, 32_000))
    }

    @Test
    fun `output allowance retains the model ceiling when the window is unknown or has spare room`() {
        assertEquals(8_192, projectedOutputAllowance(8_192, 31_000, 0))
        assertEquals(8_192, projectedOutputAllowance(8_192, 1_000, 32_000))
        assertEquals(4_000, projectedOutputAllowance(8_192, -1, 4_000))
    }

    @Test
    fun `breakdown uses projected provider head and refreshed tail`() {
        val head = "System and tools"
        val standing = "Standing memory"
        val stable = "$head\n\n$standing"
        val tail = "\n\nFresh daily memory"
        val prefix = "Provider identification\n\n"
        val sections = listOf(
            ContextBlockKey.SYSTEM_TOOLS to head,
            ContextBlockKey.MEMORY to standing,
            ContextBlockKey.RUNTIME_TAIL to "\n\nOld daily memory",
        )
        val assembled = MemoryDeliveryPrompt("$stable\n\nOld daily memory", daily = null, stablePrefixLen = stable.length)
        val projected = MemoryDeliveryPrompt(prefix + stable + tail, daily = null, stablePrefixLen = prefix.length + stable.length)
        assertEquals(
            listOf(ContextBlockKey.SYSTEM_TOOLS to prefix + head, ContextBlockKey.MEMORY to standing, ContextBlockKey.RUNTIME_TAIL to tail),
            projectedContextSections(sections, assembled, projected),
        )
        val rewritten = projected.copy(prompt = "Entirely new system", stablePrefixLen = -1)
        assertEquals(listOf(ContextBlockKey.SYSTEM_TOOLS to rewritten.prompt), projectedContextSections(sections, assembled, rewritten))
    }
}
