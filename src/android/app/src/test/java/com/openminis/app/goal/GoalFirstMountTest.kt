package com.openminis.app.goal

import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-process-tail-injection] The first-mount rule for a Goal run.
 *
 * The objective IS the user's instruction, so a goal run may append ONE
 * standalone user turn at its first turn. Everything else the host injects
 * rides the system prompt tail; it must never be grafted onto the history's
 * last user message (inside the agent loop that message is the tool_result
 * carrier — process narration would read as tool output, and every turn
 * re-injected it).
 */
class GoalFirstMountTest {

    private fun user(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)

    private fun toolResultCarrier() = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            com.openminis.app.data.model.AgentContentPart.ToolResult("call_1", "shell_execute", "ok"),
        ),
    )

    @Test
    fun `no append requested leaves the history untouched`() {
        val history = listOf(user("do work"), toolResultCarrier())
        val out = withGoalFirstMount(history, "<goal_context>x</goal_context>", appendNewUser = false)
        assertEquals(history, out)
    }

    @Test
    fun `first mount appends exactly one standalone user turn`() {
        val history = listOf(user("do work"), toolResultCarrier())
        val out = withGoalFirstMount(history, "<goal_context>x</goal_context>", appendNewUser = true)
        assertEquals(history.size + 1, out.size)
        assertEquals(LLMMessage.Role.USER, out.last().role)
        assertTrue(out.last().content.startsWith("<goal_context>"))
        // The carrier itself is never rewritten.
        assertEquals("", out[1].content)
    }

    @Test
    fun `first mount with a blank goal prompt is a no-op`() {
        val history = listOf(user("do work"))
        assertEquals(history, withGoalFirstMount(history, null, appendNewUser = true))
        assertEquals(history, withGoalFirstMount(history, "   ", appendNewUser = true))
    }
}
