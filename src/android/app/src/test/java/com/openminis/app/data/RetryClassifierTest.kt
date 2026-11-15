package com.openminis.app.data

import com.openminis.app.data.RetryClassifier.RetryClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port-fidelity tests for [RetryClassifier] (pi ai/src/utils/retry.ts,
 * session layer). Message fixtures are pi's own regression strings; the
 * status-code paths and the blacklist-beats-whitelist precedence are covered
 * separately from the backoff ladder.
 */
class RetryClassifierTest {

    // ── transient whitelist ─────────────────────────────────────────────────

    @Test
    fun `matches explicit provider retry guidance`() {
        val openAI =
            "An error occurred while processing your request. You can retry your request, or contact us " +
                "through our help center at help.openai.com if the error persists. Please include the " +
                "request ID req_******** in your message."
        val bedrock =
            """{"message":"The system encountered an unexpected error during processing. Try your request again."}"""
        val nvidiaNim = "ResourceExhausted: Worker local total request limit reached (288/48)"
        for (message in listOf(openAI, bedrock, nvidiaNim)) {
            assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `matches Bun fetch socket drop wording`() {
        assertEquals(
            RetryClass.RETRYABLE_TRANSIENT,
            RetryClassifier.classify(
                "The socket connection was closed unexpectedly. For more information, pass " +
                    "`verbose: true` in the second argument to fetch()",
                null,
            ),
        )
    }

    @Test
    fun `matches upstream request buffer exhaustion wording`() {
        assertEquals(
            RetryClass.RETRYABLE_TRANSIENT,
            RetryClassifier.classify("Error: exceeded request buffer limit while retrying upstream", null),
        )
    }

    @Test
    fun `matches DNS transport failure wording`() {
        val messages = listOf(
            "The pending stream has been canceled (caused by: getaddrinfo ENOTFOUND " +
                "bedrock-runtime.us-east-1.amazonaws.com)",
            "connect ENOTFOUND api.example.com",
            "EAI_AGAIN api.example.com",
            "getaddrinfo failed for api.example.com",
        )
        for (message in messages) {
            assertEquals("expected retryable: $message", RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `matches bare Node socket error codes`() {
        // Regression for pi #12128: bare Node socket error codes.
        val messages = listOf(
            "read ECONNRESET",
            "connect ETIMEDOUT 140.82.113.3:443",
            "write EPIPE",
            "connect ECONNREFUSED 127.0.0.1:443",
            "ECONNABORTED",
            "Connection reset by peer",
        )
        for (message in messages) {
            assertEquals("expected retryable: $message", RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `does not match socket error codes inside longer identifiers`() {
        val messages = listOf(
            "Invalid value EXECONNRESET",
            "Unknown field ETIMEDOUTX",
            "Invalid key ERR_EPIPE_ID",
        )
        for (message in messages) {
            assertEquals("expected NOT retryable: $message", RetryClass.NOT_RETRYABLE, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `matches HTTP2 pending stream cancellation`() {
        // Regression for pi #10379.
        for (message in listOf(
            "The pending stream has been canceled",
            "The pending stream has been canceled (caused by: socket closed)",
        )) {
            assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `matches service unavailable wording`() {
        for (message in listOf(
            // Regression for pi #10965 (provider unknown, no status code in the message).
            "The service is temporarily unavailable. Please retry later.",
            // Bedrock ServiceUnavailableException as formatted by formatBedrockError.
            "Service unavailable: The service is temporarily unavailable.",
        )) {
            assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `matches node-fetch request failure wording`() {
        // Regression for pi #10996: node-fetch errors from the google-vertex OAuth refresh.
        for (message in listOf(
            "request to https://oauth2.googleapis.com/token failed, reason:",
            "request to https://oauth2.googleapis.com/token failed, reason: connect ECONNREFUSED 127.0.0.1:9",
        )) {
            assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `matches gateway upstream failure wording`() {
        // Regression for pi #10920: gateway upstream failures sent as response.failed codes.
        for (message in listOf(
            "unknown: Unable to forward upstream stream",
            "upstream_error: Upstream request failed",
            "upstream_stream_read_error: Upstream response stream was interrupted",
            "Upstream HTTP/2 stream failed",
        )) {
            assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `matches gateway wording for a stream cut short mid-reply and a closed connection`() {
        // Regressions for pi #11879 and #12064.
        assertEquals(
            RetryClass.RETRYABLE_TRANSIENT,
            RetryClassifier.classify(
                "Claude Code's stream from Anthropic was cut short after the reply had begun",
                null,
            ),
        )
        assertEquals(
            RetryClass.RETRYABLE_TRANSIENT,
            RetryClassifier.classify(
                """{"type":"error","error":{"type":"api_error","message":"Connection closed."}}""",
                null,
            ),
        )
    }

    @Test
    fun `matches OpenAI Responses streams that end before terminal events`() {
        assertEquals(
            RetryClass.RETRYABLE_TRANSIENT,
            RetryClassifier.classify("OpenAI Responses stream ended before a terminal response event", null),
        )
    }

    @Test
    fun `matches Azure peak-load capacity errors`() {
        // Regression for pi #9669.
        assertEquals(
            RetryClass.RETRYABLE_TRANSIENT,
            RetryClassifier.classify(
                "The system is currently experiencing high demand and cannot process your request. " +
                    "Your request exceeds the maximum usage size allowed during peak load.",
                null,
            ),
        )
    }

    @Test
    fun `classifies assistant error messages`() {
        assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify("overloaded_error", null))
        // Regression for pi #9627: Cloudflare-style bodyless statuses inside the message.
        assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify("520 status code (no body)", null))
        assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify("524 status code (no body)", null))
    }

    @Test
    fun `does not match status codes inside ids or longer numbers`() {
        // Regression for pi #11057: status codes inside ids or other numbers are not status codes.
        val messages = listOf(
            """401 {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"},""" +
                """"request_id":"req_011CU503xyz"}""",
            "400 image exceeds 5 MB maximum: 5316784 bytes > 5242880 bytes",
        )
        for (message in messages) {
            assertEquals("expected NOT retryable: $message", RetryClass.NOT_RETRYABLE, RetryClassifier.classify(message, null))
        }
    }

    // ── quota / billing blacklist ───────────────────────────────────────────

    @Test
    fun `quota blacklist suppresses the transient whitelist`() {
        // "429" alone would match the whitelist status-code alternative; the
        // quota wording must win.
        assertEquals(RetryClass.QUOTA_EXHAUSTED, RetryClassifier.classify("429 quota exceeded", 429))
        // Both tables match: the blacklist is checked first.
        assertEquals(RetryClass.QUOTA_EXHAUSTED, RetryClassifier.classify("rate limit reached: quota exceeded", 429))
        assertEquals(
            RetryClass.QUOTA_EXHAUSTED,
            RetryClassifier.classify("exceeded request buffer limit while retrying upstream - billing required", null),
        )
    }

    @Test
    fun `quota blacklist entries classify as exhausted`() {
        val messages = listOf(
            "insufficient_quota",
            "You are out of budget for this month",
            "OpenCode Go: Monthly usage limit reached",
            """{"error":{"type":"GoUsageLimitError"}}""",
            """{"error":{"type":"FreeUsageLimitError"}}""",
            "Please enable available balance usage to continue",
            """OpenAI API error (429): {"code":"subscription_sharing_usage_limit_exceeded","message":"Usage limit reached."}""",
            """OpenAI API error (429): """ +
                """"{"code":"subscription_sharing_workplace_individual_usage_limit_exceeded","message":"Usage limit reached."}""",
        )
        for (message in messages) {
            assertEquals("expected QUOTA_EXHAUSTED: $message", RetryClass.QUOTA_EXHAUSTED, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `zhipu error code 1308 is quota exhaustion even as HTTP 429`() {
        // Regression for pi #11537: resets after hours, not seconds.
        assertEquals(RetryClass.QUOTA_EXHAUSTED, RetryClassifier.classify("""{"code":1308,"message":"..."}""", 429))
        assertEquals(RetryClass.QUOTA_EXHAUSTED, RetryClassifier.classify("""{"code":"1308"}""", 429))
        // Longer codes sharing the 1308 prefix are NOT the quota code.
        assertEquals(RetryClass.NOT_RETRYABLE, RetryClassifier.classify("""{"code":13080}""", null))
        assertEquals(RetryClass.NOT_RETRYABLE, RetryClassifier.classify("""{"code":13081}""", null))
    }

    @Test
    fun `temporary ChatGPT subscription errors stay retryable`() {
        // The blacklist carries ..._limit_exceeded; the unavailable variants are transient.
        for (message in listOf(
            "subscription_sharing_usage_unavailable: Usage cannot be checked.",
            "subscription_sharing_user_unavailable: User cannot be loaded.",
        )) {
            assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify(message, null))
        }
    }

    @Test
    fun `deterministic failures are not retryable`() {
        assertEquals(RetryClass.NOT_RETRYABLE, RetryClassifier.classify("Invalid API key provided", 401))
        assertEquals(RetryClass.NOT_RETRYABLE, RetryClassifier.classify("The model does not support tool use", 400))
    }

    // ── status-code paths ───────────────────────────────────────────────────

    @Test
    fun `throttling and server statuses are retryable without message evidence`() {
        for (status in listOf(408, 409, 429, 500, 502, 503, 504, 520, 524, 599)) {
            assertEquals(
                "expected retryable for status $status",
                RetryClass.RETRYABLE_TRANSIENT,
                RetryClassifier.classify(null, status),
            )
            assertEquals(
                "expected retryable for blank message with status $status",
                RetryClass.RETRYABLE_TRANSIENT,
                RetryClassifier.classify("", status),
            )
        }
    }

    @Test
    fun `client and success statuses are not retryable without message evidence`() {
        for (status in listOf(200, 301, 400, 401, 403, 404, 422)) {
            assertEquals(
                "expected NOT retryable for status $status",
                RetryClass.NOT_RETRYABLE,
                RetryClassifier.classify(null, status),
            )
        }
    }

    @Test
    fun `message evidence outranks a non-retryable status`() {
        assertEquals(RetryClass.RETRYABLE_TRANSIENT, RetryClassifier.classify("terminated", 400))
    }

    @Test
    fun `quota evidence outranks a retryable status`() {
        assertEquals(RetryClass.QUOTA_EXHAUSTED, RetryClassifier.classify("""{"code":1308}""", 429))
    }

    @Test
    fun `nothing known means not retryable`() {
        assertEquals(RetryClass.NOT_RETRYABLE, RetryClassifier.classify(null, null))
        assertEquals(RetryClass.NOT_RETRYABLE, RetryClassifier.classify("", null))
        assertEquals(RetryClass.NOT_RETRYABLE, RetryClassifier.classify("mysterious failure", null))
        assertEquals(RetryClass.NOT_RETRYABLE, RetryClassifier.classify("mysterious failure", 418))
    }

    @Test
    fun `pattern tables keep the full pi set`() {
        // Port-fidelity guard: pi retry.ts ships 54 transient alternatives and
        // 11 quota/billing blacklist alternatives. Bump both numbers together
        // with the tables.
        assertEquals(54, RetryClassifier.TRANSIENT_PATTERNS.size)
        assertEquals(11, RetryClassifier.QUOTA_BLACKLIST_PATTERNS.size)
    }

    // ── backoff ladder ──────────────────────────────────────────────────────

    @Test
    fun `exponential ladder doubles from the base delay`() {
        assertEquals(1000, RetryClassifier.exponentialDelayMs(1, 1000, 32_000))
        assertEquals(2000, RetryClassifier.exponentialDelayMs(2, 1000, 32_000))
        assertEquals(4000, RetryClassifier.exponentialDelayMs(3, 1000, 32_000))
        assertEquals(8000, RetryClassifier.exponentialDelayMs(4, 1000, 32_000))
        assertEquals(16_000, RetryClassifier.exponentialDelayMs(5, 1000, 32_000))
        assertEquals(32_000, RetryClassifier.exponentialDelayMs(6, 1000, 32_000))
    }

    @Test
    fun `ladder caps at the max delay`() {
        // Regression for pi #8826: base 2000, attempt 6, 60s cap.
        assertEquals(60_000, RetryClassifier.exponentialDelayMs(6, 2000, 60_000))
        assertEquals(5000, RetryClassifier.exponentialDelayMs(5, 2000, 5000))
        assertEquals(32_000, RetryClassifier.exponentialDelayMs(10, 1000, 32_000))
        // Far-future attempts must not overflow the shift.
        assertEquals(32_000, RetryClassifier.exponentialDelayMs(100, 1000, 32_000))
    }

    @Test
    fun `zero cap or zero base disables the wait`() {
        // pi: maxAgentDelayMs 0 means "cap at 0" (regression #8826), and
        // baseDelayMs 0 disables sleeping entirely.
        assertEquals(0, RetryClassifier.exponentialDelayMs(5, 2000, 0))
        assertEquals(0, RetryClassifier.exponentialDelayMs(5, 0, 32_000))
        assertEquals(0, RetryClassifier.backoffDelayMs(5, 0, 32_000))
    }

    @Test
    fun `attempts below one clamp to the first retry`() {
        assertEquals(1000, RetryClassifier.exponentialDelayMs(0, 1000, 32_000))
        assertEquals(1000, RetryClassifier.exponentialDelayMs(-5, 1000, 32_000))
    }

    @Test
    fun `custom base delay scales the ladder`() {
        assertEquals(500, RetryClassifier.exponentialDelayMs(1, 500, 8000))
        assertEquals(1000, RetryClassifier.exponentialDelayMs(2, 500, 8000))
        assertEquals(4000, RetryClassifier.exponentialDelayMs(4, 500, 8000))
    }

    @Test
    fun `backoff adds bounded jitter under the hard ceiling`() {
        // attempt 1: base 1000, jitter in [0, 800).
        for (i in 0 until 50) {
            val delay = RetryClassifier.backoffDelayMs(1)
            assertTrue("delay $delay below base", delay >= 1000)
            assertTrue("delay $delay above base+jitter", delay < 1800)
        }
        // attempt 5: base 16000, jitter in [0, 800), still far below the 32s cap.
        for (i in 0 until 50) {
            val delay = RetryClassifier.backoffDelayMs(5)
            assertTrue(delay >= 16_000)
            assertTrue(delay < 16_800)
        }
    }

    @Test
    fun `backoff tops out exactly at the max delay`() {
        for (attempt in 6..12) {
            assertEquals(32_000, RetryClassifier.backoffDelayMs(attempt))
        }
        // A tight cap still holds as a hard ceiling with jitter in play.
        for (i in 0 until 50) {
            val delay = RetryClassifier.backoffDelayMs(3, 1000, 4500)
            assertTrue(delay >= 4000)
            assertTrue("delay $delay exceeded cap", delay <= 4500)
        }
    }
}
