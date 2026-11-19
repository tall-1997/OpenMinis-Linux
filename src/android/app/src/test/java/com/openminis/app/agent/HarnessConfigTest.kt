package com.openminis.app.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-harness-config] Two contracts worth pinning:
 *  1. the shipped defaults reproduce the legacy literals EXACTLY (a config layer
 *     that moved a number would silently change request assembly);
 *  2. `validated()` rejects every combination that cannot work, because a bad
 *     reserve shows up much later as "compaction did nothing".
 *
 * The per-window formula is checked at the four sizes the roadmap and the
 * tool-title/budget gate both name: null, 32k, 128k, 200k.
 */
class HarnessConfigTest {

    @After
    fun tearDown() {
        HarnessConfig.resetForTest()
    }

    @Test
    fun defaultsMatchTheLegacyLiterals() {
        val c = HarnessConfig.DEFAULT
        assertEquals(60_000, c.postAnchorBudgetTokens)
        assertEquals(0.35, c.postAnchorWindowFraction, 0.0)
        assertEquals(1_200, c.noticeReserveTokens)
        assertEquals(64, c.noticeBaseTokens)
        assertEquals(24_000, c.absoluteAgeTokens)
        assertEquals(3L, c.agingCharsPerToken)
        assertEquals(512, c.digestScanMessages)
        assertEquals(24, c.digestMaxLines)
        assertEquals(3_000, c.digestMaxChars)
        assertEquals(600, c.ledgerMaxEntries)
    }

    @Test
    fun thePerWindowFormulaMatchesTheShippedNumbers() {
        val c = HarnessConfig.DEFAULT
        // null window → the hard ceiling.
        assertEquals(60_000, c.postAnchorBudgetFor(null))
        // 32k → 11 200 (35%), well under the ceiling.
        assertEquals(11_200, c.postAnchorBudgetFor(32_000))
        // 128k → 44 800.
        assertEquals(44_800, c.postAnchorBudgetFor(128_000))
        // 200k → capped at the ceiling, not 70 000.
        assertEquals(60_000, c.postAnchorBudgetFor(200_000))
    }

    @Test
    fun aZeroOrNegativeWindowFallsBackToTheCeiling() {
        // [T-harness-config] "Unknown window" is expressed as 0 by some callers.
        // Scaling by 0 would leave the tail with no room at all; the ceiling is
        // the honest answer, and it matches the null case.
        val c = HarnessConfig.DEFAULT
        assertEquals(60_000, c.postAnchorBudgetFor(0))
        assertEquals(60_000, c.postAnchorBudgetFor(-1))
    }

    @Test
    fun effectiveTailBudgetSubtractsTheReserveAndNeverReachesZero() {
        val c = HarnessConfig.DEFAULT
        assertEquals(60_000 - 1_200, c.effectiveTailBudget(60_000))
        // A budget smaller than the reserve still leaves one token, so callers
        // that multiply or divide by it cannot hit a zero.
        assertEquals(1, c.effectiveTailBudget(100))
        assertEquals(1, c.effectiveTailBudget(0))
    }

    @Test
    fun everyInvalidCombinationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(postAnchorBudgetTokens = 0).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(postAnchorWindowFraction = 0.0).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(postAnchorWindowFraction = 1.0).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(noticeReserveTokens = 0).validated()
        }
        // A reserve at or above the budget leaves no room for the tail.
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(postAnchorBudgetTokens = 1_000, noticeReserveTokens = 1_000).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(noticeBaseTokens = 0).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(absoluteAgeTokens = 0).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(agingCharsPerToken = 0L).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(digestScanMessages = 0).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(digestMaxLines = 0).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(digestMaxChars = 0).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig(ledgerMaxEntries = 1).validated()
        }
    }

    @Test
    fun currentFallsBackToTheDefaultsUntilAnOverrideIsInstalled() {
        assertEquals(HarnessConfig.DEFAULT, HarnessConfig.current())
        val custom = HarnessConfig(noticeReserveTokens = 500)
        HarnessConfig.install(custom)
        assertEquals(custom, HarnessConfig.current())
        HarnessConfig.resetForTest()
        assertEquals(HarnessConfig.DEFAULT, HarnessConfig.current())
    }

    @Test
    fun installRejectsAnUnusableOverrideInsteadOfStoringIt() {
        assertThrows(IllegalArgumentException::class.java) {
            HarnessConfig.install(HarnessConfig(postAnchorBudgetTokens = 0))
        }
        // The rejected install must not have replaced the live value.
        assertEquals(HarnessConfig.DEFAULT, HarnessConfig.current())
    }

    @Test
    fun aTighterReserveIsHonouredByBothTailPaths() {
        // [T-harness-config] The postAnchor tail and the detached tail subtract
        // the SAME reserve. A smaller override must move both.
        val tight = HarnessConfig(noticeReserveTokens = 500)
        assertEquals(60_000 - 500, tight.effectiveTailBudget(60_000))
        assertTrue(tight.effectiveTailBudget(60_000) > HarnessConfig.DEFAULT.effectiveTailBudget(60_000))
    }
}