package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-cache-turn-context] The per-turn context must ride on the LAST
 * user-text message, not the system prompt. These tests pin the placement and
 * the idempotency: a per-turn block in the system prompt sits before the whole
 * conversation and invalidates every provider's prefix cache on every turn.
 */
class TurnContextInjectionTest {

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)

    private fun assistant(text: String) = LLMMessage(LLMMessage.Role.ASSISTANT, text)

    private fun toolResultUser(id: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolResult(id = id, name = "shell_execute", content = "ok"),
        ),
    )

    @Test
    fun `attaches context to the last user text turn only`() {
        val history = listOf(user("first"), assistant("a1"), user("second"), assistant("a2"))
        val out = applyTurnContext(history, "recall: alpha")

        assertTrue(out[0].content == "first")
        assertEquals("first", out[0].content)
        val last = out[2]
        assertTrue(last.content.startsWith("second"))
        assertTrue(last.content.contains(TURN_CONTEXT_OPEN))
        assertTrue(last.content.contains("recall: alpha"))
        assertTrue(last.content.endsWith(TURN_CONTEXT_CLOSE))
        // Assistant rows untouched.
        assertEquals("a2", out[3].content)
        // Content parts carry the same block for providers that serialize parts.
        val tail = last.contentParts.last() as AgentContentPart.Text
        assertTrue(tail.text.contains(TURN_CONTEXT_OPEN))
    }

    @Test
    fun `skips tool-result-only user rows and lands on the real prompt`() {
        val history = listOf(
            user("question"),
            assistant("calling tool"),
            toolResultUser("call_1"),
        )
        val out = applyTurnContext(history, "ctx")
        // The tool-result row is last but is not a user-text turn: the context
        // belongs next to the instruction, not a tool payload.
        assertFalse(out[2].content.contains(TURN_CONTEXT_OPEN))
        assertTrue(out[0].content.contains(TURN_CONTEXT_OPEN))
    }

    @Test
    fun `blank context leaves the list untouched`() {
        val history = listOf(user("q"), assistant("a"))
        assertSame(history, applyTurnContext(history, null))
        assertSame(history, applyTurnContext(history, ""))
        assertSame(history, applyTurnContext(history, "   "))
    }

    @Test
    fun `no user turn means no injection`() {
        val history = listOf(assistant("a"))
        assertSame(history, applyTurnContext(history, "ctx"))
    }

    @Test
    fun `injection is idempotent`() {
        val history = listOf(user("q"), assistant("a"))
        val once = applyTurnContext(history, "ctx")
        val twice = applyTurnContext(once, "ctx")
        assertSame(once, twice)
        // Re-running on the derived list must not append a second block.
        assertEquals(1, twice[0].content.split(TURN_CONTEXT_OPEN).size - 1)
    }

    @Test
    fun `content and parts stay consistent for an empty-parts message`() {
        val history = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "q", contentParts = emptyList()))
        val out = applyTurnContext(history, "ctx")
        assertEquals(1, out[0].contentParts.size)
        assertEquals(out[0].content, (out[0].contentParts[0] as AgentContentPart.Text).text)
    }

    @Test
    fun `injection preserves every earlier message byte for byte`() {
        // The whole point of the tail placement: only the latest user-text
        // message may grow, and only at its end. Everything before it is the
        // cacheable prefix and must not move by a single byte.
        val history = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        val out = applyTurnContext(history, "ctx")
        for (i in 0 until 2) {
            assertEquals("message $i must not change", history[i].content, out[i].content)
            assertEquals("message $i parts must not change", history[i].contentParts, out[i].contentParts)
        }
        assertTrue("the injected message grows only at its tail", out[2].content.startsWith(history[2].content))
        assertEquals("assistant row must not change", history[3].content, out[3].content)
    }

    @Test
    fun `a tool-result tail does not break the earlier prefix`() {
        // Tool loops end with a tool-result-only user row. The context still
        // goes to the real user prompt, so the tool round (the hottest,
        // longest prefix of the next request) is untouched.
        val history = listOf(
            user("question"),
            assistant("calling tool"),
            toolResultUser("call_1"),
        )
        val out = applyTurnContext(history, "ctx")
        assertEquals(history[1].content, out[1].content)
        assertEquals(history[2].content, out[2].content)
        assertEquals(history[2].contentParts, out[2].contentParts)
        assertTrue(out[0].content.startsWith(history[0].content))
    }

    @Test
    fun `persisted context row suppresses request-side injection`() {
        val wrapped = "$TURN_CONTEXT_OPEN\nctx1\n$TURN_CONTEXT_CLOSE"
        val contextRow = LLMMessage(
            role = LLMMessage.Role.USER,
            content = wrapped,
            contentParts = listOf(AgentContentPart.Text(wrapped)),
        )
        // Retry rebuild: the context row still follows the user turn.
        val history = listOf(user("q1"), contextRow, assistant("a1"))
        assertSame(history, applyTurnContext(history, "ctx2"))
    }

    @Test
    fun `next turn rebuild keeps the assistant reply while the request-only block drops`() {
        // Fallback path (no persisted context row): simulates what
        // prepareRunHistory does between turns. Divergence stays confined to
        // the old user message's tail.
        val turn1 = applyTurnContext(listOf(user("q1")), "ctx1")
        val rebuilt = listOf(user("q1"), assistant("a1"), user("q2"))
        val turn2 = applyTurnContext(rebuilt, "ctx2")
        assertEquals("a1", turn2[1].content)
        assertEquals("q2", turn2[2].content.substringBefore("\n\n$TURN_CONTEXT_OPEN"))
        assertEquals("q1", turn1[0].content.substringBefore("\n\n$TURN_CONTEXT_OPEN"))
        // Divergence is confined to the old user message (documented cost).
        assertFalse(turn2[0].content.contains(TURN_CONTEXT_OPEN))
    }
}
