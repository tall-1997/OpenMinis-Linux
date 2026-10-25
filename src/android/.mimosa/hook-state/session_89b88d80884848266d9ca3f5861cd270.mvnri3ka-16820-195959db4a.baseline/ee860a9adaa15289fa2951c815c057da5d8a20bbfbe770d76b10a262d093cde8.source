package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRetryAfterTest {

    @Test
    fun `parses integer Retry-After header`() {
        assertEquals(12, HttpRetryAfter.parseSeconds("12"))
        // [T-android-retryafter-honor] Explicit server values are honored up to
        // MAX_HONORED_RETRY_AFTER_SEC instead of a blanket 120s clamp.
        assertEquals(999, HttpRetryAfter.parseSeconds("999"))
        assertEquals(HttpRetryAfter.MAX_HONORED_RETRY_AFTER_SEC, HttpRetryAfter.parseSeconds("99999"))
        assertNull(HttpRetryAfter.parseSeconds("not-a-number"))
    }

    @Test
    fun `parses HTTP-date Retry-After header`() {
        // Far-future date: clamped to the honor ceiling regardless of "now".
        assertEquals(
            HttpRetryAfter.MAX_HONORED_RETRY_AFTER_SEC,
            HttpRetryAfter.parseSeconds("Wed, 21 Oct 2199 07:28:00 GMT"),
        )
        // Past date = "retry now".
        assertEquals(
            1,
            HttpRetryAfter.parseSeconds("Wed, 21 Oct 2015 07:28:00 GMT"),
        )
    }

    @Test
    fun `parses retry-after from JSON body`() {
        assertEquals(
            8,
            HttpRetryAfter.parseSeconds(null, """{"error":{"message":"retry_after=8"}}"""),
        )
        assertEquals(
            5,
            HttpRetryAfter.parseSeconds(null, """{"retryAfter": 5}"""),
        )
    }

    @Test
    fun `header wins over body`() {
        assertEquals(3, HttpRetryAfter.parseSeconds("3", """{"retry_after": 40}"""))
    }

    @Test
    fun `backoff follows schedule then honours Retry-After`() {
        assertEquals(1, HttpRetryAfter.delaySeconds(0, null))
        assertEquals(2, HttpRetryAfter.delaySeconds(1, null))
        assertEquals(16, HttpRetryAfter.delaySeconds(4, null))
        assertEquals(20, HttpRetryAfter.delaySeconds(0, 20))
        // Explicit server values beyond the ladder ceiling are honored.
        assertEquals(500, HttpRetryAfter.delaySeconds(0, 500))
        // Ladder-only path keeps its own 120s ceiling.
        assertEquals(120, HttpRetryAfter.delaySeconds(9, null))
    }

    @Test
    fun `plain 429 body is RateLimited`() {
        val err = HttpRetryAfter.map429("Rate limited", "12")
        assertTrue(err is LLMError.RateLimited)
        assertEquals(12, (err as LLMError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `strong capacity 429 bodies are ProviderError`() {
        val bodies = listOf(
            "无可用渠道",
            """{"error":{"message":"no_available_providers"}}""",
            "model_not_found for sn-deepseek-v4-1-flash",
            "insufficient_quota",
            "当前分组上游负载已饱和",
        )
        for (body in bodies) {
            val err = HttpRetryAfter.map429(body, "1")
            assertTrue("expected ProviderError for $body, got $err", err is LLMError.ProviderError)
            assertTrue((err as LLMError.ProviderError).detail.contains("[429]"))
        }
        assertFalse(HttpRetryAfter.isPermanentCapacityBody("Rate limited"))
        assertFalse(HttpRetryAfter.isPermanentCapacityBody("Too many requests"))
    }

    @Test
    fun `weak capacity markers classify as transient at the provider`() {
        // [T-android-capacity-tiering] Weak markers ("余额不足", "billing", …)
        // also appear inside transient rate-limit copy, so at the provider
        // boundary they stay RateLimited (transient family). Whether they
        // bypass the same-provider retry is decided where the fallback
        // context is known (ChatViewModel).
        val weakBodies = listOf("余额不足", "billing error", "无可用模型节点")
        for (body in weakBodies) {
            assertTrue(HttpRetryAfter.isWeakCapacityBody(body))
            assertFalse(HttpRetryAfter.isStrongCapacityBody(body))
            assertTrue(HttpRetryAfter.isPermanentCapacityBody(body))
            val err = HttpRetryAfter.map429(body, null)
            assertTrue("expected RateLimited for $body, got $err", err is LLMError.RateLimited)
        }
    }

    @Test
    fun `transient 429 message includes body snippet`() {
        val err = HttpRetryAfter.map429("try again in a few seconds", null)
        assertTrue(err is LLMError.RateLimited)
        assertTrue(err.message!!.contains("try again in a few seconds"))
        assertTrue(LLMError.RateLimited().isFallbackable)
        assertTrue(LLMError.InvalidApiKey().isFallbackable)
        assertTrue(LLMError.ProviderError("[429] 无可用渠道").isFallbackable)
        assertTrue(LLMError.ProviderError("[503] no_available_providers").isFallbackable)
        assertFalse(LLMError.ProviderError("The model does not exist").isFallbackable)
        assertFalse(LLMError.ProviderError("context window 512 tokens").isFallbackable)
        assertFalse(LLMError.TransientError("connection dropped").isFallbackable)
    }

    @Test
    fun `snippets are redacted before reaching UI`() {
        val err = HttpRetryAfter.map429(
            """invalid key sk-abcdefghijklmnop1234 for bearer AbCdEf0123456789 api_key=deadbeefcafebabe0123""",
            null,
        )
        val msg = err.message ?: ""
        assertFalse(msg.contains("sk-abcdefghijklmnop"))
        assertFalse(msg.contains("AbCdEf0123456789"))
        assertFalse(msg.contains("deadbeefcafebabe0123"))
        assertTrue(msg.contains("***"))
        // Deterministic units on the raw helper too.
        assertEquals(
            "key=*** rest",
            HttpRetryAfter.redactSecrets("key=abcdefghijklmnop rest"),
        )
        assertEquals("sk-*** ok", HttpRetryAfter.redactSecrets("sk-abcdef1234567890 ok"))
    }
}
