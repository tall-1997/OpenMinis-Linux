package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ToolIdNormalization
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-once-notices] A one-shot notice is a USER message that follows the
 * tool-result carrier. Providers that require strict user/assistant alternation
 * would reject the pair, so the request assembly collapses them into one user
 * message — the same shape ZCode produces by merging into the tool result.
 */
class RequestRoleMergeTest {

    private fun user(text: String, vararg parts: AgentContentPart) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        contentParts = if (parts.isEmpty()) listOf(AgentContentPart.Text(text)) else parts.toList(),
    )

    private fun assistant(text: String) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, contentParts = listOf(AgentContentPart.Text(text)))

    @Test
    fun `consecutive user texts become one message`() {
        val merged = ToolIdNormalization.mergeConsecutiveUserTurns(
            listOf(user("first"), user("notice")),
        )
        assertEquals(1, merged.size)
        assertEquals("first\n\nnotice", merged[0].content)
    }

    @Test
    fun `a notice after a tool result rides the same user message`() {
        val carrier = user(
            "",
            AgentContentPart.ToolResult(id = "call_1", name = "shell_execute", content = "ok"),
        )
        val merged = ToolIdNormalization.mergeConsecutiveUserTurns(listOf(carrier, user("notice")))
        assertEquals(1, merged.size)
        val parts = merged[0].contentParts
        assertEquals(2, parts.size)
        assertEquals("call_1", (parts[0] as AgentContentPart.ToolResult).id)
        assertEquals("notice", (parts[1] as AgentContentPart.Text).text)
    }

    @Test
    fun `an assistant turn keeps the user messages apart`() {
        val merged = ToolIdNormalization.mergeConsecutiveUserTurns(
            listOf(user("first"), assistant("reply"), user("second")),
        )
        assertEquals(3, merged.size)
        assertEquals("second", merged[2].content)
    }

    @Test
    fun `normalizeMessages applies the merge`() {
        val merged = ToolIdNormalization.normalizeMessages(listOf(user("a"), user("b")))
        assertEquals(1, merged.size)
    }
}
