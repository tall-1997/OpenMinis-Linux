package com.openminis.app.ui.chat

import com.openminis.app.data.ContextPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** [T-request-coverage-gate] Token size and prefix coverage answer different questions. */
class InLoopCoverageGateTest {
    private val policy = ContextPolicy.forContextWindow(100_000, 50)

    @Test
    fun `under threshold with full coverage proceeds`() {
        assertEquals(
            ContextPolicy.CheckResult.OK,
            projectedCapacity(policy, 10_000, 100_000, hasCoverageGap = false),
        )
    }

    @Test
    fun `a coverage gap blocks the request even when the payload is small`() {
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            projectedCapacity(policy, 1_000, 100_000, hasCoverageGap = true),
        )
        // With the soft path unavailable (compaction disabled), the gap is a hard stop.
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            projectedCapacity(policy, 1_000, 100_000, hasCoverageGap = true, bypassSoftThreshold = true),
        )
    }

    @Test
    fun `at the window the request stops even with the soft bypass`() {
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            projectedCapacity(policy, 100_000, 100_000, hasCoverageGap = false, bypassSoftThreshold = true),
        )
    }
}
