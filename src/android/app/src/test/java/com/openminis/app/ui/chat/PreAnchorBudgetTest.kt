package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * [T-preanchor-dynamic-budget GH#344] Regression: the old inline expression
 * `min(15_000, max(0.2·window, 8_000))` saturated at 15k for every window
 * ≥ 75k, so 128k/200k models always got the hardcoded ceiling and the
 * "≤20% window" dynamic term was dead code. PR #331 declared 40k.
 */
class PreAnchorBudgetTest {

    @Test fun noWindowFallsBackToDeclaredCeiling() {
        assertEquals(40_000, WalkBack.preAnchorTokenBudget(null))
        assertEquals(WalkBack.COMPACT_PREANCHOR_TOKEN_BUDGET, WalkBack.preAnchorTokenBudget(null))
    }

    @Test fun smallWindowsTakeTheFractionInsteadOfTheFloor() {
        // [T-budget-floor-fraction-clamp] On tiny windows the old 8k floor
        // overrode the ≤20% share (32k window → 8k = 25%). The floor is now
        // clamped to the fraction: small windows get their proportional
        // preAnchor, and summary + preAnchor + postAnchor stays under the 80%
        // compact threshold on every model.
        assertEquals(6_400, WalkBack.preAnchorTokenBudget(32_000))
        assertEquals(200, WalkBack.preAnchorTokenBudget(1_000))
        assertEquals(8_000, WalkBack.preAnchorTokenBudget(40_000))
    }

    @Test fun midWindowScalesWithTwentyPercent() {
        assertEquals(12_800, WalkBack.preAnchorTokenBudget(64_000))
        assertEquals(25_600, WalkBack.preAnchorTokenBudget(128_000))
    }

    @Test fun largeWindowSaturatesAtFortyK() {
        assertEquals(40_000, WalkBack.preAnchorTokenBudget(200_000))
        assertEquals(40_000, WalkBack.preAnchorTokenBudget(1_000_000))
    }

    @Test fun corridorIsMonotonicAndStaysUnderParityBounds() {
        var previous = 0
        for (window in listOf(1_000, 32_000, 40_000, 64_000, 75_000, 128_000, 200_000, 1_000_000)) {
            val budget = WalkBack.preAnchorTokenBudget(window)
            assertTrue("budget must be monotonic at window=$window", budget >= previous)
            // [T-budget-floor-fraction-clamp] The budget is the ≤20% window
            // share clamped by the 40k corridor ceiling — the ceiling wins on
            // huge windows (1M window → 200k fraction → 40k budget).
            val expected = minOf(
                (window * WalkBack.PREANCHOR_WINDOW_FRACTION).toInt(),
                WalkBack.COMPACT_PREANCHOR_TOKEN_BUDGET,
            )
            assertEquals(
                "budget must be the 20% share clamped by the ceiling at window=$window",
                expected, budget,
            )
            assertTrue("budget must respect the ceiling at window=$window",
                budget <= WalkBack.COMPACT_PREANCHOR_TOKEN_BUDGET)
            // preAnchor + the 60k postAnchor ceiling leaves room under the
            // 0.8·window compact threshold on realistic (128k+) windows.
            if (window >= 128_000) {
                assertTrue("corridor must stay under 0.8·window at window=$window",
                    budget + 60_000 < window * 0.8)
            }
            previous = budget
        }
    }

    @Test fun theOldFifteenKCeilingWouldHaveFailedTheContract() {
        // Guard against reintroducing the hardcoded 15k ceiling: on a 128k
        // model the declared corridor is 25.6k, not 15k.
        assertNotEquals(15_000, WalkBack.preAnchorTokenBudget(128_000))
        assertEquals(40_000, WalkBack.COMPACT_PREANCHOR_TOKEN_BUDGET)
    }
}
