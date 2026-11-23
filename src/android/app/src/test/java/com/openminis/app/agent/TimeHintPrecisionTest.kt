package com.openminis.app.agent

import com.openminis.app.ui.chat.TurnClock
import com.openminis.app.ui.chat.requestClockNote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-time-hint-precision] A clock note we appended must not keep re-qualifying
 * its own message as a time question.
 *
 * Regression: the note text is `<system-note>当前时间: …` and the hint pattern
 * matches "当前时间". So once a note was appended, `hasTimeHint` returned true
 * for that message on every later request — the projector's "user asked for the
 * clock" branch stayed open forever, and the model got a fresh
 * "(since previous message: …)" cue each turn.
 */
class TimeHintPrecisionTest {

    @Test
    fun `a message with an appended clock note is not a time question`() {
        val noted = "<system-note>当前时间: 2026-11-23 16:30</system-note>\n帮我看下这个 bug"
        assertFalse(
            "the note we appended must not count as the user asking for time",
            MessageTransformerChain.hasTimeHint(noted),
        )
    }

    @Test
    fun `an ordinary sentence mentioning time is not a time question`() {
        listOf("这段时间不太对劲", "提醒我一下", "今天做什么", "花了 5 分钟").forEach {
            assertFalse(it, MessageTransformerChain.hasTimeHint(it))
        }
    }

    @Test
    fun `a real time question still counts`() {
        listOf("现在几点了？", "今天是几号", "what time is it", "今天天气如何").forEach {
            assertTrue(it, MessageTransformerChain.hasTimeHint(it))
        }
    }

    @Test
    fun `a time question asked under an old note still counts`() {
        // The note is stripped first, so the question behind it stays visible.
        val noted = "<system-note>当前时间: 2026-11-23 16:30</system-note>\n现在几点了"
        assertTrue(MessageTransformerChain.hasTimeHint(noted))
    }

    @Test
    fun `a question placed before the note is still visible`() {
        // appendUserText can stack notes; the strip must be span-wise, not
        // "everything after the head".
        val text = "现在几点了？<system-note>当前时间: 2026-11-23 16:30</system-note>"
        assertTrue(MessageTransformerChain.hasTimeHint(text))
    }

    @Test
    fun `stacked notes leave no phantom hint`() {
        val text = "<system-note>当前时间: 2026-11-23 16:30</system-note>\n" +
            "<system-note>当前时间: 2026-11-23 16:40</system-note>\n继续这个任务"
        assertFalse(MessageTransformerChain.hasTimeHint(text))
    }

    @Test
    fun `the projector injects once, not once per request`() {
        // End-to-end shape of the reported bug: the user message already
        // carries a note, the throttle was refreshed seconds ago — the note
        // must not come back. Before the fix the note text itself counted as a
        // time hint, so every request re-injected "(since previous message: 0s)".
        TimeContextGate.resetForTest()
        TurnClock.resetForTest()
        val session = "time-hint-e2e"
        val now = 10_000_000L
        TimeContextGate.markInjected(session, now - 20_000L)
        val history = listOf(
            com.openminis.app.data.model.LLMMessage(
                role = com.openminis.app.data.model.LLMMessage.Role.USER,
                content = "<system-note>当前时间: 2026-11-23 16:30</system-note>\n继续",
                dbMessageId = "row-1",
            ),
        )
        assertNull(
            "an appended note must not re-open the clock",
            com.openminis.app.ui.chat.requestClockNote(session, history, nowMs = now),
        )
    }
}
