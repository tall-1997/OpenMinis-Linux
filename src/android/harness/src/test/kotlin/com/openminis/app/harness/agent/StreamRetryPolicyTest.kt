package com.openminis.app.harness.agent

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-loop-retry-cut] 重试/回退决策表验收——与原 catch 块内联实现逐条对齐。
 */
class StreamRetryPolicyTest {

    private fun classifyOf(error: Throwable) = StreamRetryPolicy.classify(error)

    @Test
    fun `transient network error retries on same provider with ladder delay`() {
        val cls = classifyOf(LLMError.NetworkError(RuntimeException("boom")))
        val d = StreamRetryPolicy.decide(cls, retryAttempt = 0, maxRetries = 5, remainingFallbacks = 0, strategy = FallbackStrategy.default)
        assertTrue(d is StreamRetryPolicy.Decision.RetrySameProvider)
        d as StreamRetryPolicy.Decision.RetrySameProvider
        assertEquals(1, d.delaySec) // ladder[0]
        assertEquals(1, d.attempt)
    }

    @Test
    fun `rate limit with a sibling skips same-provider retry and falls back`() {
        val cls = classifyOf(LLMError.RateLimited(retryAfterSeconds = 30))
        val d = StreamRetryPolicy.decide(cls, retryAttempt = 0, maxRetries = 5, remainingFallbacks = 2, strategy = FallbackStrategy.default)
        assertTrue(d is StreamRetryPolicy.Decision.TryFallback)
    }

    @Test
    fun `rate limit as last candidate retries at most once`() {
        val cls = classifyOf(LLMError.RateLimited())
        val first = StreamRetryPolicy.decide(cls, retryAttempt = 0, maxRetries = 5, remainingFallbacks = 0, strategy = FallbackStrategy.default)
        assertTrue(first is StreamRetryPolicy.Decision.RetrySameProvider)
        val second = StreamRetryPolicy.decide(cls, retryAttempt = 1, maxRetries = 5, remainingFallbacks = 0, strategy = FallbackStrategy.default)
        assertTrue(second is StreamRetryPolicy.Decision.Rethrow)
    }

    @Test
    fun `permanent capacity never retries on same provider`() {
        val err = LLMError.ProviderError("[429] insufficient quota")
        val cls = classifyOf(err)
        assertTrue(cls.isPermanentCapacity)
        assertTrue(!cls.isTransient)
        val d = StreamRetryPolicy.decide(cls, retryAttempt = 0, maxRetries = 5, remainingFallbacks = 0, strategy = FallbackStrategy.default)
        assertTrue(d is StreamRetryPolicy.Decision.Rethrow)
        // 有组员 → 直接回退，不烧阶梯
        val d2 = StreamRetryPolicy.decide(cls, retryAttempt = 0, maxRetries = 5, remainingFallbacks = 1, strategy = FallbackStrategy.default)
        assertTrue(d2 is StreamRetryPolicy.Decision.TryFallback)
    }

    @Test
    fun `read-phase timeout is not transient and goes to fallback`() {
        val err = LLMError.Timeout("read timeout", LLMError.Timeout.TimeoutPhase.READ)
        val cls = classifyOf(err)
        assertTrue(!cls.isTimeoutConnect)
        assertTrue(!cls.isTransient)
        assertTrue(cls.isFallbackable) // Timeout is fallbackable
        val d = StreamRetryPolicy.decide(cls, retryAttempt = 0, maxRetries = 5, remainingFallbacks = 1, strategy = FallbackStrategy.default)
        assertTrue(d is StreamRetryPolicy.Decision.TryFallback)
    }

    @Test
    fun `connect-phase timeout retries on same provider`() {
        val cls = classifyOf(LLMError.Timeout("connect timeout", LLMError.Timeout.TimeoutPhase.CONNECT))
        val d = StreamRetryPolicy.decide(cls, retryAttempt = 0, maxRetries = 5, remainingFallbacks = 3, strategy = FallbackStrategy.default)
        assertTrue(d is StreamRetryPolicy.Decision.RetrySameProvider)
    }

    @Test
    fun `5xx provider error retries then falls back when budget exhausted`() {
        val cls = classifyOf(LLMError.ProviderError("[503] upstream down"))
        assertTrue(cls.is5xx)
        val retry = StreamRetryPolicy.decide(cls, retryAttempt = 0, maxRetries = 2, remainingFallbacks = 1, strategy = FallbackStrategy.default)
        assertTrue(retry is StreamRetryPolicy.Decision.RetrySameProvider)
        val last = StreamRetryPolicy.decide(cls, retryAttempt = 2, maxRetries = 2, remainingFallbacks = 1, strategy = FallbackStrategy.default)
        assertTrue(last is StreamRetryPolicy.Decision.TryFallback)
    }

    @Test
    fun `always strategy falls back even on non-fallbackable errors`() {
        val cls = classifyOf(LLMError.NetworkError(RuntimeException("x")))
        val d = StreamRetryPolicy.decide(cls, retryAttempt = 5, maxRetries = 1, remainingFallbacks = 1, strategy = FallbackStrategy.always)
        assertTrue(d is StreamRetryPolicy.Decision.TryFallback)
    }

    @Test
    fun `shouldFallback ignores remaining count for the exhausted trail`() {
        val cls = classifyOf(LLMError.RateLimited())
        assertTrue(StreamRetryPolicy.shouldFallback(cls, FallbackStrategy.default))
        // 非回退错误 + default 策略 → false
        val net = classifyOf(RuntimeException("plain"))
        assertTrue(!StreamRetryPolicy.shouldFallback(net, FallbackStrategy.default))
    }

    @Test
    fun `nextCandidate skips and discards same-bucket members`() {
        data class C(val bucket: String?)
        val remaining = mutableListOf(C("k1"), C("k1"), C("k2"))
        val picked = StreamRetryPolicy.nextCandidate(remaining, currentBucket = "k1") { it.bucket }
        assertEquals("k2", picked?.bucket)
        assertEquals(listOf<C>(), remaining) // both k1 siblings consumed
    }

    @Test
    fun `nextCandidate treats blank bucket as never same`() {
        data class C(val bucket: String?)
        val remaining = mutableListOf(C(null), C(""))
        val picked = StreamRetryPolicy.nextCandidate(remaining, currentBucket = null) { it.bucket }
        assertNotNull(picked)
        assertNull(StreamRetryPolicy.nextCandidate(mutableListOf(C("x")), "x") { it.bucket })
    }

    @Test
    fun `fallback reason prefers rate-limit message then provider detail`() {
        val rate = classifyOf(LLMError.RateLimited(detail = "slow down"))
        assertTrue(StreamRetryPolicy.fallbackReason(rate).contains("Rate limited"))
        val prov = classifyOf(LLMError.ProviderError("[429] quota"))
        assertTrue(StreamRetryPolicy.fallbackReason(prov).contains("[429] quota"))
        val net = classifyOf(LLMError.NetworkError(RuntimeException("conn reset")))
        assertTrue(StreamRetryPolicy.fallbackReason(net).contains("conn reset"))
    }
}
