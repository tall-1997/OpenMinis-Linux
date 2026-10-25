package com.openminis.app.data

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-summary-quality] and [T-compact-reduced-retry]: the failure
 * policy is pure so it can be exercised without an Android ViewModel. The
 * regression these guard is a real one — a short or off-target summary is
 * accepted by a naive `isNotEmpty()` check and then silently poisons every
 * later turn, and a transient provider refusal used to end in permanent
 * truncation.
 */
class CompactResilienceTest {

    private fun user(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)
    private fun assistant(text: String) = LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text)

    private fun longSummary(seed: String): String =
        // ~400 chars so it clears MIN_ACCEPTABLE_SUMMARY_CHARS with room.
        seed.repeat(40) + " trailing context to clear the length floor"

    // ─── summary quality gate ────────────────────────────────────────

    @Test
    fun `a long on-topic summary is accepted`() {
        val msgs = listOf(user("please refactor the notification pipeline"))
        val summary = longSummary("notification pipeline summary ")
        assertTrue(ChatViewModel.isCompactSummaryAcceptable(summary, msgs))
    }

    @Test
    fun `a too-short summary is rejected`() {
        val msgs = listOf(user("please refactor the notification pipeline"))
        assertFalse(
            ChatViewModel.isCompactSummaryAcceptable("done", msgs),
        )
    }

    @Test
    fun `a long but off-topic summary is rejected`() {
        val msgs = listOf(user("please refactor the notification pipeline"))
        val offTopic = longSummary("entirely unrelated grocery list ")
        assertFalse(ChatViewModel.isCompactSummaryAcceptable(offTopic, msgs))
    }

    @Test
    fun `quality check tolerates a degenerate history with no user turn`() {
        val msgs = listOf(assistant("tool output only"))
        // No user probe at all: nothing to require an echo of.
        assertTrue(ChatViewModel.isCompactSummaryAcceptable(longSummary("pipeline notification work "), msgs))
    }

    @Test
    fun `quality check tolerates a too-short user probe`() {
        val msgs = listOf(user("ok"))
        // Probe shorter than 4 chars -> treated as unspecifiable, accept.
        assertTrue(ChatViewModel.isCompactSummaryAcceptable(longSummary("pipeline work "), msgs))
    }

    // ─── reduced retry input ─────────────────────────────────────────

    @Test
    fun `small ranges skip the reduced retry`() {
        val small = (1..8).map { user("m$it") }
        assertNull(ChatViewModel.reducedCompactRetryInput(small))
    }

    @Test
    fun `large ranges retry on the newest three fifths`() {
        val big = (1..100).map { user("m$it") }
        val reduced = ChatViewModel.reducedCompactRetryInput(big)
        assertNotNull(reduced)
        assertEquals(60, reduced!!.size)
        // Keeps the tail, not the head: the most recent turns are what a
        // continuing conversation can actually build on.
        assertEquals("m41", reduced.first().content)
        assertEquals("m100", reduced.last().content)
    }

    @Test
    fun `reduced retry never drops below the floor`() {
        val twelve = (1..12).map { user("m$it") }
        val reduced = ChatViewModel.reducedCompactRetryInput(twelve)
        assertNotNull(reduced)
        assertEquals(8, reduced!!.size)
    }
}
