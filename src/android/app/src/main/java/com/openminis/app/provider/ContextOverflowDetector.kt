package com.openminis.app.provider

/**
 * Context-overflow detection for provider error bodies and usage reports.
 * Port of pi `ai/src/utils/overflow.ts` (packages/ai, MIT) — the three
 * detection families and the exclusion table are translated 1:1:
 *
 *  1. **Error-text overflow** — most providers answer an oversized prompt with
 *     a recognizable error message; [detectFromError] matches the body.
 *  2. **Silent overflow** — some providers (z.ai) accept an oversized request
 *     and answer "successfully"; [detectFromUsage] catches it because the
 *     reported prompt tokens exceed the context window.
 *  3. **Truncation overflow** — some deployments (Xiaomi MiMo) silently clip
 *     the input to fill the window exactly and return a length stop with zero
 *     output (no room left to generate); [detectTruncationOverflow] catches it.
 *
 * [NON_OVERFLOW_PATTERNS] vetoes overflow detection for rate-limiting and
 * server-error wording that would otherwise collide with a generic overflow
 * pattern (Bedrock formats throttling as "Throttling error: Too many tokens,
 * please wait before trying again." which matches /too many tokens/).
 *
 * Pure JVM logic — no Android dependency, safe for unit tests. The verdicts
 * feed the compact/offload policy (ContextPolicy's EXHAUSTED tier), so a
 * false positive here forces an unnecessary compact and a false negative
 * burns one doomed request; the exclusion table exists to keep both rare.
 */
object ContextOverflowDetector {

    /** HTTP 413 — request byte-size overflow regardless of provider or body text. */
    private const val HTTP_REQUEST_ENTITY_TOO_LARGE = 413

    /** Provider id whose bodyless 400/413 answers are a known overflow signal. */
    private const val CEREBRAS_PROVIDER = "cerebras"

    /**
     * The HTTP status prefix [com.openminis.app.provider.LLMProvider]'s shared
     * error mapper (mapHttpErrorShared, `messageLabel` default "[CODE]")
     * stamps onto ProviderError/TransientError details. Line-anchored so a
     * status mentioned mid-body is NOT taken as the response status.
     */
    private val BRACKETED_STATUS_PREFIX = Regex("""^\[(\d{3})\]""")

    /**
     * The second deterministic status shape the SAME shared error mapper
     * ([com.openminis.app.provider.LLMProvider].mapHttpErrorShared) emits when
     * the provider body is NOT parseable JSON (or is empty):
     * `"HTTP <code>: <masked body excerpt>"`. Same trust level as the bracket
     * form — it is the mapper's own assembly of the proven status, not
     * provider-controlled text. Only the colon-carrying form is recognized:
     * the mapper's other shapes ("HTTP 401 — …", "HTTP 403 forbidden — …")
     * have no colon after the status, are 401/403 auth errors by construction,
     * and never need overflow recovery. Line-anchored like the bracket form:
     * a status mentioned mid-body is NOT taken as the response status.
     */
    private val HTTP_STATUS_PREFIX = Regex("""^HTTP (\d{3}):""")

