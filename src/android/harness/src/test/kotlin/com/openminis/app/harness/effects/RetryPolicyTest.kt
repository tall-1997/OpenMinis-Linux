package com.openminis.app.harness.effects

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Adapted from taixu HarnessRuntimePolicyTest (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 上游该测试还覆盖 ToolReplayPolicy / OperationSnapshot（第二批移植），
 * 此处仅保留 RetryPolicy 部分。
 */
class RetryPolicyTest {
    @Test
    fun `retry policy uses bounded exponential delays`() {
        val policy = RetryPolicy(enabled = true, maxRetries = 3, baseDelayMs = 1_000)
        assertEquals(4, policy.maxAttempts)
        assertEquals(1_000, policy.delayForRetry(1))
        assertEquals(2_000, policy.delayForRetry(2))
        assertEquals(4_000, policy.delayForRetry(3))
    }

    @Test
    fun `disabled policy makes a single attempt and no delay`() {
        val policy = RetryPolicy(enabled = false, maxRetries = 5, baseDelayMs = 1_000)
        assertEquals(1, policy.maxAttempts)
        assertEquals(0, policy.delayForRetry(1))
    }

    @Test
    fun `delay saturates instead of overflowing`() {
        val policy = RetryPolicy(enabled = true, maxRetries = 100, baseDelayMs = 1_000)
        assertEquals(Long.MAX_VALUE, policy.delayForRetry(100))
    }

    @Test
    fun `networking default matches the documented budget`() {
        assertEquals(6, RetryPolicy.NETWORK_DEFAULT.maxAttempts)
        assertEquals(1_500, RetryPolicy.NETWORK_DEFAULT.delayForRetry(1))
    }
}
