package com.openminis.app.harness.context

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDigestTest {
    @Test
    fun renderKeepsNewestExcerptAndCountsTheRest() {
        val lines = (1..20).map { HistoryDigest.Line("user", "turn $it " + "x".repeat(400)) }
        val text = HistoryDigest.render(lines).orEmpty()
        assertTrue(text.startsWith(HistoryDigest.MARKER))
        assertTrue(text.contains("turn 20"))
        assertFalse(text.contains("turn 1 "))
        assertTrue(text.contains("省略"))
    }

    @Test
    fun readablePreviewPullsTextValuesOutOfAJsonStub() {
        val raw = """[{"type":"text","value":"hello \"world\""}]"""
        assertEquals("hello \"world\"", HistoryDigest.readablePreview(raw))
    }

    @Test
    fun injectAddsDigestAsAStructuredTextPartWhenPartsAlreadyExist() {
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "question",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        id = "tool-1",
                        name = "read",
                        input = JSONObject(),
                    ),
                ),
            ),
        )
        val injected = HistoryDigest.inject(history, "[HISTORY_DIGEST]\nnote")
        assertEquals(
            "[HISTORY_DIGEST]\nnote",
            (injected.single().contentParts.first() as AgentContentPart.Text).text,
        )
        assertTrue(injected.single().content.startsWith("[HISTORY_DIGEST]"))
    }

    @Test
    fun injectPrefixesTheFirstUserTurnOnly() {
        val history = listOf(
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "old"),
            LLMMessage(role = LLMMessage.Role.USER, content = "question"),
        )
        val injected = HistoryDigest.inject(history, "[HISTORY_DIGEST]\nnote")
        assertEquals("old", injected[0].content)
        assertTrue(injected[1].content.startsWith("[HISTORY_DIGEST]"))
        assertTrue(injected[1].content.endsWith("question"))
        assertEquals(injected, HistoryDigest.inject(injected, "[HISTORY_DIGEST]\nagain"))
    }
}