    /**
     * Regex patterns to detect context overflow errors from different providers.
     *
     * These patterns match error messages returned when the input exceeds
     * the model's context window or the provider's request byte-size limit.
     * Vendor annotations are carried over from pi overflow.ts (with example
     * error messages):
     *
     * - Anthropic: "prompt is too long: 213462 tokens > 200000 maximum"
     * - Anthropic: "413 {"error":{"type":"request_too_large","message":"Request exceeds the maximum size"}}"
     * - OpenAI: "Your input exceeds the context window of this model"
     * - OpenAI/LiteLLM: "Requested token count exceeds the model's maximum context length of 131072 tokens"
     * - LiteLLM pre-call check: "litellm.ContextWindowExceededError: ... Context Window exceeded for given call."
     * - OpenAI-compatible: "Input length (265330) exceeds model's maximum context length (262144)."
     * - Google: "The input token count (1196265) exceeds the maximum number of tokens allowed (1048575)"
     * - xAI: "This model's maximum prompt length is 131072 but the request contains 537812 tokens"
     * - Groq: "Please reduce the length of the messages or completion"
     * - OpenRouter: "This endpoint's maximum context length is X tokens. However, you requested about Y tokens"
     * - OpenRouter/Poolside: "Input length X exceeds the maximum allowed input length of Y tokens."
     * - Together AI: "The input (X tokens) is longer than the model's context length (Y tokens)."
     * - llama.cpp: "the request exceeds the available context size, try increasing it"
     * - LM Studio: "tokens to keep from the initial prompt is greater than the context length"
     * - GitHub Copilot: "prompt token count of X exceeds the limit of Y"
     * - GitHub Copilot: 'github-copilot API error (413): {"message":"failed to parse request","code":""}'
     * - MiniMax: "invalid params, context window exceeds limit"
     * - Kimi For Coding: "Your request exceeded model token limit: X (requested: Y)"
     * - DS4: "Prompt has X tokens, but the configured context size is Y tokens"
     * - Cerebras: "400/413 status code (no body)" (provider-gated, see below)
     * - Mistral: "Prompt contains X tokens ... too large for model with Y maximum context length"
     * - z.ai: {"code":"1261","message":"Prompt too long"}, {"code":"1261","message":"Prompt exceeds max length"}
     *   (CN endpoint), or silent overflow via usage.input > contextWindow
     * - Xiaomi MiMo: truncates input to fill contextWindow exactly, then returns
     *   finish_reason "length" with output=0 (no room left to generate) — see
     *   [detectTruncationOverflow]
     * - DashScope/Qwen: "Range of input length should be [1, X]" (HTTP 400 invalid_parameter_error)
     * - Ollama: some deployments truncate silently, others return errors like
     *   "prompt too long; exceeded max context length by X tokens"
     *
     * Exposed for tests and for callers that want to pre-screen a message
     * themselves (pi's getOverflowPatterns()).
     */
    val OVERFLOW_PATTERNS: List<Regex> = listOf(
        Regex("""prompt (?:is )?too long""", RegexOption.IGNORE_CASE), // Anthropic and z.ai token overflow
        Regex("""prompt exceeds max length""", RegexOption.IGNORE_CASE), // z.ai CN endpoint token overflow
        Regex("""request_too_large""", RegexOption.IGNORE_CASE), // Anthropic request byte-size overflow (HTTP 413)
        Regex("""input is too long for requested model""", RegexOption.IGNORE_CASE), // Amazon Bedrock
        Regex("""exceeds the context window""", RegexOption.IGNORE_CASE), // OpenAI (Completions & Responses API)
        Regex(
            """exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\d,]+ tokens?|\s*\([\d,]+\))""",
            RegexOption.IGNORE_CASE,
        ), // OpenAI-compatible proxies (LiteLLM)
        Regex("""context window exceeded""", RegexOption.IGNORE_CASE), // LiteLLM pre-call check (ContextWindowExceededError)
        Regex("""input token count.*exceeds the maximum""", RegexOption.IGNORE_CASE), // Google (Gemini)
        Regex("""maximum prompt length is \d+""", RegexOption.IGNORE_CASE), // xAI (Grok)
        Regex("""reduce the length of the messages""", RegexOption.IGNORE_CASE), // Groq
        Regex("""maximum context length is \d+ tokens""", RegexOption.IGNORE_CASE), // OpenRouter (most backends)
        Regex(
            """exceeds (?:the )?maximum allowed input length of [\d,]+ tokens?""",
            RegexOption.IGNORE_CASE,
        ), // OpenRouter/Poolside
        Regex(
            """input \(\d+ tokens\) is longer than the model'?s context length \(\d+ tokens\)""",
            RegexOption.IGNORE_CASE,
        ), // Together AI
        Regex("""exceeds the limit of \d+""", RegexOption.IGNORE_CASE), // GitHub Copilot
        Regex("""exceeds the available context size""", RegexOption.IGNORE_CASE), // llama.cpp server
        Regex("""greater than the context length""", RegexOption.IGNORE_CASE), // LM Studio
        Regex("""context window exceeds limit""", RegexOption.IGNORE_CASE), // MiniMax
        Regex("""exceeded model token limit""", RegexOption.IGNORE_CASE), // Kimi For Coding
        Regex("""too large for model with \d+ maximum context length""", RegexOption.IGNORE_CASE), // Mistral
        Regex(
            """prompt has [\d,]+ tokens?, but the configured context size is [\d,]+ tokens?""",
            RegexOption.IGNORE_CASE,
        ), // DS4 server
        Regex("""model_context_window_exceeded""", RegexOption.IGNORE_CASE), // z.ai non-standard finish_reason surfaced as error text
        Regex("""prompt too long; exceeded (?:max )?context length""", RegexOption.IGNORE_CASE), // Ollama explicit overflow error
        Regex("""range of input length should be""", RegexOption.IGNORE_CASE), // DashScope / Qwen Token Plan
        Regex("""context[_ ]length[_ ]exceeded""", RegexOption.IGNORE_CASE), // Generic fallback
        Regex("""too many tokens""", RegexOption.IGNORE_CASE), // Generic fallback
        Regex("""token limit exceeded""", RegexOption.IGNORE_CASE), // Generic fallback
        // HTTP 413 means request-size overflow regardless of provider or body text.
        // Match raw SDK errors and formatProviderError's prefix, not a status mentioned in the body.
        Regex("""^(?:[\w -]+ API error \(413\):|413(?:\s|:|$))""", RegexOption.IGNORE_CASE),
    )

