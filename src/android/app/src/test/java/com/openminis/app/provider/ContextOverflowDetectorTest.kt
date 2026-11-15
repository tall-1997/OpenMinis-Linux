package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port-fidelity tests for [ContextOverflowDetector] (pi ai/src/utils/overflow.ts).
 *
 * Every entry of [ContextOverflowDetector.OVERFLOW_PATTERNS] gets at least one
 * positive (the vendor example from pi's docs/tests) and one negative that
 * narrowly misses THAT pattern while staying clear of every other table entry.
 * The exclusion table, the HTTP 413 status fallback, the Cerebras gate, and
 * the silent/truncation overflow paths are covered separately.
 */
class ContextOverflowDetectorTest {

    // ── table shape ─────────────────────────────────────────────────────────

    @Test
    fun `pattern tables keep the full pi set`() {
        // Port-fidelity guard: pi overflow.ts ships 27 overflow entries and 3
        // non-overflow exclusions. Bump both numbers together with the table.
        assertEquals(27, ContextOverflowDetector.OVERFLOW_PATTERNS.size)
        assertEquals(3, ContextOverflowDetector.NON_OVERFLOW_PATTERNS.size)
    }

    // ── per-pattern positives / negatives ───────────────────────────────────

    @Test
    fun `each overflow pattern matches its vendor example and rejects near-miss wording`() {
        val cases = listOf(
            // 1 - Anthropic and z.ai token overflow
            "prompt is too long: 213462 tokens > 200000 maximum" to
                "Your prompt violates the usage policy",
            // 2 - z.ai CN endpoint token overflow
            """400 {"code":"1261","message":"Prompt exceeds max length"}""" to
                """400 {"code":"1261","message":"Prompt exceeds the per-request byte cap"}""",
            // 3 - Anthropic request byte-size overflow (HTTP 413)
            """413 {"error":{"type":"request_too_large","message":"Request exceeds the maximum size"}}""" to
                """{"error":{"type":"invalid_request_error","message":"Request exceeds the maximum size"}}""",
            // 4 - Amazon Bedrock
            "The input is too long for requested model claude-v4-6" to
                "The input is too long; shorten it before requesting the model again",
            // 5 - OpenAI (Completions & Responses API)
            "Your input exceeds the context window of this model" to
                "Your input exceeds the token budget window of this model",
            // 6 - OpenAI-compatible proxies (LiteLLM), "of N tokens" form
            "Requested token count exceeds the model's maximum context length of 131072 tokens." to
                "Requested token count exceeds the model's maximum context length budget",
            // 6b - OpenAI-compatible proxies, parenthesized form
            "Error: 400 Input length (265330) exceeds model's maximum context length (262144)." to
                "Error: 400 Input length (265330) exceeds the model's documented budget",
            // 7 - LiteLLM pre-call check (ContextWindowExceededError)
            "litellm.ContextWindowExceededError: Context Window exceeded for given call." to
                "context window check passed but the call failed",
            // 8 - Google (Gemini)
            "The input token count (1196265) exceeds the maximum number of tokens allowed (1048575)" to
                "The input token count (500) is below the maximum",
            // 9 - xAI (Grok)
            "This model's maximum prompt length is 131072 but the request contains 537812 tokens" to
                "This model's maximum prompt length is unknown",
            // 10 - Groq
            "Please reduce the length of the messages or completion" to
                "Please reduce the number of the messages",
            // 11 - OpenRouter (most backends)
            "This endpoint's maximum context length is 65536 tokens. However, you requested about 70000 tokens" to
                "This endpoint's maximum context length is configurable",
            // 12 - OpenRouter/Poolside
            "Input length 131393 exceeds the maximum allowed input length of 131040 tokens." to
                "Input length 131393 exceeds the maximum allowed output length of 131040 tokens.",
            // 13 - Together AI
            "400 The input (516368 tokens) is longer than the model's context length (262144 tokens)." to
                "400 The input (516368 tokens) is shorter than the model's context length (262144 tokens).",
            // 14 - GitHub Copilot
            "prompt token count of 5000 exceeds the limit of 4096" to
                "prompt token count of 5000 exceeds the limit",
            // 15 - llama.cpp server
            "the request exceeds the available context size, try increasing it" to
                "the request exceeds the available disk size",
            // 16 - LM Studio
            "tokens to keep from the initial prompt is greater than the context length" to
                "tokens to keep from the initial prompt is equal to the context length",
            // 17 - MiniMax
            "invalid params, context window exceeds limit" to
                "invalid params, context window parameter missing",
            // 18 - Kimi For Coding
            "Your request exceeded model token limit: 200000 (requested: 210000)" to
                "Your request exceeded the per-minute request limit",
            // 19 - Mistral
            "Prompt contains 100000 tokens and is too large for model with 8192 maximum context length" to
                "Prompt contains 100000 tokens and is too large for model with unknown maximum context length",
            // 20 - DS4 server
            "400 Prompt has 256468 tokens, but the configured context size is 256000 tokens" to
                "400 Prompt has 256468 tokens, and the configured context size is 256000 tokens",
            // 20b - DS4 server, comma-grouped numbers
            "Prompt has 5,958,968 tokens, but the configured context size is 256,000 tokens" to
                "Prompt has 5958968 tokens, but the configured context size is unknown",
            // 21 - z.ai non-standard finish_reason surfaced as error text
            """{"error":{"code":"model_context_window_exceeded"}}""" to
                """{"error":{"code":"model_context_window_ok"}}""",
            // 22 - Ollama explicit overflow error
            "400 `prompt too long; exceeded max context length by 100918 tokens`" to
                "400 `prompt rejected; exceeded max retries`",
            // 23 - DashScope / Qwen Token Plan
            "Range of input length should be [1, 6144]" to
                "Range of input temperature should be [0, 2]",
            // 24 - Generic fallback
            "context_length_exceeded" to
                "context_length_ok",
            // 25 - Generic fallback
            "The request contained too many tokens" to
                "The request contained too few tokens",
            // 26 - Generic fallback
            "token limit exceeded for this key" to
                "token limit documented in the readme",
        )
        for ((positive, negative) in cases) {
            val pos = ContextOverflowDetector.detectFromError(null, positive)
            assertTrue("expected overflow for: $positive", pos.isOverflow)
            val neg = ContextOverflowDetector.detectFromError(null, negative)
            assertFalse("expected NO overflow for: $negative", neg.isOverflow)
        }
    }

    // ── HTTP 413 prefix pattern (table entry 27) ────────────────────────────

    @Test
    fun `provider-independent request-size overflow via 413 prefix`() {
        val positives = listOf(
            // formatProviderError prefix
            """github-copilot API error (413): {"message":"failed to parse request","code":""}""",
            // any provider, not just Copilot
            "custom-provider API error (413): Payload Too Large",
            // raw SDK error
            """413 {"error":{"message":"failed to parse request","code":""}}""",
            // bare status at end of string
            "413",
            // status followed by colon
            "413: payload too large",
        )
        for (body in positives) {
            // The request can exceed the byte limit even with a huge token window.
            assertTrue(
                "expected overflow for: $body",
                ContextOverflowDetector.detectFromError(null, body).isOverflow,
            )
        }
    }

    @Test
    fun `unrelated errors are not mistaken for request-size overflow`() {
        val negatives = listOf(
            // other status, same body
            """github-copilot API error (400): {"message":"failed to parse request","code":""}""",
            // 413 in the body only
            """github-copilot API error (400): {"message":"failed to parse request: invalid value 413"}""",
            // 413 not at start
            "custom-provider API error (400): upstream mentioned OpenAI API error (413): invalid input",
            // 413 is a prefix, not the status
            "custom-provider API error (4130): invalid input",
            // bare 4130 is not 413
            "4130 failed to parse request",
        )
        for (body in negatives) {
            assertFalse(
                "expected NO overflow for: $body",
                ContextOverflowDetector.detectFromError(null, body).isOverflow,
            )
        }
    }

    // ── exclusion table ─────────────────────────────────────────────────────

    @Test
    fun `exclusion table vetoes overflow wording`() {
        // Bedrock returns this for HTTP 429 rate limiting, NOT context overflow;
        // the body would match /too many tokens/ without the exclusion.
        val throttling = ContextOverflowDetector.detectFromError(
            null,
            "Throttling error: Too many tokens, please wait before trying again.",
        )
        assertTrue(throttling is OverflowVerdict.Excluded)
        assertEquals(
            "^(Throttling error|Service unavailable):",
            (throttling as OverflowVerdict.Excluded).matchedPattern,
        )

        val serviceDown = ContextOverflowDetector.detectFromError(
            null,
            "Service unavailable: The service is temporarily unavailable.",
        )
        assertTrue(serviceDown is OverflowVerdict.Excluded)

        val rateLimit = ContextOverflowDetector.detectFromError(
            null,
            "Rate limit exceeded, please retry after 30 seconds.",
        )
        assertTrue(rateLimit is OverflowVerdict.Excluded)
        assertEquals("rate limit", (rateLimit as OverflowVerdict.Excluded).matchedPattern)
    }

    @Test
    fun `HTTP 429 style errors are not overflow`() {
        val verdict = ContextOverflowDetector.detectFromError(429, "Too many requests. Please slow down.")
        // The exclusion table fires before anything else, so even a body that
        // trips a generic overflow pattern stays non-overflow on a 429.
        assertTrue(verdict is OverflowVerdict.Excluded)
        assertFalse(verdict.isOverflow)
    }

    @Test
    fun `exclusion veto outranks the 413 status fallback`() {
        // Practically unreachable combination, but the precedence is fixed:
        // exclusions are checked before every overflow signal (pi order).
        val verdict = ContextOverflowDetector.detectFromError(413, "Too many requests. Please slow down.")
        assertTrue(verdict is OverflowVerdict.Excluded)
    }

    @Test
    fun `generic non-overflow provider errors are not overflow`() {
        val verdict = ContextOverflowDetector.detectFromError(500, "500 `model runner crashed unexpectedly`")
        assertTrue(verdict is OverflowVerdict.NoMatch)
    }

    // ── HTTP 413 status fallback ────────────────────────────────────────────

    @Test
    fun `status 413 alone is request-size overflow`() {
        for (body in listOf<String?>(null, "", "   ", "mysterious gateway failure")) {
            val verdict = ContextOverflowDetector.detectFromError(413, body)
            assertTrue("expected overflow for body=$body", verdict.isOverflow)
            val overflow = verdict as OverflowVerdict.Overflow
            assertEquals(ContextOverflowDetector.Source.HTTP_413, overflow.source)
            assertNull(overflow.matchedPattern)
        }
    }

    @Test
    fun `other statuses with unrecognizable bodies are not overflow`() {
        for (status in listOf(400, 401, 403, 404, 408, 429, 500, 502, 503, null)) {
            assertFalse(
                "expected NO overflow for status=$status",
                ContextOverflowDetector.detectFromError(status, "mysterious gateway failure").isOverflow,
            )
            assertFalse(
                "expected NO overflow for empty body, status=$status",
                ContextOverflowDetector.detectFromError(status, null).isOverflow,
            )
        }
    }

    // ── detectFromLlmError: bracketed status prefix recovery ────────────────
    //
    // LLMProvider's shared HTTP error mapper (mapHttpErrorShared) formats
    // ProviderError/TransientError details as "[413] body"; the line-anchored
    // 413 table entry cannot see the status inside the brackets, so the
    // status must be carried through detectFromError's statusCode param.

    @Test
    fun `llm error with a 413 status prefix is request-size overflow`() {
        // The dead path this guards: without prefix recovery the status is lost
        // and the body "payload too large" matches nothing.
        val verdict = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("[413] payload too large"),
        )
        assertTrue("$verdict", verdict.isOverflow)
        val overflow = verdict as OverflowVerdict.Overflow
        assertEquals(ContextOverflowDetector.Source.HTTP_413, overflow.source)
        assertNull(overflow.matchedPattern)

        // The same shape on the transient side: the mapper stamps 5xx details
        // with "[503] …" and the prefix must not be mistaken for an overflow.
        val transient = ContextOverflowDetector.detectFromLlmError(
            LLMError.TransientError("[503] backend temporarily unavailable"),
        )
        assertEquals(OverflowVerdict.NoMatch, transient)
    }

    @Test
    fun `llm error with a non-413 status prefix and no signal is no match`() {
        val verdict = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("[400] no overflow signal"),
        )
        assertEquals(OverflowVerdict.NoMatch, verdict)

        // The suffix may still claim a 413 the way raw SDK bodies do (the
        // line-anchored table entry is designed for exactly that), while the
        // recovered status parameter (400) stays irrelevant for the verdict.
        val stray413 = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("[400] 413 upstream was not the status"),
        )
        assertTrue("$stray413", stray413 is OverflowVerdict.Overflow)
    }

    @Test
    fun `llm error body text still routes through the overflow table`() {
        // A recognized wording outranks the status fallback (pi order), even
        // when the status itself is a 413.
        val verdict = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("[413] prompt is too long: 213462 tokens > 200000 maximum"),
        )
        val overflow = verdict as OverflowVerdict.Overflow
        assertEquals(ContextOverflowDetector.Source.ERROR_PATTERN, overflow.source)
        assertEquals("prompt (?:is )?too long", overflow.matchedPattern)

        // The exclusion table vetoes just like it does on a raw body.
        val vetoed = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("[413] Too many requests, please slow down"),
        )
        assertTrue(vetoed is OverflowVerdict.Excluded)
        assertFalse(vetoed.isOverflow)
    }

    // ── detectFromLlmError: "HTTP CODE: body" prefix recovery ───────────────
    //
    // mapHttpErrorShared's NON-JSON / empty-body path formats the SAME mapper's
    // message as "HTTP <code>: <masked body>" — the bracket regex cannot see
    // that status, so without the second shape every non-JSON 413 stayed a
    // dead path (status null, body matched nothing).

    @Test
    fun `llm error with an HTTP-form 413 prefix is request-size overflow`() {
        // The exact mapper assembly for a non-JSON 413 body.
        val verdict = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("HTTP 413: payload too large"),
        )
        assertTrue("$verdict", verdict.isOverflow)
        val overflow = verdict as OverflowVerdict.Overflow
        assertEquals(ContextOverflowDetector.Source.HTTP_413, overflow.source)
        assertNull(overflow.matchedPattern)

        // Empty-body variant (mask of an empty excerpt leaves just the prefix).
        val bodyless = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("HTTP 413: "),
        )
        assertTrue("$bodyless", bodyless.isOverflow)
        assertEquals(ContextOverflowDetector.Source.HTTP_413, (bodyless as OverflowVerdict.Overflow).source)
    }

    @Test
    fun `llm error with an HTTP-form non-413 status and no signal is no match`() {
        assertEquals(
            OverflowVerdict.NoMatch,
            ContextOverflowDetector.detectFromLlmError(LLMError.ProviderError("HTTP 400: unknown")),
        )
        // The transient side stamps 5xx details with the same shape.
        assertEquals(
            OverflowVerdict.NoMatch,
            ContextOverflowDetector.detectFromLlmError(
                LLMError.TransientError("HTTP 503: backend temporarily unavailable"),
            ),
        )
    }

    @Test
    fun `HTTP-form prefix body still routes through the overflow tables`() {
        // A recognized wording outranks the recovered status (pi order).
        val worded = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("HTTP 413: prompt is too long: 213462 tokens > 200000 maximum"),
        )
        val overflow = worded as OverflowVerdict.Overflow
        assertEquals(ContextOverflowDetector.Source.ERROR_PATTERN, overflow.source)
        assertEquals("prompt (?:is )?too long", overflow.matchedPattern)

        // The exclusion table vetoes on this shape too.
        val vetoed = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("HTTP 413: Too many requests, please slow down"),
        )
        assertTrue(vetoed is OverflowVerdict.Excluded)
        assertFalse(vetoed.isOverflow)
    }

    @Test
    fun `label-shaped prefixes keep the status-null behavior`() {
        // A custom messageLabel (Anthropic's error type) matches neither numeric
        // regex, so the status stays null — and the body text alone decides.
        // request_too_large is an OVERFLOW_PATTERNS entry, so this 413-class
        // answer is still caught, via the body table rather than the status.
        val labeled = ContextOverflowDetector.detectFromLlmError(
            LLMError.ProviderError("[request_too_large] Request exceeds the maximum size"),
        )
        val overflow = labeled as OverflowVerdict.Overflow
        assertEquals(ContextOverflowDetector.Source.ERROR_PATTERN, overflow.source)
        assertEquals("request_too_large", overflow.matchedPattern)

        // A label whose body carries no overflow wording still ends in NoMatch
        // (no accidental status recovery from the bracket shape).
        assertEquals(
            OverflowVerdict.NoMatch,
            ContextOverflowDetector.detectFromLlmError(
                LLMError.ProviderError("[invalid_request_error] something unrelated"),
            ),
        )
    }

    // ── Cerebras bodyless gate ──────────────────────────────────────────────

    @Test
    fun `bodyless 400 answers are overflow only for Cerebras`() {
        val body = "400 status code (no body)"
        val cerebras = ContextOverflowDetector.detectFromError(400, body, provider = "cerebras")
        assertTrue(cerebras.isOverflow)
        val overflow = cerebras as OverflowVerdict.Overflow
        assertEquals(ContextOverflowDetector.Source.CEREBRAS_BODYLESS, overflow.source)
        assertEquals(ContextOverflowDetector.CEREBRAS_BODYLESS_OVERFLOW_PATTERN.pattern, overflow.matchedPattern)

        // Regression pi #9482: other providers must not read a bodyless 400 as overflow.
        assertFalse(ContextOverflowDetector.detectFromError(400, body, provider = "opencode-go").isOverflow)
        assertFalse(ContextOverflowDetector.detectFromError(400, body, provider = null).isOverflow)
    }

    @Test
    fun `cerebras bodyless pattern accepts the bare 413 form`() {
        val verdict = ContextOverflowDetector.detectFromError(null, "413(no body)", provider = "Cerebras")
        assertTrue(verdict.isOverflow)
        assertEquals(ContextOverflowDetector.Source.CEREBRAS_BODYLESS, (verdict as OverflowVerdict.Overflow).source)
    }

    // ── verdict metadata ────────────────────────────────────────────────────

    @Test
    fun `overflow verdict carries the matched pattern source`() {
        val verdict = ContextOverflowDetector.detectFromError(null, "prompt is too long: 213462 tokens > 200000 maximum")
        val overflow = verdict as OverflowVerdict.Overflow
        assertEquals(ContextOverflowDetector.Source.ERROR_PATTERN, overflow.source)
        assertEquals("prompt (?:is )?too long", overflow.matchedPattern)
        assertTrue(overflow.isOverflow)
    }

    // ── silent overflow (z.ai style) ────────────────────────────────────────

    @Test
    fun `usage above the window is a silent overflow`() {
        // z.ai regression pi #9805 family: success reported but prompt side over the window.
        assertTrue(ContextOverflowDetector.detectFromUsage(1_048_577, 0, 1_048_576))
        // cacheRead counts toward the prompt side.
        assertTrue(ContextOverflowDetector.detectFromUsage(1_000_000, 48_577, 1_048_576))
    }

    @Test
    fun `usage at or below the window is not overflow`() {
        assertFalse(ContextOverflowDetector.detectFromUsage(1_000_000, 0, 1_048_576))
        // Strictly greater-than: exactly the window is fine.
        assertFalse(ContextOverflowDetector.detectFromUsage(1_048_576, 0, 1_048_576))
        assertFalse(ContextOverflowDetector.detectFromUsage(1_000_000, 48_575, 1_048_576))
    }

    @Test
    fun `unknown window disables silent overflow detection`() {
        assertFalse(ContextOverflowDetector.detectFromUsage(9_999_999, 0, 0))
    }

    // ── truncation overflow (Xiaomi MiMo style) ─────────────────────────────

    @Test
    fun `length stop with zero output at a filled window is truncation overflow`() {
        // pi test: input 58 + cacheRead 1048512 = 1048570 fills a 1048576 window, output 0.
        assertTrue(
            ContextOverflowDetector.detectTruncationOverflow(
                finishReason = "length",
                outputTokens = 0,
                inputTokens = 1_048_570,
                contextWindow = 1_048_576,
            ),
        )
    }

    @Test
    fun `anthropic-native max_tokens spelling is a length stop`() {
        assertTrue(
            ContextOverflowDetector.detectTruncationOverflow("max_tokens", 0, 1_048_576, 1_048_576),
        )
        // Case-insensitive.
        assertTrue(
            ContextOverflowDetector.detectTruncationOverflow("Length", 0, 1_048_576, 1_048_576),
        )
    }

    @Test
    fun `length stop with output or a far-below window input is not truncation overflow`() {
        // Normal length stop: the model actually produced output.
        assertFalse(ContextOverflowDetector.detectTruncationOverflow("length", 4096, 1000, 200_000))
        // Zero output but the input nowhere near fills the window.
        assertFalse(ContextOverflowDetector.detectTruncationOverflow("length", 0, 100, 200_000))
        // Non-length stop.
        assertFalse(ContextOverflowDetector.detectTruncationOverflow("stop", 0, 1_048_576, 1_048_576))
        assertFalse(ContextOverflowDetector.detectTruncationOverflow(null, 0, 1_048_576, 1_048_576))
    }

    @Test
    fun `truncation threshold is 99 percent of the window`() {
        // window=1000: ceil(99*1000/100) = 990 → input 990 qualifies, 989 does not.
        assertTrue(ContextOverflowDetector.detectTruncationOverflow("length", 0, 990, 1_000))
        assertFalse(ContextOverflowDetector.detectTruncationOverflow("length", 0, 989, 1_000))
    }

    @Test
    fun `unknown window disables truncation overflow detection`() {
        assertFalse(ContextOverflowDetector.detectTruncationOverflow("length", 0, 9_999_999, 0))
    }

    // ── recoverable length stop ─────────────────────────────────────────────

    @Test
    fun `length stop below the desired output limit is recoverable`() {
        // pi test: output 16 of a desired 128000.
        assertTrue(ContextOverflowDetector.isRecoverableLength("length", 16, 128_000))
        // Zero output is recoverable without context metadata.
        assertTrue(ContextOverflowDetector.isRecoverableLength("length", 0, 128_000))
        // Anthropic/Gemini spelling.
        assertTrue(ContextOverflowDetector.isRecoverableLength("max_tokens", 16, 128_000))
    }

    @Test
    fun `length stop that reached the desired limit is not recoverable`() {
        assertFalse(ContextOverflowDetector.isRecoverableLength("length", 1024, 1_024))
        assertFalse(ContextOverflowDetector.isRecoverableLength("length", 2048, 1_024))
    }

    @Test
    fun `recoverable check needs a positive desired limit and a length stop`() {
        assertFalse(ContextOverflowDetector.isRecoverableLength("length", 16, 0))
        assertFalse(ContextOverflowDetector.isRecoverableLength("length", 16, -1))
        assertFalse(ContextOverflowDetector.isRecoverableLength("stop", 16, 128_000))
        assertFalse(ContextOverflowDetector.isRecoverableLength(null, 16, 128_000))
    }

    // ── success-path combined gate (T-context-overflow-usage wiring) ────────

    @Test
    fun `combined gate flags silent usage overflow`() {
        // z.ai style: "successful" reply whose reported prompt side is over
        // the window. Stop reason is irrelevant for this family.
        assertTrue(
            ContextOverflowDetector.isSuccessfulOverflow(
                finishReason = "stop",
                outputTokens = 512,
                inputTokens = 213_000L,
                contextWindow = 200_000,
            ),
        )
    }

    @Test
    fun `combined gate flags truncation overflow`() {
        // Xiaomi MiMo style: length stop, zero output, input fills the window.
        assertTrue(
            ContextOverflowDetector.isSuccessfulOverflow(
                finishReason = "length",
                outputTokens = 0,
                inputTokens = 200_000L,
                contextWindow = 200_000,
            ),
        )
        // Anthropic/Gemini spelling of the same stop.
        assertTrue(
            ContextOverflowDetector.isSuccessfulOverflow(
                finishReason = "max_tokens",
                outputTokens = 0,
                inputTokens = 198_500L,
                contextWindow = 200_000,
            ),
        )
    }

    @Test
    fun `combined gate passes healthy successful responses through`() {
        // Normal reply well inside the window.
        assertFalse(
            ContextOverflowDetector.isSuccessfulOverflow(
                finishReason = "stop",
                outputTokens = 512,
                inputTokens = 90_000L,
                contextWindow = 200_000,
            ),
        )
        // A genuine output-budget length stop (output > 0) is NOT overflow —
        // the truncation family requires zero output.
        assertFalse(
            ContextOverflowDetector.isSuccessfulOverflow(
                finishReason = "length",
                outputTokens = 4_096,
                inputTokens = 199_000L,
                contextWindow = 200_000,
            ),
        )
        // Truncation-shaped reply but the window was not full.
        assertFalse(
            ContextOverflowDetector.isSuccessfulOverflow(
                finishReason = "length",
                outputTokens = 0,
                inputTokens = 120_000L,
                contextWindow = 200_000,
            ),
        )
        // Unknown window disables the whole gate.
        assertFalse(
            ContextOverflowDetector.isSuccessfulOverflow(
                finishReason = "length",
                outputTokens = 0,
                inputTokens = 999_999L,
                contextWindow = 0,
            ),
        )
    }

    // ── success-path kind split (P0 fix: SILENT never retries) ──────────────

    @Test
    fun `kind splits silent from truncation families`() {
        // z.ai style: natural stop + over-window prompt side → SILENT.
        assertEquals(
            ContextOverflowDetector.SuccessfulOverflowKind.SILENT,
            ContextOverflowDetector.successfulOverflowKind("stop", 512, 213_000L, 200_000),
        )
        // Anthropic/Gemini natural-stop spellings count for the silent family.
        assertEquals(
            ContextOverflowDetector.SuccessfulOverflowKind.SILENT,
            ContextOverflowDetector.successfulOverflowKind("end_turn", 512, 213_000L, 200_000),
        )
        // Xiaomi MiMo style: length + zero output on a full window → TRUNCATED
        // (even though the prompt side alone is over the window too).
        assertEquals(
            ContextOverflowDetector.SuccessfulOverflowKind.TRUNCATED,
            ContextOverflowDetector.successfulOverflowKind("length", 0, 213_000L, 200_000),
        )
    }

    @Test
    fun `silent family is gated on natural stop finishes like pi`() {
        // pi: `stopReason === "stop"` — a response that ended any other way is
        // not trusted as a clean "answered successfully" report.
        assertNull(
            ContextOverflowDetector.successfulOverflowKind("tool_calls", 512, 213_000L, 200_000),
        )
        assertNull(
            ContextOverflowDetector.successfulOverflowKind("tool_use", 512, 213_000L, 200_000),
        )
        assertNull(
            ContextOverflowDetector.successfulOverflowKind(null, 512, 213_000L, 200_000),
        )
        // BUT the truncation family does not need the stop gate: length +
        // zero output stays TRUNCATED regardless of the spelling family.
        assertEquals(
            ContextOverflowDetector.SuccessfulOverflowKind.TRUNCATED,
            ContextOverflowDetector.successfulOverflowKind("max_tokens", 0, 213_000L, 200_000),
        )
    }
}
