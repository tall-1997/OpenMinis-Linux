package com.openminis.app.ui.chat

import com.openminis.app.data.ContextPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #404: only token pressure may request compaction. Coverage gaps are carried
 * by the bounded history digest instead, and the gap signal keeps exactly one
 * job — proving the digest actually rendered ([coverageGapWithoutDigest]).
 */
class InLoopCoverageGateTest {
    private val policy = ContextPolicy.forContextWindow(100_000, 50)

    @Test
    fun `under threshold proceeds`() {
        assertEquals(
            ContextPolicy.CheckResult.OK,
            projectedCapacity(policy, 10_000, 100_000),
        )
        assertEquals(
            ContextPolicy.CheckResult.OK,
            projectedCapacity(policy, 10_000, 100_000, bypassSoftThreshold = true),
        )
    }

    @Test
    fun `reaching the token threshold requests compaction`() {
        val threshold = policy.compactThreshold
        assertEquals(ContextPolicy.CheckResult.OK, projectedCapacity(policy, threshold - 1, 100_000))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, projectedCapacity(policy, threshold, 100_000))
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, projectedCapacity(policy, threshold + 1, 100_000))
    }

    @Test
    fun `the soft bypass disables the threshold but never the hard window`() {
        val threshold = policy.compactThreshold
        assertEquals(
            ContextPolicy.CheckResult.OK,
            projectedCapacity(policy, threshold, 100_000, bypassSoftThreshold = true),
        )
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            projectedCapacity(policy, 100_000, 100_000, bypassSoftThreshold = true),
        )
    }

    @Test
    fun `an unknown window never blocks`() {
        assertEquals(ContextPolicy.CheckResult.OK, projectedCapacity(policy, 900_000, 0))
        assertEquals(ContextPolicy.CheckResult.OK, projectedCapacity(policy, 900_000, -1))
    }

    /** [T-digest-covers-gap] The only surviving consumer of the coverage-gap signal. */
    @Test
    fun `a coverage gap must be represented by a rendered digest`() {
        assertTrue(coverageGapWithoutDigest(hasCoverageGap = true, digest = null))
        assertTrue(coverageGapWithoutDigest(hasCoverageGap = true, digest = "  "))
        assertFalse(coverageGapWithoutDigest(hasCoverageGap = true, digest = "[HISTORY_DIGEST]\n- user: hi"))
        assertFalse(coverageGapWithoutDigest(hasCoverageGap = false, digest = null))
    }
}