    /**
     * Bodyless 400/413 answers are an overflow signal ONLY for Cerebras
     * (pi regression #9482: HTTP 413 is request-size overflow for every
     * provider, but a bodyless HTTP 400 is only known to indicate overflow
     * for Cerebras). Gated on the provider id in [detectFromError].
     */
    val CEREBRAS_BODYLESS_OVERFLOW_PATTERN: Regex =
        Regex("""^4(?:00|13)\s*(?:status code)?\s*\(no body\)""", RegexOption.IGNORE_CASE)

    /**
     * Patterns that indicate non-overflow errors (e.g. rate limiting, server
     * errors). Error messages matching any of these are excluded from overflow
     * detection even if they also match an [OVERFLOW_PATTERNS] entry.
     *
     * Example: Bedrock formats throttling errors as "ThrottlingException: Too
     * many tokens, please wait before trying again." which would match the
     * /too many tokens/i overflow pattern without this exclusion.
     */
    val NON_OVERFLOW_PATTERNS: List<Regex> = listOf(
        // AWS Bedrock non-overflow errors (human-readable prefixes from formatBedrockError)
        Regex("""^(Throttling error|Service unavailable):""", RegexOption.IGNORE_CASE),
        Regex("""rate limit""", RegexOption.IGNORE_CASE), // Generic rate limiting
        Regex("""too many requests""", RegexOption.IGNORE_CASE), // Generic HTTP 429 style
    )

    /** Where an [OverflowVerdict.Overflow] verdict came from. */
    enum class Source {
        /** Body text matched an [OVERFLOW_PATTERNS] entry. */
        ERROR_PATTERN,

        /** [statusCode] was 413 and the body gave no counter-evidence. */
        HTTP_413,

        /** Body matched the provider-gated Cerebras bodyless-status pattern. */
        CEREBRAS_BODYLESS,
    }

