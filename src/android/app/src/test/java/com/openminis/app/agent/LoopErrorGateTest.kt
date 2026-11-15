package com.openminis.app.agent

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composition-level tests for the loop's error gate: the components are
 * covered by RetryClassifierTest / ContextOverflowDetectorTest; these pin the
 * loop-relevant combinations (quota suppression of a 429-shaped error,
 * generic-error overflow routing, transient passthrough).
 */
class LoopErrorGateTest {

    @Test
    fun `rate limited with quota wording is not transient`() {
        val error = LLMError.RateLimited(detail = "quota exceeded for this organization")
        assertFalse(LoopErrorGate.isTransient(error, is5xx = false, isPermanentCapacity = false))
    }

    @Test
    fun `rate limited without quota wording stays transient`() {
        val error = LLMError.RateLimited(retryAfterSeconds = 30, detail = "slow down")
        assertTrue(LoopErrorGate.isTransient(error, is5xx = false, isPermanentCapacity = false))
    }

    @Test
    fun `server 5xx with billing wording is not transient`() {
        val error = LLMError.TransientError(detail = "billing error: card declined")
        assertFalse(LoopErrorGate.isTransient(error, is5xx = true, isPermanentCapacity = false))
    }

    @Test
    fun `permanent capacity short-circuits before the classifier`() {
        val error = LLMError.TransientError(detail = "upstream connect error")
        assertFalse(LoopErrorGate.isTransient(error, is5xx = false, isPermanentCapacity = true))
    }

    @Test
    fun `connect timeout stays transient`() {
        val error = LLMError.Timeout(detail = "connect timed out", phase = LLMError.Timeout.TimeoutPhase.CONNECT)
        assertTrue(LoopErrorGate.isTransient(error, is5xx = false, isPermanentCapacity = false))
    }

    @Test
    fun `read timeout is not transient without the 5xx signal`() {
        val error = LLMError.Timeout(detail = "read timed out", phase = LLMError.Timeout.TimeoutPhase.READ)
        assertFalse(LoopErrorGate.isTransient(error, is5xx = false, isPermanentCapacity = false))
    }

    @Test
    fun `context length exceeded is overflow`() {
        assertTrue(LoopErrorGate.isContextOverflow(LLMError.ContextLengthExceeded(detail = "too many tokens")))
    }

    @Test
    fun `generic provider error with prompt-overflow wording is overflow`() {
        assertTrue(
            LoopErrorGate.isContextOverflow(
                LLMError.ProviderError("[400] prompt is too long: 213462 tokens > 200000 maximum"),
            ),
        )
    }

    @Test
    fun `generic provider error with rate-limit wording is NOT overflow`() {
        assertFalse(
            LoopErrorGate.isContextOverflow(
                LLMError.ProviderError("[429] too many requests, please retry later"),
            ),
        )
    }

    @Test
    fun `non-llm throwables are neither transient nor overflow`() {
        val error = IllegalStateException("nothing provider-shaped")
        assertFalse(LoopErrorGate.isTransient(error, is5xx = false, isPermanentCapacity = false))
        assertFalse(LoopErrorGate.isContextOverflow(error))
    }
}
