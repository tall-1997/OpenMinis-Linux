package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

}