    /**
     * Error-based overflow detection (pi's isContextOverflow case 1, reshaped
     * for callers that hold the HTTP status and the body separately instead of
     * one formatted error string).
     *
     * Call this ONLY for a failed turn (pi gates on stopReason === "error");
     * successful turns go through [detectFromUsage] / [detectTruncationOverflow].
     *
     * Order of authority:
     *  1. [NON_OVERFLOW_PATTERNS] on [body] — a veto wins over everything (pi
     *     checks exclusions before every overflow signal).
     *  2. [OVERFLOW_PATTERNS] on [body] — the body may already contain the
     *     status prefix ("413 {...}") which the last table entry handles.
     *  3. The provider-gated Cerebras bodyless-status pattern.
     *  4. [statusCode] == 413 — request-size overflow regardless of body text;
     *     covers the empty-body and unrecognized-body cases. A 413 mentioned
     *     only inside a body carried by a different status does NOT reach here
     *     (that is exactly what the ^-anchored table entry rejects).
     *
     * @param provider provider id (e.g. "cerebras"), used only to gate the
     *   bodyless-400 overflow signal; null/other ids disable it.
     */
    fun detectFromError(statusCode: Int?, body: String?, provider: String? = null): OverflowVerdict {
        val text = body?.trim()
        if (!text.isNullOrEmpty()) {
            NON_OVERFLOW_PATTERNS.firstOrNull { it.containsMatchIn(text) }?.let {
                return OverflowVerdict.Excluded(matchedPattern = it.pattern)
            }
            OVERFLOW_PATTERNS.firstOrNull { it.containsMatchIn(text) }?.let {
                return OverflowVerdict.Overflow(
                    source = Source.ERROR_PATTERN,
                    matchedPattern = it.pattern,
                )
            }
            if (provider.equals(CEREBRAS_PROVIDER, ignoreCase = true) &&
                CEREBRAS_BODYLESS_OVERFLOW_PATTERN.containsMatchIn(text)
            ) {
                return OverflowVerdict.Overflow(
                    source = Source.CEREBRAS_BODYLESS,
                    matchedPattern = CEREBRAS_BODYLESS_OVERFLOW_PATTERN.pattern,
                )
            }
        }
        if (statusCode == HTTP_REQUEST_ENTITY_TOO_LARGE) {
            return OverflowVerdict.Overflow(source = Source.HTTP_413, matchedPattern = null)
        }
        return OverflowVerdict.NoMatch
    }

    /**
     * Error-based overflow detection over the unified [com.openminis.app.data.model.LLMError]
     * taxonomy: [com.openminis.app.data.model.LLMError.ContextLengthExceeded] short-circuits to
     * Overflow (the classifier already proved the verdict); every other variant's provider
     * detail routes through [detectFromError]. Call ONLY for a failed turn.
     *
     * Status recovery: [com.openminis.app.provider.LLMProvider]'s shared HTTP error mapper
     * (mapHttpErrorShared) formats provider details in TWO deterministic shapes, and both
     * carry the proven status where [detectFromError]'s status-driven fallbacks can recover
     * it (a bodyless or unrecognized-body HTTP 413 must still be Overflow, otherwise the
     * 413 family is a dead path on every LLMError-carried error):
     *  - `"[CODE] body"` — the JSON-body path (default `messageLabel`), e.g. a 413 arrives
     *    as `"[413] ..."`; recovered by [BRACKETED_STATUS_PREFIX].
     *  - `"HTTP CODE: body"` — the non-JSON / empty-body path of the same mapper, e.g.
     *    `"HTTP 413: <masked body>"`; recovered by [HTTP_STATUS_PREFIX]. Without this
     *    second shape every non-JSON 413 stayed a dead path (status null, body text
     *    matched nothing).
     * Custom `messageLabel` forms (e.g. Anthropic's `"[request_too_large]"`) do NOT match
     * either numeric regex — the status stays null and the body text still routes through
     * the [OVERFLOW_PATTERNS] table (request_too_large is already an entry there), exactly
     * the pre-recovery behavior for label-shaped prefixes.
     *
     * The status is trusted over the body: the string PASSED to [detectFromError] is the
     * suffix after the proven prefix, so the prefix can never double as the line-anchored
     * "413..." table entry under a different real status. No matching prefix -> null
     * status, exactly the pre-recovery behavior.
     */
    fun detectFromLlmError(error: com.openminis.app.data.model.LLMError): OverflowVerdict {
        if (error is com.openminis.app.data.model.LLMError.ContextLengthExceeded) {
            return OverflowVerdict.Overflow(source = Source.ERROR_PATTERN, matchedPattern = null)
        }
        val body = when (error) {
            is com.openminis.app.data.model.LLMError.ProviderError -> error.detail
            is com.openminis.app.data.model.LLMError.TransientError -> error.detail
            is com.openminis.app.data.model.LLMError.RateLimited -> error.detail
            else -> error.message
        } ?: ""
        val statusMatch = BRACKETED_STATUS_PREFIX.find(body)
            ?: HTTP_STATUS_PREFIX.find(body)
        return if (statusMatch != null) {
            detectFromError(
                statusCode = statusMatch.groupValues[1].toInt(),
                body = body.substring(statusMatch.range.last + 1),
            )
        } else {
            detectFromError(statusCode = null, body = body)
        }
    }

