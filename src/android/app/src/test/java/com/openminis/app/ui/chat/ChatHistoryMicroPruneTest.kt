package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compaction-cascade] Tier-① micro-prune contract: old whitelisted tool
 * results are slimmed locally (no LLM, request-time projection only), recent
 * carriers stay verbatim, tool_use/tool_result pairing and part structure are
 * never touched, and the projection is idempotent. Tier-② threshold pin.
 */
class ChatHistoryMicroPruneTest {

    private fun userText(text: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
    )

    private fun toolCarrier(id: String, name: String, result: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(AgentContentPart.ToolResult(id = id, name = name, content = result)),
    )

    private fun bigResult(seed: Char, size: Int = 4_000) = seed.toString().repeat(size)

    private fun historyWith(count: Int, tool: String = "shell_execute", seed: Char = 'a'): List<LLMMessage> =
        List(count) { i ->
            toolCarrier("call_$i", tool, bigResult(seed + i))
        }

    private val overTrigger = usedTokens(windowRatio = 0.7)
    private val underTrigger = usedTokens(windowRatio = 0.5)

    /** usage/window pairs that sit above/below MICRO_PRUNE_TRIGGER_RATIO (0.6). */
    private fun usedTokens(windowRatio: Double, window: Long = 100_000): Long = (window * windowRatio).toLong()

    @Test
    fun `under the trigger the history passes through untouched`() {
        val history = historyWith(8)
        assertSame(history, microPruneOldToolResults(history, underTrigger, 100_000))
        assertSame(history, microPruneOldToolResults(history, 0, 100_000))
        assertSame(history, microPruneOldToolResults(history, overTrigger, 0))
    }

    @Test
    fun `over the trigger old results slim and recent stay verbatim`() {
        val history = historyWith(8)
        val pruned = microPruneOldToolResults(history, overTrigger, 100_000)
        // The newest MICRO_PRUNE_KEEP_RECENT (4) carriers stay verbatim.
        for (i in 0 until 8 - MICRO_PRUNE_KEEP_RECENT) {
            val tr = pruned[i].contentParts.filterIsInstance<AgentContentPart.ToolResult>().single()
            assertTrue("old result must be slimmed", tr.content.contains("micro-pruned"))
            assertEquals("structure preserved: id intact", "call_$i", tr.id)
            assertEquals("structure preserved: name intact", "shell_execute", tr.name)
        }
        for (i in (8 - MICRO_PRUNE_KEEP_RECENT) until 8) {
            val tr = pruned[i].contentParts.filterIsInstance<AgentContentPart.ToolResult>().single()
            assertFalse("recent result must stay verbatim", tr.content.contains("micro-pruned"))
            assertEquals(bigResult('a' + i), tr.content)
        }
    }

    @Test
    fun `non prunable tools are never slimmed`() {
        val history = historyWith(8, tool = "file_read")
        val pruned = microPruneOldToolResults(history, overTrigger, 100_000)
        for (msg in pruned) {
            val tr = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>().single()
            assertFalse("file_read anchors edit read-state — never slimmed", tr.content.contains("micro-pruned"))
        }
    }

    @Test
    fun `history alias reaches the same verdict as the canonical name`() {
        val history = historyWith(8, tool = "shell_exec")
        val pruned = microPruneOldToolResults(history, overTrigger, 100_000)
        val slimmed = pruned[0].contentParts.filterIsInstance<AgentContentPart.ToolResult>().single()
        assertTrue("shell_exec → shell_execute via ToolAliases", slimmed.content.contains("micro-pruned"))
    }

    @Test
    fun `projection is idempotent`() {
        val history = historyWith(8)
        val once = microPruneOldToolResults(history, overTrigger, 100_000)
        val twice = microPruneOldToolResults(once, overTrigger, 100_000)
        assertEquals(once, twice)
    }

    @Test
    fun `short results pass through unchanged`() {
        val history = listOf(
            userText("task"),
            toolCarrier("c0", "shell_execute", "tiny output"),
            toolCarrier("c1", "shell_execute", "tiny output 2"),
        )
        val pruned = microPruneOldToolResults(history, overTrigger, 100_000)
        val tr = pruned[1].contentParts.filterIsInstance<AgentContentPart.ToolResult>().single()
        assertEquals("tiny output", tr.content)
    }

    @Test
    fun `tier-2 threshold follows the ratio`() {
        // 0/unknown usage and unknown window never trigger.
        assertFalse(shouldProactivelyCompact(0, 100_000))
        assertFalse(shouldProactivelyCompact(50_000, 0))
        assertFalse(shouldProactivelyCompact(-1, 100_000))
        // 0.80 ratio boundary: exactly at it triggers, just under does not.
        assertTrue(shouldProactivelyCompact(80_000, 100_000))
        assertFalse(shouldProactivelyCompact(79_999, 100_000))
    }
}
