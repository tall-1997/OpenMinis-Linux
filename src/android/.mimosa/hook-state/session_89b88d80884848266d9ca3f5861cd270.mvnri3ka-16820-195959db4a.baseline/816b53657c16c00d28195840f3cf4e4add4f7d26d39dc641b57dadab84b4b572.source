package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-auto-compact-inloop] Pins the ContextPolicy behaviour the in-loop
 * guard in ChatViewModel.runAgentLoop depends on (iOS f70ac173).
 *
 * The guard itself needs a live ViewModel (Context + DB + provider), so these
 * cover the decision inputs rather than the coroutine plumbing: which tiers can
 * auto-compact, and which can only ever report exhaustion.
 *
 * This distinction is load-bearing. `check()` returns NEEDS_COMPACT first
 * whenever compactThreshold > 0, so EXHAUSTED can ONLY come from an
 * `exhaustedOnly` tier — one where compactThreshold is deliberately 0 because
 * the window is too small for compaction to pay for itself. The guard must
 * therefore STOP on EXHAUSTED rather than attempt a "rescue" compaction.
 */
class InLoopContextPolicyTest {

    @Test
    fun `large windows can auto-compact`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window)
        // Just past the compact threshold.
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            p.check(p.compactThreshold, window),
        )
    }

    @Test
    fun `mid windows can auto-compact`() {
        val window = 100_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            p.check(p.compactThreshold + 1, window),
        )
    }

    @Test
    fun `small windows auto-compact before the offload line`() {
        // [T-compact-small-window-auto] The 32K-64K tier no longer refuses
        // auto-compaction: a bounded tool-heavy history costs far more than a
        // summary plus a short tail, so the ceiling is reached otherwise.
        val window = 40_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(window - 15_000, p.compactThreshold)
        // Fires before offload, so the summary lands while headroom remains.
        assertTrue(p.compactThreshold < p.offloadThreshold)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            p.check(window - 1_000, window),
        )
        // Still quiet below the compact line.
        assertEquals(
            ContextPolicy.CheckResult.OK,
            p.check(p.compactThreshold - 1_000, window),
        )
    }

    @Test
    fun `tiny windows never ask for compaction - only exhaustion`() {
        val window = 16_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(0, p.compactThreshold)
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            p.check(window - 100, window),
        )
    }

    @Test
    fun `an idle context proceeds untouched`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(ContextPolicy.CheckResult.OK, p.check(1_000, window))
    }

    @Test
    fun `compaction is only requested at or above the threshold`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window)
        // One token below the line must NOT trigger a compaction — otherwise the
        // in-loop guard would compact on every iteration near the boundary.
        assertEquals(
            ContextPolicy.CheckResult.OK,
            p.check(p.compactThreshold - 1, window),
        )
    }

    @Test
    fun `default 90 percent matches historic 200k and 100k headroom`() {
        assertEquals(180_000, ContextPolicy.forContextWindow(200_000).compactThreshold)
        assertEquals(90_000, ContextPolicy.forContextWindow(100_000).compactThreshold)
    }

    @Test
    fun `custom compact percent scales the threshold`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window, compactPercent = 80)
        assertEquals(160_000, p.compactThreshold)
        assertEquals(ContextPolicy.CheckResult.OK, p.check(159_999, window))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(160_000, window))
    }

    @Test
    fun `small windows ignore the compact percent knob`() {
        // [T-compact-small-window-auto] The 32K-64K tier now has a real
        // compactThreshold fixed 15k below the window; the percent knob that
        // scales the >=64K tiers must not shrink it away again.
        val p = ContextPolicy.forContextWindow(40_000, compactPercent = 95)
        assertEquals(40_000 - 15_000, p.compactThreshold)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            p.check(39_000, 40_000),
        )
    }
}
