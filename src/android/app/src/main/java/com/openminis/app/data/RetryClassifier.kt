package com.openminis.app.data

import kotlin.random.Random

/**
 * Session-layer retry classification for failed assistant turns. Port of pi
 * `ai/src/utils/retry.ts` (packages/ai, MIT): the transient-error whitelist
 * and the quota/billing blacklist are translated 1:1 (pi's
 * RETRYABLE_PROVIDER_ERROR_PATTERN and NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN).
 *
 * ## The two-layer split (pi-port analysis §1.5)
 *
 * The HTTP protocol layer ("should THIS request be resent: x-should-retry
 * header, 408/409/429/5xx, Retry-After honoring") lives in
 * [com.openminis.app.provider.HttpRetryAfter]. THIS class answers the session
 * question: "did the whole assistant turn fail for a reason that rerunning it
 * can fix?". Callers handle context overflow first (it needs compaction, not
 * retries), then apply their own retry budget and backoff before restarting.
 *
 * ## Precedence
 *
 * The blacklist outranks the whitelist: a message that trips both is quota
 * exhaustion wearing transient clothing ("429 quota exceeded", Zhipu/Z.ai
 * error 1308 arriving as HTTP 429, "rate limit: quota exceeded"). Retrying
 * those burns the ladder against a limit that resets in hours, not seconds,
 * so [RetryClass.QUOTA_EXHAUSTED] wins even when the status code looks
 * retryable.
 *
 * Pure JVM logic — no Android dependency, safe for unit tests.
 */
object RetryClassifier {

    /** Verdict for one failed assistant turn. */
    enum class RetryClass {
        /** Transient provider/transport failure — rerunning the turn can fix it. */
        RETRYABLE_TRANSIENT,

        /** Quota/billing/subscription exhaustion — retrying is wasted budget. */
        QUOTA_EXHAUSTED,

        /** Deterministic failure (bad key, bad request, policy) — fail fast. */
        NOT_RETRYABLE,
    }

    /** Ceiling on the per-attempt jitter added by [backoffDelayMs] (HttpRetryAfter.jitterMs style). */
    const val JITTER_MAX_MS: Long = 800L