    /**
     * Silent overflow (pi's isContextOverflow case 2, z.ai style): the provider
     * accepted an oversized request and answered "successfully", but the
     * reported prompt side already exceeds the window.
     *
     * @param inputTokens usage.input of the successful response
     * @param cacheReadTokens usage.cacheRead of the same response (0 when the
     *   provider does not report cache reads)
     * @param contextWindow the model's context window; 0 (unknown) disables detection
     */
    fun detectFromUsage(inputTokens: Long, cacheReadTokens: Long, contextWindow: Int): Boolean =
        contextWindow > 0 && inputTokens + cacheReadTokens > contextWindow

    /**
     * Truncation overflow (pi's isContextOverflow case 3, Xiaomi MiMo style):
     * the server silently clipped the oversized input to fill the context
     * window and returned a length stop with zero output — nothing was
     * generated because no room was left.
     *
     * @param finishReason the terminal stop reason as carried by this app
     *   ("length" on OpenAI-shaped APIs, "max_tokens" on Anthropic/Gemini —
     *   both are recognized; pi compares === "length" only because its ai
     *   layer normalizes the spelling first)
     * @param outputTokens usage.output of the response (0 = nothing generated)
     * @param inputTokens the full prompt side of the response: usage.input +
     *   usage.cacheRead (the caller combines them because that sum is what
     *   fills the window)
     * @param contextWindow the model's context window; 0 (unknown) disables detection
     */
    fun detectTruncationOverflow(
        finishReason: String?,
        outputTokens: Long,
        inputTokens: Long,
        contextWindow: Int,
    ): Boolean {
        if (contextWindow <= 0) return false
        if (!isLengthStop(finishReason)) return false
        if (outputTokens != 0L) return false
        // pi: inputTokens >= contextWindow * 0.99 (float compare). Integer-exact
        // equivalent for integer token counts: inputTokens >= ceil(99*window/100),
        // which agrees with the float form for every window below 2^31/99.
        val threshold = (contextWindow.toLong() * 99 + 99) / 100
        return inputTokens >= threshold
    }

    /**
     * Success-path overflow gate (pi's isContextOverflow cases 2+3 combined
     * for callers holding one SUCCESSFUL response — failed turns use
     * [detectFromError] / [detectFromLlmError]). Non-null when the response
     * answered "successfully" but its own report proves the window was
     * exceeded, with the family that fired:
     *
     *  - [SuccessfulOverflowKind.TRUNCATED] — the input silently clipped to
     *    fill the window with nothing generated ([detectTruncationOverflow],
     *    Xiaomi MiMo). The turn produced no answer; pi case ① recovers with
     *    ONE bounded compact-and-retry.
     *  - [SuccessfulOverflowKind.SILENT] — the prompt side already exceeds the
     *    window ([detectFromUsage], z.ai style). GATED on a natural-stop
     *    finish reason exactly like pi (`stopReason === "stop"`): a response
     *    that ended any other way is not trusted as a clean "answered
     *    successfully" report. The answer DID stream; pi case ② compacts and
     *    never retries ("overflow is never retried").
     *
     * @param inputTokens the FULL prompt side of the response: usage.input +
     *   usage.cacheRead (the caller combines them — that sum is what fills the
     *   window, same convention as [detectTruncationOverflow])
     */
    enum class SuccessfulOverflowKind { SILENT, TRUNCATED }

