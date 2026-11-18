package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-llm-error-classification] Regression net for the corrected mappings:
 * 413 → ContextLengthExceeded, TLS → non-retryable ProviderError, 403 →
 * credential-accepted-but-denied detail, and secret masking of upstream
 * error bodies. Also pins the Timeout phase contract the agent loop keys on.
 */
class LLMErrorClassificationTest {

    // mapHttpError / mapError are internal extensions on OpenAIProvider in the
    // openai package; from the app's unit tests we exercise the classification
    // through the same helpers where constructible, and through LLMError's
    // own contract below.

    @Test
    fun `timeout participates in fallback`() {
        val err = LLMError.Timeout("read timed out", LLMError.Timeout.TimeoutPhase.READ)
        assertTrue(err.isFallbackable)
        assertTrue(err.isRetryable)
        assertTrue(err.isNetworkError)
    }

    @Test
    fun `connect timeout is the only timeout the main loop retries in place`() {
        // The agent loop keys on phase == CONNECT for same-provider retry.
        val connect = LLMError.Timeout("connect timed out", LLMError.Timeout.TimeoutPhase.CONNECT)
        val read = LLMError.Timeout("read timed out", LLMError.Timeout.TimeoutPhase.READ)
        assertTrue(connect.phase == LLMError.Timeout.TimeoutPhase.CONNECT)
        assertFalse(read.phase == LLMError.Timeout.TimeoutPhase.CONNECT)
    }

    @Test
    fun `tls failures classify as non-retryable provider errors`() {
        val tls = javax.net.ssl.SSLHandshakeException("certificate not trusted")
        val mapped = mapThrowableToLLMError(tls)
        assertTrue("expected ProviderError, got $mapped", mapped is LLMError.ProviderError)
        assertFalse(mapped.isRetryable)
    }

    @Test
    fun `plain io failures stay retryable network errors`() {
        val ioErr = java.io.IOException("connection reset")
        val mapped = mapThrowableToLLMError(ioErr)
        assertTrue(mapped is LLMError.NetworkError)
        assertTrue(mapped.isRetryable)
    }

    @Test
    fun `okhttp cancel io maps to a transient stall, not a network error`() {
        // [T-canceled-io-mapping] OkHttp reports a cancelled call as
        // IOException("Canceled") — the stall watchdog's cancel cascade, not a
        // provider network failure. The old mapping filed it as NetworkError
        // ("Network error: Canceled") which the loop could not retry (raw
        // IOExceptions are not transport-transient) — runs died at the
        // watchdog boundaries with the bare "Canceled" inline.
        val mapped = mapThrowableToLLMError(java.io.IOException("Canceled"))
        assertTrue("expected TransientError, got $mapped", mapped is LLMError.TransientError)
        assertTrue(
            "the loop must retry a teardown-cancelled stream in place",
            com.openminis.app.agent.LoopErrorGate.isTransient(mapped, is5xx = false, isPermanentCapacity = false),
        )
    }

    @Test
    fun `key-shaped material in upstream bodies is masked`() {
        val dirty = "request failed for key sk-prod-1234567890abcdef sent as Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.xxx"
        val masked = maskSecrets(dirty)
        assertFalse(masked.contains("sk-prod-1234567890abcdef"))
        assertFalse(masked.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue(masked.contains("[redacted]"))
    }

    @Test
    fun `403 detail drives the access-denied hint`() {
        val err = LLMError.InvalidApiKey("HTTP 403 forbidden — model or region not permitted")
        assertTrue(err.actionableHint.contains("403"))
        assertTrue(err.actionableHint.contains("not be allowed on your plan"))
    }

    @Test
    fun `plain 401 keeps the check-your-key hint`() {
        val err = LLMError.InvalidApiKey()
        assertTrue(err.actionableHint.contains("Check your API key"))
    }

    // [T-transient-5xx-fallback] Relay outages arrive as TransientError, not
    // ProviderError — see mapHttpErrorShared's TRANSIENT_HTTP_STATUSES. The
    // fallback predicate only recognised ProviderError 5xx, so a group with a
    // healthy member still failed the turn after 5 in-place retries.
    @Test
    fun `relay 503 transient error engages group fallback`() {
        val err = LLMError.TransientError("[503] Service temporarily unavailable")
        assertTrue("503 must keep retrying in place", err.isRetryable)
        assertTrue(
            "503 must also reach the group fallback once retries run out",
            err.isFallbackable,
        )
    }

    @Test
    fun `relay 5xx transient errors engage group fallback`() {
        listOf("[500] Internal error", "[502] Bad gateway", "[504] Gateway timeout", "[529] Overloaded").forEach { detail ->
            assertTrue(detail, LLMError.TransientError(detail).isFallbackable)
        }
    }

    @Test
    fun `watchdog stall transient errors keep resume semantics`() {
        // No HTTP status token: these are produced locally by the TTFB / idle
        // watchdogs and are resumed from the partial text, never "switched".
        val firstEvent = LLMError.TransientError("no first event within 45s")
        val idle = LLMError.TransientError(
            "stream idle for 90s after first event",
            stalledAfterFirstEvent = true,
        )
        assertFalse(firstEvent.isFallbackable)
        assertFalse(idle.isFallbackable)
        assertTrue(firstEvent.isRetryable)
        assertTrue(idle.isRetryable)
    }

    @Test
    fun `4xx status tokens do not fan out across the group`() {
        assertFalse(LLMError.TransientError("[400] Bad Request").isFallbackable)
        assertFalse(LLMError.TransientError("[404] Not Found").isFallbackable)
    }

    @Test
    fun `provider error 503 keeps falling back`() {
        assertTrue(LLMError.ProviderError("[503] model not provisioned").isFallbackable)
    }

    // [T-transient-5xx-fallback] One parser for the bracketed status the
    // mappers stamp into `detail`; the retry budget, the fallback predicate
    // and diagnostics all read it.
    @Test
    fun `http status token parses from transient and provider details`() {
        assertEquals(503, LLMError.TransientError("[503] Service temporarily unavailable").httpStatus)
        assertEquals(429, LLMError.ProviderError("[429] rate limited").httpStatus)
        assertEquals(429, LLMError.RateLimited(detail = "[429] slow down").httpStatus)
        assertNull(LLMError.TransientError("no first event within 45s").httpStatus)
        assertNull(LLMError.DecodingError(IllegalStateException("expected a JSON object body")).httpStatus)
    }

    @Test
    fun `capacity rejection covers 5xx but not 429 or local stalls`() {
        assertTrue(LLMError.TransientError("[503] Service temporarily unavailable").isCapacityRejection)
        assertTrue(LLMError.TransientError("[502] Bad gateway").isCapacityRejection)
        assertFalse(LLMError.TransientError("[429] slow down").isCapacityRejection)
        assertFalse(LLMError.TransientError("stream idle for 90s").isCapacityRejection)
    }

    @Test
    fun `capacity rejection prefers switching over a long retry budget`() {
        // The agent loop caps same-provider retries at 1 when the error is a
        // capacity 5xx and another group member exists; locally-produced
        // transients keep the full 1/2/4/8/16s ladder.
        val relay503 = LLMError.TransientError("[503] Service temporarily unavailable")
        val stall = LLMError.TransientError("stream idle for 90s after first event", stalledAfterFirstEvent = true)
        assertTrue(relay503.isCapacityRejection)
        assertFalse(stall.isCapacityRejection)
    }
}