    /**
     * Retryable provider/transport error patterns (regex alternatives joined
     * into one case-insensitive matcher, exactly like pi's
     * buildProviderErrorPattern). Vendor annotations carried over from pi.
     */
    val TRANSIENT_PATTERNS: List<String> = listOf(
        // Generic provider load, HTTP status, and server-side transient failures.
        "overloaded",
        "server_busy",
        "servers are currently busy",
        "currently experiencing high demand",
        "model is at capacity",
        "rate.?limit",
        "too many requests",
        // Status codes must not be part of a longer word or number, e.g. a request id or byte count (#11057).
        "(?<![a-z0-9])(?:429|500|502|503|504|520|524)(?![a-z0-9])",
        // Also matches "The service is temporarily unavailable" (#10965).
        "service.?(?:is.?)?(?:temporarily.?)?unavailable",
        "server.?error",
        "internal.?error",

        // Wrapper/provider text for transient upstream failures, including OpenRouter
        // "Provider returned error" responses (#2264) and gateway wording such as
        // "The upstream provider returned an error" (#10842).
        "provider.?returned.?(?:an.?)?error",
        "exceeded request buffer limit while retrying upstream",
        // Gateway failure relaying the upstream response stream, sent as an OpenAI
        // Responses `response.failed` event without an error code (#10920).
        "unable to forward upstream",
        // Other gateway upstream failures relayed as `response.failed` error codes (#10920).
        "upstream.?request.?failed",
        "upstream_stream_read_error",
        "stream was interrupted",
        "http/?2 stream failed",

        // Network, proxy, and fetch transport failures. This includes OpenAI Codex
        // raw-fetch failures such as "upstream connect", "connection refused", and
        // "reset before headers" (#733), plus OpenRouter connection drops (#3317).
        "network.?error",
        "connection.?error",
        "connection.?refused",
        "connection.?lost",
        // Gateway mid-stream drop relayed as an Anthropic-style `api_error` event,
        // e.g. {"type":"error","error":{"type":"api_error","message":"Connection closed."}} (#12064).
        "connection.?closed",
        "other side closed",
        "fetch failed",
        // node-fetch system errors, e.g. the Google ADC OAuth token refresh used by
        // google-vertex. The reason is often empty (#10996).
        "request to \\S+ failed, reason:",
        "getaddrinfo",
        // Bare Node socket error codes, e.g. "read ECONNRESET", "write EPIPE", or
        // "connect ETIMEDOUT 1.2.3.4:443" when a transport reports only the code (#12128).
        // The codes must not be part of a longer identifier.
        "(?<![a-z0-9_])(?:ECONNRESET|ECONNABORTED|ECONNREFUSED|ETIMEDOUT|EPIPE)(?![a-z0-9_])",
        "connection reset by peer",
        "ENOTFOUND",
        "EAI_AGAIN",
        "upstream.?connect",
        "reset before headers",
        "socket hang up",
        "socket connection was closed",
        "timed? out",
        "timeout",
        "terminated",

        // WebSocket transports can report close/error text instead of HTTP/fetch text.
        "websocket.?closed",
        "websocket.?error",

        // Premature stream endings from SDKs and transports. Anthropic can throw
        // "stream ended without ..." and "Anthropic stream ended before message_stop"
        // (#4433); Bedrock/Smithy can throw an HTTP/2 no-response error (#3594).
        "ended without",
        "stream ended before message_stop",
        "stream ended before a terminal response event",
        "http2 request did not get a response",
        // Codex CLI mid-stream disconnect, relayed by proxies that wrap the Codex CLI,
        // e.g. "stream disconnected before completion: stream closed before response.completed" (#11057).
        "stream disconnected before completion",
        // Magpie gateway relaying a dropped upstream Anthropic stream, e.g.
        // "Claude Code's stream from Anthropic was cut short after the reply had begun" (#11879).
        "cut short after the reply had begun",
        // Node ERR_HTTP2_STREAM_CANCEL: the HTTP/2 session died before the request was
        // sent, e.g. after the Bedrock SDK's 5-minute session timeout (#10379).
        "pending stream has been canceled",

        // Provider-requested retry delay cap failures should flow through the outer
        // retry policy so callers can surface/abort the backoff (#1123).
        "retry delay",

        // Explicit retry guidance emitted mid-stream by OpenAI Responses and Bedrock
        // stream exceptions (#6019).
        "you can retry your request",
        "try your request again",
        "please retry your request",

        // gRPC based providers (e.g. NVIDIA NIM)
        "ResourceExhausted",

        // Sign in with ChatGPT: usage or user data temporarily unavailable. Usage
        // failures can arrive mid-stream without an HTTP 503 in the message.
        "subscription_sharing_usage_unavailable",
        "subscription_sharing_user_unavailable",
    )

    /**
     * Quota / billing / subscription-exhaustion patterns. These arrive dressed
     * as transient errors (HTTP 429, "limit" wording) but reset on the scale
     * of hours, not seconds, so they must never enter the retry ladder.
     * Checked BEFORE [TRANSIENT_PATTERNS] and before the status-code path.
     */
    val QUOTA_BLACKLIST_PATTERNS: List<String> = listOf(
        // OpenCode Go/free-tier limits returned as 429 JSON error types by OpenCode's
        // Zen API. These are subscription/account limits, not transient throttles.
        "GoUsageLimitError",
        "FreeUsageLimitError",

        // OpenCode Go subscription-limit text asks users to enable available-balance
        // usage after rolling/weekly/monthly limits are reached.
        "Monthly usage limit reached",
        "available balance",

        // Generic quota/budget/billing exhaustion. `insufficient_quota` is OpenAI's
        // quota/billing error code; the other strings cover common gateway wording.
        "insufficient_quota",
        "out of budget",
        "quota exceeded",
        "billing",

        // Sign in with ChatGPT: the subscription's shared usage limit, which resets
        // after hours rather than seconds.
        "subscription_sharing_usage_limit_exceeded",
        // Sign in with ChatGPT on a ChatGPT Business plan: a usage limit set by the
        // workspace admin. Only the admin can raise it.
        "subscription_sharing_workplace_individual_usage_limit_exceeded",

        // Zhipu/Z.ai (open.bigmodel.cn) error code 1308: the plan's usage limit is
        // reached, sent as HTTP 429. It resets after hours, not seconds (#11537).
        "\"code\"\\s*:\\s*\"?1308(?![0-9])",
    )

