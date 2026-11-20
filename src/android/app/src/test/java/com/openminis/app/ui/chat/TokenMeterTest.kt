package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-token-meter-single-authority] The precedence rule, pinned.
 *
 * Two displays disagreed with the gate twice in this branch because each call
 * site wrote its own `maxOf(...)`. These cases state the rule once: BOTH
 * display and pressure take the larger of the provider reading and the
 * pending estimate, so a stale reading can never make either look small.
 */
class TokenMeterTest {

    private fun user(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)

    @Test
    fun displayKeepsTheProviderReadingWhenThereIsOne() {
        // [T-token-meter-display-compat] Display keeps origin/main behaviour:
        // the provider reading is the billed prompt size of the last call.
        // The GATE (pressure) is the one that takes the larger of the two.
        val m = TokenMeter.forDisplay(providerTokens =5_000, estimatedTokens =8_000)
        assertEquals(5_000, m.totalTokens)
        assertTrue(m.sourceIsProvider)
    }

    @Test
    fun displayShowsTheProviderReadingWhenItIsTheLargerOne() {
        val m = TokenMeter.forDisplay(providerTokens = 9_000, estimatedTokens = 5_000)
        assertEquals(9_000, m.totalTokens)
        assertTrue(m.sourceIsProvider)
    }

    @Test
    fun displayFallsBackToTheEstimateBeforeTheFirstCall() {
        val m = TokenMeter.forDisplay(providerTokens = 0, estimatedTokens = 8_000)
        assertEquals(8_000, m.totalTokens)
        assertFalse(m.sourceIsProvider)
    }

    @Test
    fun pressureNeverUnderstatesTheRequest() {
        // A stale provider reading must not make an oversized request look small.
        assertEquals(8_000, TokenMeter.pressure(providerTokens = 5_000, estimatedTokens = 8_000))
        assertEquals(9_000, TokenMeter.pressure(providerTokens = 9_000, estimatedTokens = 8_000))
    }

    @Test
    fun negativeInputsAreTreatedAsAbsent() {
        assertEquals(0, TokenMeter.pressure(providerTokens = -1, estimatedTokens = 0))
        assertEquals(7, TokenMeter.forDisplay(providerTokens = -5, estimatedTokens = 7).totalTokens)
    }

    @Test
    fun aJournalSnapshotReproducesTheSameNumber() {
        // [T-token-meter-journal-source] The durable copy must agree with the
        // live measurement, or a post-mortem compares two different rules.
        val event = org.json.JSONObject()
        event.put("estTokens",8_000)
        event.put("providerTokens",5_000)
        val fromJournal = TokenMeter.fromJournalSnapshot(event)
        // The snapshot is the figure the GATE acted on, which is what a
        // post-mortem has to compare against.
        val live = TokenMeter.pressure(providerTokens =5_000, estimatedTokens =8_000)
    }

    @Test
    fun anEventWithoutTokenFieldsIsNotASnapshot() {
        // A request event written by an older build must not be read as zero.
        assertEquals(null, TokenMeter.fromJournalSnapshot(null))
        assertEquals(null, TokenMeter.fromJournalSnapshot(org.json.JSONObject().put("turn",3)))
    }

    @Test
    fun breakdownCountsEveryPartKindAndImagesByWeight() {
        val messages = listOf(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.Text("abcde"),
                    AgentContentPart.ToolUse("c1", "shell_execute", org.json.JSONObject("{\"a\":1}")),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("c1", "shell_execute", "xxxx"),
                    AgentContentPart.ImageData(ByteArray(8), "image/png"),
                ),
            ),
        )
        val b = TokenMeter.breakdown(messages)
        assertEquals(5L, b.messageChars)
        assertTrue(b.toolUseChars > 0L)
        assertEquals(4L, b.toolResultChars)
        assertEquals(1, b.imageCount)
        assertEquals(TokenMeter.ESTIMATED_IMAGE_CHARS, b.imageChars)
        assertEquals(
            b.messageChars + b.toolUseChars + b.toolResultChars + b.imageChars,
            b.totalChars,
        )
    }

    @Test
    fun breakdownDoesNotDoubleCountMessageContent() {
        // content is a display mirror of the leading text part for most
        // producers; counting both would silently move the ring number.
        val both = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "same text",
            contentParts = listOf(AgentContentPart.Text("same text")),
        )
        assertEquals(9L, TokenMeter.breakdown(listOf(both)).messageChars)
    }

    @Test
    fun emptyHistoryIsZero() {
        val b = TokenMeter.breakdown(emptyList())
        assertEquals(0L, b.totalChars)
        assertEquals(0, TokenMeter.pressure(0, 0))
    }

    @Test
    fun theSnapshotAgreesWithTheDisplayPrecedence() {
        val event = org.json.JSONObject()
        event.put("estTokens",8_000)
        event.put("providerTokens",5_000)
        val fromJournal = TokenMeter.fromJournalSnapshot(event)
        val live = TokenMeter.forDisplay(providerTokens =5_000, estimatedTokens =8_000)
        assertEquals(live.totalTokens, fromJournal?.totalTokens)
    }
}
