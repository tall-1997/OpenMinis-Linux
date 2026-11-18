package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-postanchor-dropped-digest] The post-anchor tail budget drops rounds the
 * compact summary does NOT cover. These tests pin what survives: real user
 * instructions and assistant decisions as bounded excerpts, never old harness
 * reminders, never pure tool traffic (the working-memory ledger owns that).
 */
class DroppedRoundDigestTest {

    private fun user(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)
    private fun assistant(text: String) = LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text)

    private fun resultCarrier(id: String, output: String = "ok") = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(AgentContentPart.ToolResult(id = id, name = "shell_execute", content = output)),
    )

    @Test
    fun keepsUserInstructionsAndAssistantDecisions() {
        val digest = DroppedRoundDigest.render(
            listOf(
                user("Implement the login retry with backoff."),
                assistant("I will add a backoff helper first."),
                resultCarrier("c1"),
            ),
        )!!
        assertTrue(digest.contains("user: Implement the login retry with backoff."))
        assertTrue(digest.contains("assistant: I will add a backoff helper first."))
        assertTrue(digest.startsWith(DroppedRoundDigest.HEADER))
    }

    @Test
    fun skipsHarnessNotesAndToolResultCarriers() {
        val digest = DroppedRoundDigest.render(
            listOf(
                user("<system-reminder>\n## Recalled relevant memories\nold note\n</system-reminder>"),
                resultCarrier("c1", "large tool output"),
                user("Keep this instruction."),
            ),
        )!!
        assertFalse(digest.contains("Recalled relevant memories"))
        assertFalse(digest.contains("large tool output"))
        assertTrue(digest.contains("Keep this instruction."))
    }

    @Test
    fun pureToolTrafficProducesNoDigest() {
        assertNull(DroppedRoundDigest.render(listOf(resultCarrier("c1"), resultCarrier("c2"))))
        assertNull(DroppedRoundDigest.render(emptyList()))
    }

    @Test
    fun clipsLongTextToOneLine() {
        val digest = DroppedRoundDigest.render(listOf(user("x".repeat(2000))))!!
        assertTrue(digest.contains("…"))
        val line = digest.lines().first { it.startsWith("- user: ") }
        assertTrue(line.length <= DroppedRoundDigest.USER_CHARS + 10)
        assertFalse(line.contains("\n"))
    }

    @Test
    fun keepsNewestLinesWhenOverTheLineCap() {
        val dropped = (0 until 60).map { user("instruction $it") }
        val digest = DroppedRoundDigest.render(dropped)!!
        assertTrue("newest must survive", digest.contains("instruction 59"))
        assertFalse("oldest beyond the cap is summarized, not carried", digest.contains("instruction 0"))
        assertTrue(digest.contains("older dropped lines"))
        val bodyLines = digest.lines().filter { it.startsWith("- user: ") }
        assertTrue(bodyLines.size <= DroppedRoundDigest.MAX_LINES)
    }

    @Test
    fun textFromPartsIsUsed() {
        val msg = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(AgentContentPart.Text("part-based decision")),
        )
        val digest = DroppedRoundDigest.render(listOf(msg))!!
        assertTrue(digest.contains("assistant: part-based decision"))
    }

    @Test
    fun digestIsBoundedInTotalChars() {
        val dropped = (0 until 30).map { user("y".repeat(DroppedRoundDigest.USER_CHARS)) }
        val digest = DroppedRoundDigest.render(dropped)!!
        assertTrue(digest.length <= DroppedRoundDigest.MAX_CHARS + DroppedRoundDigest.HEADER.length + 200)
    }

    @Test
    fun emptyUserTextIsIgnored() {
        assertEquals(null, DroppedRoundDigest.render(listOf(user("   "), assistant(""))))
    }
}