    fun successfulOverflowKind(
        finishReason: String?,
        outputTokens: Long,
        inputTokens: Long,
        contextWindow: Int,
    ): SuccessfulOverflowKind? = when {
        detectTruncationOverflow(finishReason, outputTokens, inputTokens, contextWindow) ->
            SuccessfulOverflowKind.TRUNCATED
        isStopFinish(finishReason) && detectFromUsage(inputTokens, 0L, contextWindow) ->
            SuccessfulOverflowKind.SILENT
        else -> null
    }

    /** Boolean convenience over [successfulOverflowKind] (pi's boolean shape). */
    fun isSuccessfulOverflow(
        finishReason: String?,
        outputTokens: Long,
        inputTokens: Long,
        contextWindow: Int,
    ): Boolean = successfulOverflowKind(finishReason, outputTokens, inputTokens, contextWindow) != null

    /** Natural-completion spellings across the supported provider families. */
    private fun isStopFinish(finishReason: String?): Boolean {
        if (finishReason.isNullOrEmpty()) return false
        val reason = finishReason.trim()
        return reason.equals("stop", ignoreCase = true) ||
            reason.equals("end_turn", ignoreCase = true) ||
            reason.equals("stop_sequence", ignoreCase = true)
    }

    /**
     * Recoverable length stop (pi's isRecoverableLength): the response ended on
     * a length stop BELOW the caller or model's intended output limit, so it
     * may be context pressure or provider-side truncation and one bounded
     * compact-and-retry attempt is worthwhile. [desiredMaxOutputTokens] must
     * be the ORIGINAL limit before any context-based clamping.
     */
    fun isRecoverableLength(finishReason: String?, outputTokens: Long, desiredMaxOutputTokens: Long): Boolean =
        isLengthStop(finishReason) && desiredMaxOutputTokens > 0 && outputTokens < desiredMaxOutputTokens

    /**
     * Whether [finishReason] is a truncation stop. pi normalizes every provider
     * to "length" before comparing; this app carries provider-native spellings
     * (OpenAI "length", Anthropic/Gemini "max_tokens"), so both are recognized.
     */
    private fun isLengthStop(finishReason: String?): Boolean {
        if (finishReason.isNullOrEmpty()) return false
        val reason = finishReason.trim()
        return reason.equals("length", ignoreCase = true) || reason.equals("max_tokens", ignoreCase = true)
    }
}

/**
 * Result of [ContextOverflowDetector.detectFromError]. Both non-overflow
 * outcomes carry the deciding pattern (when there is one) so callers can log
 * why a suspicious-looking body was NOT treated as overflow.
 */
sealed interface OverflowVerdict {
    /** True when the caller should treat the turn as a context overflow. */
    val isOverflow: Boolean

    /** Body matched nothing: neither the exclusion table nor the overflow table. */
    data object NoMatch : OverflowVerdict {
        override val isOverflow: Boolean get() = false
    }

    /**
     * A non-overflow pattern (rate limiting / throttling / server error)
     * matched first and vetoed overflow detection. Deliberately distinct from
     * [NoMatch]: a body that trips BOTH tables is diagnostic gold — the veto
     * means the provider used overflow-adjacent wording for a transient error.
     */
    data class Excluded(
        /** Source of the [ContextOverflowDetector.NON_OVERFLOW_PATTERNS] entry that matched. */
        val matchedPattern: String,
    ) : OverflowVerdict {
        override val isOverflow: Boolean get() = false
    }

    /** The turn is a context overflow. */
    data class Overflow(
        /** Which detection family produced the verdict. */
        val source: ContextOverflowDetector.Source,
        /**
         * Source of the matching regex, or null when the verdict came from the
         * bare HTTP 413 status with no recognizable body text.
         */
        val matchedPattern: String?,
    ) : OverflowVerdict {
        override val isOverflow: Boolean get() = true
    }
}
