 package com.openminis.app.agent

 import org.junit.Assert.assertEquals
 import org.junit.Assert.assertThrows
 import org.junit.Assert.assertTrue
 import org.junit.Test

 /**
  * [T-harness-config] Three contracts worth pinning:
  *1. the shipped defaults reproduce the legacy literals EXACTLY (a config layer
  * that moved a number would silently change request assembly);
  *2. `validated()` rejects every combination that cannot work;
  *3. the per-window formula keeps a usable tail even for a tiny window.
  *
  * The process-global override and its per-session file store were REMOVED
  * (nothing ever wrote the file; the global would have leaked one chat's
  * numbers into the next). So there is no install/current state to test here.
  */
 class HarnessConfigTest {

    @Test
    fun defaultsMatchTheLegacyLiterals() {
        val c = HarnessConfig.DEFAULT
        assertEquals(60_000, c.postAnchorBudgetTokens)
        assertEquals(0.35, c.postAnchorWindowFraction,0.0)
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
        assertEquals(60_000, c.postAnchorBudgetFor(null))
        assertEquals(11_200, c.postAnchorBudgetFor(32_000))
        assertEquals(44_800, c.postAnchorBudgetFor(128_000))
        assertEquals(60_000, c.postAnchorBudgetFor(200_000))
    }

    @Test
    fun aZeroOrNegativeWindowFallsBackToTheCeiling() {
        val c = HarnessConfig.DEFAULT
        assertEquals(60_000, c.postAnchorBudgetFor(0))
        assertEquals(60_000, c.postAnchorBudgetFor(-1))
    }

    @Test
    fun aTinyWindowStillLeavesRoomForTheNotice() {
        // [T-harness-config-floor] A2K window scales the ceiling to700 tokens,
        // below the1_200 reserve, and the tail used to collapse to a single
        // token. The guarantee is that the reserve never eats the whole tail:
        // a window that small simply cannot carry a useful one.
        val c = HarnessConfig.DEFAULT
        val budget = c.postAnchorBudgetFor(2_048)
        assertTrue(budget > c.noticeReserveTokens)
        assertTrue(c.effectiveTailBudget(budget) >= 1)
    }

    @Test
    fun effectiveTailBudgetSubtractsTheReserveAndNeverReachesZero() {
        val c = HarnessConfig.DEFAULT
        assertEquals(60_000 -1_200, c.effectiveTailBudget(60_000))
        assertEquals(1, c.effectiveTailBudget(100))
        assertEquals(1, c.effectiveTailBudget(0))
    }

    @Test
    fun everyInvalidCombinationIsRejected() {
        val bad = listOf(
            HarnessConfig(postAnchorBudgetTokens =0),
            HarnessConfig(postAnchorWindowFraction =0.0),
            HarnessConfig(postAnchorWindowFraction =1.0),
            HarnessConfig(noticeReserveTokens =0),
            HarnessConfig(postAnchorBudgetTokens =1_000, noticeReserveTokens =1_000),
            HarnessConfig(noticeBaseTokens =0),
            HarnessConfig(absoluteAgeTokens =0),
            HarnessConfig(agingCharsPerToken =0L),
            HarnessConfig(digestScanMessages =0),
            HarnessConfig(digestMaxLines =0),
            HarnessConfig(digestMaxChars =0),
            HarnessConfig(ledgerMaxEntries =1),
        )
        val thrown = bad.count { cfg -> runCatching { cfg.validated() }.isFailure }
        assertEquals(bad.size, thrown)
    }

    @Test
    fun theShippedDefaultsValidate() {
        assertEquals(HarnessConfig.DEFAULT, HarnessConfig.DEFAULT.validated())
    }

    @Test
    fun aTighterReserveIsHonouredByBothTailPaths() {
        val tight = HarnessConfig(noticeReserveTokens =500)
        assertEquals(60_000 -500, tight.effectiveTailBudget(60_000))
        assertTrue(tight.effectiveTailBudget(60_000) > HarnessConfig.DEFAULT.effectiveTailBudget(60_000))
    }
 }
