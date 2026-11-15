package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-thinking-dual-ledger] The per-turn thinking ledger written into every
 * token_usage JSON (the port of pi's `thinkingLevel` vs
 * `providerThinkingLevel` split). Pins the honesty contract:
 *  - requested is ALWAYS recorded, evidence or not;
 *  - effective is recorded only when the response evidenced thinking AND the
 *    requested tier is in the model's DECLARED set (where requested == wire
 *    tier by construction of the picker);
 *  - everything else is null (= unknown), never a guess — the pre-ledger
 *    failure this exists to prevent is a wrong-tier request being invisible
 *    after the fact.
 */
class TurnThinkingUsageTest {

    private val declared = listOf(
        ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.MAX,
    )

    @Test
    fun `requested is always recorded`() {
        // No evidence, empty declared set — requested still lands.
        val noEvidence = resolveTurnThinkingUsage(
            ThinkingLevel.HIGH, thinkingObserved = false, declaredSelectableLevels = emptyList(),
        )
        assertEquals("HIGH", noEvidence.requested)
        assertNull(noEvidence.effective)

        val withEvidence = resolveTurnThinkingUsage(
            ThinkingLevel.HIGH, thinkingObserved = true, declaredSelectableLevels = declared,
        )
        assertEquals("HIGH", withEvidence.requested)
        assertEquals("HIGH", withEvidence.effective)
    }

    @Test
    fun `effective needs response evidence`() {
        assertNull(
            resolveTurnThinkingUsage(
                ThinkingLevel.MEDIUM, thinkingObserved = false, declaredSelectableLevels = declared,
            ).effective,
        )
        assertEquals(
            "MEDIUM",
            resolveTurnThinkingUsage(
                ThinkingLevel.MEDIUM, thinkingObserved = true, declaredSelectableLevels = declared,
            ).effective,
        )
    }

    @Test
    fun `effective needs the requested tier inside the declared set`() {
        // LOW was requested but the model only declares [medium..max]: the wire
        // tier may differ from the ask (relay clamp, or the field omitted by
        // the no-upward-jump fix) — unknown, never a guess.
        val narrow = listOf(ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.MAX)
        assertNull(
            resolveTurnThinkingUsage(
                ThinkingLevel.LOW, thinkingObserved = true, declaredSelectableLevels = narrow,
            ).effective,
        )
        // Inside the set, evidence present: the tier is sound.
        assertEquals(
            "MEDIUM",
            resolveTurnThinkingUsage(
                ThinkingLevel.MEDIUM, thinkingObserved = true, declaredSelectableLevels = narrow,
            ).effective,
        )
    }

    @Test
    fun `OFF is never recorded as effective`() {
        // OFF is not in the declared picker set by definition, but pin it
        // explicitly: an OFF request that a forced-reasoning model answered
        // WITH visible thinking must stay unknown, not become "OFF effective"
        // (that would misread as "the request was honoured").
        assertNull(
            resolveTurnThinkingUsage(
                ThinkingLevel.OFF, thinkingObserved = true, declaredSelectableLevels = declared,
            ).effective,
        )
        assertEquals("OFF", resolveTurnThinkingUsage(
            ThinkingLevel.OFF, thinkingObserved = true, declaredSelectableLevels = declared,
        ).requested)
    }

    @Test
    fun `undeclared catalog keeps the ledger unknown even with evidence`() {
        assertNull(
            resolveTurnThinkingUsage(
                ThinkingLevel.XHIGH, thinkingObserved = true, declaredSelectableLevels = emptyList(),
            ).effective,
        )
    }
}
