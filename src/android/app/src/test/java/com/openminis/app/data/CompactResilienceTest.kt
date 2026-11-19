package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.CompactOutcome
import com.openminis.app.ui.chat.cappedCompactEnd
import com.openminis.app.ui.chat.coveredCountFor
import com.openminis.app.ui.chat.maxCompactRangeTokens
import com.openminis.app.ui.chat.reducedCompactRetryInput
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
        assertNull(reducedCompactRetryInput(small))
    }

    @Test
    fun `large ranges retry on the oldest three fifths`() {
        val big = (1..100).map { user("m$it") }
        val reduced = reducedCompactRetryInput(big)
        assertNotNull(reduced)
        assertEquals(60, reduced!!.size)
        // [T-compact-reduced-anchor] The summarized part must be a PREFIX of
        // the range: the marker only advances over what the summary covers,
        // and the newest turns stay active verbatim.
        assertEquals("m1", reduced.first().content)
        assertEquals("m60", reduced.last().content)
    }

    @Test
    fun `reduced retry never drops below the floor`() {
        val twelve = (1..12).map { user("m$it") }
        val reduced = reducedCompactRetryInput(twelve)
        assertNotNull(reduced)
        assertEquals(8, reduced!!.size)
    }

    @Test
    fun `a failed compaction never yields a covered count`() {
        // [T-compact-no-wipe-on-failure] The anchor advance decision is pure:
        // succeeded=false -> null (no marker), success -> covered prefix.
        assertNull(coveredCountFor(CompactOutcome("failed", 0, succeeded = false), 10))
        assertEquals(6, coveredCountFor(CompactOutcome("ok", 6), 10))
        assertEquals(1, coveredCountFor(CompactOutcome("ok", 0), 10))
        assertNull(coveredCountFor(CompactOutcome("ok", 6), 0))
    }

    @Test
    fun `compaction cap scales with the window and call budget`() {
        // [T-compact-range-cap] 4 leaves × segment (window/5, 8k..32k) = 4 calls.
        assertEquals(32_000, maxCompactRangeTokens(32_000))   // 4 × 8k
        assertEquals(102_400, maxCompactRangeTokens(128_000)) // 4 × 25.6k
        assertEquals(128_000, maxCompactRangeTokens(1_000_000)) // 4 × 32k
    }

    @Test
    fun `compaction range is capped to a summarizable prefix`() {
        // [T-compact-range-cap] A detached anchor starts the range at 0; a
        // million-token range would exhaust the 6-call budget and fail. The
        // attempt must cover only a prefix it can finish.
        val heavy = (1..100).map {
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("c$it", "shell_execute", "x".repeat(16_000)),
                ),
            )
        }
        val end = cappedCompactEnd(
            heavy,
            start = 0,
            end = heavy.lastIndex,
            maxTokens = 20_000,
            estimate = com.openminis.app.ui.chat.WalkBack::estimateMessageTokens,
        )
        assertEquals(4, end) // 5 × 4000 = 20000 fits; the 6th would exceed
        assertTrue(end < heavy.lastIndex)
    }

    @Test
    fun `compaction cap always includes the first message`() {
        val one = listOf(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("c", "shell_execute", "x".repeat(400_000)),
                ),
            ),
        )
        assertEquals(0, cappedCompactEnd(one, 0, 0, maxTokens = 10))
    }
}
