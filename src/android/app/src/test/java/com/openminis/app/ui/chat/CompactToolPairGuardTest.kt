package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-preserves-tool-pairs] Compact boundaries must never split a
 * tool_use/tool_result pair: a dangling tool_result half at the kept-tail
 * start (or a result summarized without its call) is a conversation shape
 * schema-first providers reject outright.
 */
class CompactToolPairGuardTest {

    private fun assistantToolUse(id: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolUse(
                id = id,
                name = "shell_execute",
                input = org.json.JSONObject("{}"),
            ),
        ),
    )

    private fun userToolResult(id: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(AgentContentPart.ToolResult(id = id, name = "shell_execute", content = "out")),
    )

    private fun userText(text: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
    )

    @Test
    fun `kept tail opening on a tool result pulls the anchor back`() {
        // [pair0: use+result][pair1: use+result][user text] — compacting
        // through pair1's RESULT would orphan it; the anchor pulls back so
        // pair1 stays kept whole.
        val history = listOf(
            assistantToolUse("u0"), userToolResult("u0"),
            assistantToolUse("u1"), userToolResult("u1"),
            userText("next"),
        )
        // Anchor originally on the last carrier (u1's result, idx 3): kept tail
        // would open on userText ✓ — no pull needed here. Force the bad shape:
        val (s, a) = preserveToolPairsAtBoundaries(history, 0, 2)
        // anchorIdx=2 means kept tail = [u1-result, next] → opens on a result
        // → anchor must pull back to 1 (kept tail opens on the tool_use).
        assertEquals(1, a)
        assertEquals(0, s)
    }

    @Test
    fun `slice opening on a tool result pulls the start back`() {
        // Previous compact ended between the pair: the new slice would start
        // on u1's result while u1's use sits in the previous summary.
        val history = listOf(
            userText("old"),
            assistantToolUse("u1"), userToolResult("u1"),
            userText("next"),
        )
        val (s, a) = preserveToolPairsAtBoundaries(history, 2, 3)
        // slice start 2 sits on a result → pulled back to 1 (the paired use).
        assertEquals(1, s)
        assertEquals(3, a)
    }

    @Test
    fun `clean boundaries pass through unchanged`() {
        val history = listOf(
            userText("turn 1"),
            assistantToolUse("u0"), userToolResult("u0"),
            userText("turn 2"),
        )
        val (s, a) = preserveToolPairsAtBoundaries(history, 0, 3)
        assertEquals(0, s)
        assertEquals(3, a)
    }

    @Test
    fun `anchor walk never empties the range`() {
        // Everything is tool carriers: the anchor walk stops at start (a > s
        // condition) — the range can never become empty.
        val history = listOf(
            assistantToolUse("u0"), userToolResult("u0"),
            assistantToolUse("u1"), userToolResult("u1"),
        )
        val (s, a) = preserveToolPairsAtBoundaries(history, 0, 3)
        assertTrue("range stays non-empty", a >= s)
        assertEquals(0, s)
    }
}
