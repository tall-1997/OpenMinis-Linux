package com.openminis.app.ui.chat

import com.openminis.app.agent.TimeContextGate
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [T-time-context-no-nag] The clock note is a THROTTLED context aid, never a
 * per-message nag.
 *
 * Regression this pins: `requestClockNote` used to read
 * `if (!shouldInject && !hasTimeHint) return null`, i.e. it injected when
 * EITHER condition held. Any user text containing one of the loose hint words
 * ("时间", "现在", "提醒", "分钟", …) therefore bypassed the 10-minute throttle
 * and a note was re-injected on every request — including "(since previous
 * message: 0s)". The model read that stream of reminders as an instruction to
 * keep verifying instead of finishing, which showed up as fragmented prose.
 */
class RequestClockThrottleTest {

    private val sessionId = "clock-throttle-session"

    @Before
    fun reset() {
        TimeContextGate.resetForTest()
        TurnClock.resetForTest()
    }

    private fun userSaying(text: String) = listOf(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = text,
            contentParts = listOf(AgentContentPart.Text(text)),
            // [T-receipt-notice-removed] `requestClockNote` locates the latest
            // user row through `receiptUserRow`, which requires a persisted id —
            // a fixture without one exercised nothing and made two cases fail.
            dbMessageId = "user-row-1",
        ),
    )

    @Test
    fun `user text that merely mentions time does not bypass the throttle`() {
        val now = 1_000_000L
        // First request is due (no previous injection): a note is produced.
        val first = requestClockNote(sessionId, userSaying("现在几点"), nowMs = now)
        assertNotNull("first note should be due", first)
        first!!.confirm(now)

        // Seconds later an ordinary sentence containing a former hint word
        // ("时间") must NOT re-open the gate.
        val second = requestClockNote(sessionId, userSaying("这段时间不太对劲"), nowMs = now + 5_000L)
        assertNull("everyday words must not bypass the throttle", second)
    }

    @Test
    fun `an explicit time question pulls the clock forward once per minute`() {
        val now = 5_000_000L
        // Throttle was refreshed seconds ago, yet the user explicitly asks.
        TimeContextGate.markInjected(sessionId, now - 30_000L)

        val asked = requestClockNote(sessionId, userSaying("现在几点了？"), nowMs = now)
        assertNotNull("an explicit time question may pull the clock forward", asked)
        asked!!.confirm(now)

        // The same minute must not stamp twice, no matter how often we ask.
        val again = requestClockNote(sessionId, userSaying("现在几点了？"), nowMs = now + 2_000L)
        assertNull("a hint must not inject twice for one timestamp", again)
    }

    @Test
    fun `a note carrying the clock head is not re-injected on the next request`() {
        val now = 2_000_000L
        val stamped = "<system-note>当前时间: 2026-11-23 14:00"
        TimeContextGate.markInjected(sessionId, now)
        // Even far past the throttle window, the message already carrying the
        // note head must stay silent.
        val note = requestClockNote(sessionId, userSaying(stamped), nowMs = now + TimeContextGate.MIN_INTERVAL_MS * 3)
        assertNull("already-stamped history must not be re-stamped", note)
    }

    @Test
    fun `the throttle still opens after the interval`() {
        val now = 3_000_000L
        TimeContextGate.markInjected(sessionId, now)
        val later = requestClockNote(
            sessionId,
            userSaying("继续"),
            nowMs = now + TimeContextGate.MIN_INTERVAL_MS,
        )
        assertNotNull("a genuinely stale clock should still be refreshed", later)
        assertFalse("note text should carry the stamp", later!!.text.isBlank())
    }

    @Test
    fun `no user turn means no note`() {
        val assistantOnly = listOf(
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "hi"),
        )
        assertNull(requestClockNote(sessionId, assistantOnly, nowMs = 4_000_000L))
    }
}