    private val transientRegex =
        Regex(TRANSIENT_PATTERNS.joinToString("|"), RegexOption.IGNORE_CASE)

    private val quotaBlacklistRegex =
        Regex(QUOTA_BLACKLIST_PATTERNS.joinToString("|"), RegexOption.IGNORE_CASE)

    /**
     * Classify one failed assistant turn.
     *
     * Order of authority:
     *  1. [QUOTA_BLACKLIST_PATTERNS] on [message] — beats everything (see the
     *     precedence note on the object).
     *  2. [TRANSIENT_PATTERNS] on [message].
     *  3. [statusCode] — 408/409/429 and anything >= 500 are transient
     *     (mirrors pi provider-retry.ts, i.e. the pinned OpenAI/Anthropic SDK
     *     policy). Message evidence always outranks status evidence.
     *  4. Otherwise [RetryClass.NOT_RETRYABLE].
     *
     * @param message the provider error text (null/blank when only the status is known)
     * @param statusCode the HTTP status of the failed call, null when the
     *   failure never produced one (transport-level exception)
     */
    fun classify(message: String?, statusCode: Int?): RetryClass {
        val text = message?.trim()
        if (!text.isNullOrEmpty()) {
            if (quotaBlacklistRegex.containsMatchIn(text)) return RetryClass.QUOTA_EXHAUSTED
            if (transientRegex.containsMatchIn(text)) return RetryClass.RETRYABLE_TRANSIENT
        }
        if (statusCode != null && isRetryableStatus(statusCode)) return RetryClass.RETRYABLE_TRANSIENT
        return RetryClass.NOT_RETRYABLE
    }

    /**
     * Deterministic part of [backoffDelayMs]: `baseDelayMs * 2^(attempt-1)`
     * capped at [maxDelayMs] (pi's retryDelayMs, regression #8826). attempt is
     * 1-indexed; values below 1 clamp to the first retry. A non-positive base
     * or cap yields 0 (pi: baseDelayMs 0 disables the wait entirely).
     */
    fun exponentialDelayMs(attempt: Int, baseDelayMs: Long, maxDelayMs: Long): Long {
        if (baseDelayMs <= 0 || maxDelayMs <= 0) return 0L
        val exp = (attempt - 1).coerceIn(0, 62)
        val raw = if (baseDelayMs > (Long.MAX_VALUE ushr exp)) Long.MAX_VALUE else baseDelayMs shl exp
        return raw.coerceAtMost(maxDelayMs)
    }

    /**
     * Full per-attempt backoff: [exponentialDelayMs] plus bounded jitter in the
     * style of [com.openminis.app.provider.HttpRetryAfter.jitterMs] (additive,
     * [JITTER_MAX_MS] ceiling), with [maxDelayMs] as a HARD ceiling — the
     * jitter budget shrinks near the cap so the ladder tops out exactly at it.
     * A zero base delay stays exactly zero (callers use base 0 to disable the
     * wait in tests, like pi's `baseDelayMs: 0` policy).
     */
    fun backoffDelayMs(attempt: Int, baseDelayMs: Long = 1_000, maxDelayMs: Long = 32_000): Long {
        val base = exponentialDelayMs(attempt, baseDelayMs, maxDelayMs)
        if (base <= 0) return 0L
        val jitter = jitterMs(minOf(JITTER_MAX_MS, maxDelayMs - base))
        return (base + jitter).coerceAtMost(maxDelayMs)
    }

    /** Additive jitter in the HttpRetryAfter style: uniform in [0, maxExclusive). */
    fun jitterMs(maxExclusive: Long = JITTER_MAX_MS): Long =
        if (maxExclusive <= 1L) 0L else Random.nextLong(0, maxExclusive)

    /**
     * Status codes worth a resend even without recognizable body text — the
     * protocol-layer set pi provider-retry.ts borrows from the OpenAI and
     * Anthropic SDKs (408 timeout, 409 conflict, 429 throttling, 5xx server).
     */
    private fun isRetryableStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500
}
