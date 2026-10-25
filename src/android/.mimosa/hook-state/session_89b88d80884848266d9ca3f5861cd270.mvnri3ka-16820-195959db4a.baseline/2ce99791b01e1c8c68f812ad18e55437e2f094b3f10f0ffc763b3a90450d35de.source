package com.openminis.app.data

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-detached-anchor] The regression under test: once a marker's
 * anchor falls out of the bounded agentHistory window, the old code threw
 * the summary away and sent the full history on every turn — a session could
 * never get back under budget. The fallback must instead deliver the summary
 * plus a verbatim tail, starting on a user turn.
 */
class CompactDetachedAnchorTest {

    private fun user(i: Int) = LLMMessage(role = LLMMessage.Role.USER, content = "u$i", dbMessageId = "id$i")
    private fun asst(i: Int) = LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "a$i", dbMessageId = "id${i}a")

    private fun history(n: Int): List<LLMMessage> =
        (1..n).flatMap { listOf(user(it), asst(it)) }

    @Test
    fun `a short history is passed through with the summary inlined`() {
        val h = listOf(user(1), asst(1), user(2))
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals(3, out.size)
        assertEquals(LLMMessage.Role.USER, out.first().role)
        assertTrue(out.first().content.startsWith("<summary/>"))
        assertTrue(out.first().content.endsWith("u1"))
    }

    @Test
    fun `an oversized history is cut down to the tail budget`() {
        val h = history(200) // 400 entries
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals(60, out.size)
        // Tail semantics: the newest entries survive, the oldest are dropped.
        assertEquals("u200", out.last { it.role == LLMMessage.Role.USER }.content)
    }

    @Test
    fun `leading assistant turns are peeled so the first message is user`() {
        val h = listOf(asst(1), asst(2), user(3), asst(3))
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals(LLMMessage.Role.USER, out.first().role)
        assertTrue(out.first().content.startsWith("<summary/>"))
    }

    @Test
    fun `a tail with no user turn still carries the summary`() {
        val h = listOf(asst(1), asst(2))
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals(3, out.size)
        assertEquals("<summary/>", out.last().content)
        assertEquals(LLMMessage.Role.USER, out.last().role)
    }

    @Test
    fun `the injected summary is strictly smaller than sending the whole history`() {
        // The point of the fix: bound what reaches the provider.
        val h = history(200)
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertTrue(
            "expected ${out.size} sent messages vs ${h.size} in history",
            out.size < h.size,
        )
    }
}
