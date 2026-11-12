package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#352] A base64 image stored as text must be scrubbed from the outgoing
 * request, or every retry re-sends the same over-limit payload and the session
 * stays bricked.
 */
class TextifiedImageGuardTest {

    private val base64 = "A".repeat(70_000)

    @Test
    fun `large base64 text part is replaced on the outgoing copy`() {
        val original = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.Text("look: data:image/png;base64,$base64"),
            ),
        )
        val result = TextifiedImageGuard.scrub(listOf(original))
        assertEquals(1, result.scrubbedParts)
        assertTrue(result.savedChars > 60_000)
        val scrubbed = (result.history[0].contentParts[0] as AgentContentPart.Text).text
        assertTrue(scrubbed.startsWith(TextifiedImageGuard.STUB_PREFIX))
        assertFalse(scrubbed.contains(base64))
        // The persisted/in-memory message itself is NOT mutated.
        assertTrue((original.contentParts[0] as AgentContentPart.Text).text.contains(base64))
    }

    @Test
    fun `large base64 message body is replaced too`() {
        val original = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "data:image/jpeg;base64,$base64",
        )
        val result = TextifiedImageGuard.scrub(listOf(original))
        assertEquals(1, result.scrubbedParts)
        assertTrue(result.history[0].content.startsWith(TextifiedImageGuard.STUB_PREFIX))
    }

    @Test
    fun `tool result textified image loses the text duplicate but keeps pixels`() {
        val original = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "call_1",
                    name = "read_image",
                    content = "data:image/png;base64,$base64",
                    imageData = byteArrayOf(1, 2, 3),
                ),
            ),
        )
        val result = TextifiedImageGuard.scrub(listOf(original))
        assertEquals(1, result.scrubbedParts)
        val scrubbed = result.history[0].contentParts[0] as AgentContentPart.ToolResult
        assertTrue(scrubbed.content.startsWith(TextifiedImageGuard.STUB_PREFIX))
        assertEquals(3, scrubbed.imageData?.size ?: -1)
    }

    @Test
    fun `ordinary large text without base64 is untouched`() {
        val original = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.Text("x".repeat(100_000))),
        )
        val result = TextifiedImageGuard.scrub(listOf(original))
        assertEquals(0, result.scrubbedParts)
        assertEquals(original, result.history[0])
    }

    @Test
    fun `small base64-ish text is untouched`() {
        val original = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "data:image/png;base64,AAAA",
        )
        assertFalse(TextifiedImageGuard.looksLikeTextifiedImage(original.content))
        assertEquals(0, TextifiedImageGuard.scrub(listOf(original)).scrubbedParts)
    }
}
