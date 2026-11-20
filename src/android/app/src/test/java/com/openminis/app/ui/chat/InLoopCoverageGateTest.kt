package com.openminis.app.ui.chat

import com.openminis.app.data.ContextPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Coverage gaps are digest material; only token pressure may request compaction. */
class InLoopCoverageGateTest {
    private val policy = ContextPolicy.forContextWindow(100_000,50)

    @Test
    fun `under threshold with full coverage proceeds`() {
        assertEquals(
            ContextPolicy.CheckResult.OK,
            projectedCapacity(policy,10_000,100_000, hasCoverageGap = false),
        )
    }

    @Test
    fun `a coverage gap alone does not force compaction below the threshold`() {
        assertEquals(
            ContextPolicy.CheckResult.OK,
            projectedCapacity(policy,10_000,100_000, hasCoverageGap = true),
        )
        assertEquals(
            ContextPolicy.CheckResult.OK,
            projectedCapacity(policy,10_000,100_000, hasCoverageGap = true, bypassSoftThreshold = true),
        )
    }

    @Test
    fun `reaching the token threshold requests compaction even with a coverage gap`() {
        val threshold = policy.compactThreshold
        assertEquals(
            ContextPolicy.CheckResult.OK,
            projectedCapacity(policy, threshold -1,100_000, hasCoverageGap = true),
        )
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            projectedCapacity(policy, threshold,100_000, hasCoverageGap = true),
        )
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            projectedCapacity(policy, threshold +1,100_000, hasCoverageGap = true),
        )
    }

    @Test
    fun `the hard window still stops even with the soft bypass`() {
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            projectedCapacity(policy,100_000,100_000, hasCoverageGap = false, bypassSoftThreshold = true),
        )
    }
}
