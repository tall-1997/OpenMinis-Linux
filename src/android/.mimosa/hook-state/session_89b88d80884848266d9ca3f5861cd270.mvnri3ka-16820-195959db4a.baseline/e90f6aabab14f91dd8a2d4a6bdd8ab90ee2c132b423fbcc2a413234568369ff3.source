package com.openminis.app.data.model

/**
 * [T-llm-error-classification] Unified 11-kind error taxonomy modelled after
 * shiyi-agent's `llm_client.dart` error classification. Every variant carries
 * an [actionableHint] that tells the user (or the agent on retry) what concrete
 * next step restores progress — instead of dumping a raw status code.
 */
sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** API key is missing, revoked, or expired. */
    class InvalidApiKey(
        val detail: String = "",
        /**
         * [T-llm-error-401-model-scope] True when the SAME credential was
         * accepted by this host recently (see
         * [com.openminis.app.provider.CredentialAcceptance]). The refusal is
         * then about *this model* — typically a gateway whose upstream channel
         * for it is broken — and not about the key, so both the message and the
         * hint must say so instead of sending the user to regenerate a
         * credential that was never the problem.
         */
        val credentialAccepted: Boolean = false,
    ) : LLMError(
        when {
            credentialAccepted && detail.isNotBlank() -> detail
            credentialAccepted ->
                "Model refused by the gateway (the credential itself was accepted)"
            detail.isBlank() -> "Invalid API key"
            else -> "Invalid API key: $detail"
        },
    )

    /** TCP/DNS layer failed before the HTTP request was sent. */
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)

    /**
     * The request was sent but the server didn't respond within the deadline.
     * Distinct from [NetworkError] (which never left the client) and
     * [TransientError] (which is a server-returned 5xx / connection drop).
     * [phase] distinguishes connect vs read vs ttfb so the retry strategy can
     * decide whether same-provider retry is worthwhile.
     */
    class Timeout(
        val detail: String,
        val phase: TimeoutPhase = TimeoutPhase.READ,
    ) : LLMError("Request timed out: $detail") {
        enum class TimeoutPhase { CONNECT, READ, TTFB }
    }

    /**
     * The provider returned a 4xx / 5xx that doesn't match a more specific
     * category. Prefer [RateLimited], [ContextLengthExceeded],
     * [ContentFiltered], or [InvalidApiKey] when the server gives enough signal.
     */
    class ProviderError(val detail: String) : LLMError("Provider error: $detail")

    /** JSON parsing / SSE framing / schema mismatch on the response body. */
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)

    class RateLimited(
        val retryAfterSeconds: Int? = null,
        val detail: String = "",
    ) : LLMError(rateLimitedMessage(retryAfterSeconds, detail))

    /**
     * The prompt (or current conversation) exceeds the model's context window.
     * Actionable: the agent or user can compact, offload, or truncate.
     */
    class ContextLengthExceeded(
        val detail: String,
        val requestedTokens: Int? = null,
        val maxTokens: Int? = null,
    ) : LLMError(contextLengthMessage(detail, requestedTokens, maxTokens))

    /**
     * The server rejected the request because of a content policy violation.
     * Not retryable on the same model — rephrase or switch providers.
     */
    class ContentFiltered(val detail: String) : LLMError(
        "Content filtered: $detail",
    )

    /**
     * Server-side hiccup (5xx, connection dropped mid-stream, empty response).
     * [stalledAfterFirstEvent] is true when the stream had already produced at
     * least one chunk and then went quiet past the idle budget — i.e. a
     * mid-stream stall rather than a never-started timeout. The retry layer
     * uses it to decide whether to resume from the partial text ([T-stall-resume])
     * or regenerate from scratch.
     */
    class TransientError(
        val detail: String,
        val stalledAfterFirstEvent: Boolean = false,
    ) : LLMError("Transient error: $detail")

    /** Coroutine-level cancellation (user stopped, timeout, etc.). */
    class Cancelled : LLMError("Request was cancelled")

    /** Catch-all for unrecognised failures. */
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    // ── classification helpers ──────────────────────────────────────────

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError || this is Timeout

    /** Worth retrying on the same provider (bounded exponential backoff). */
    val isRetryable: Boolean
        get() = this is NetworkError || this is Timeout ||
            this is TransientError || this is RateLimited

    /** Should immediately fall back to the next model in the group. */
    val isFallbackable: Boolean
        get() = when (this) {
            is RateLimited, is InvalidApiKey, is ContextLengthExceeded,
            is ContentFiltered -> true
            is Timeout -> true
            is ProviderError ->
                detail.contains("[429]") ||
                    Regex("""\[5\d{2}\]""").containsMatchIn(detail)
            else -> false
        }

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey ->
                if (credentialAccepted) "Model refused by gateway" else "Invalid API key"
            is Timeout -> "Timed out"
            is ContextLengthExceeded -> "Context window exceeded"
            is ContentFiltered -> "Content filtered"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }

    /**
     * One-sentence next step the user (or a self-correcting agent) can take.
     * Mirrors shiyi-agent's per-error guidance so the chat UI shows concrete
     * advice instead of a bare HTTP code.
     */
    val actionableHint: String
        get() = when (this) {
            is InvalidApiKey -> when {
                // Checked before the 403 branch: a credential we have seen
                // accepted is the stronger evidence, and its remedy (pick
                // another model) is different from either key branch.
                credentialAccepted ->
                    "Your API key is fine — this server accepted it moments ago. It refused THIS MODEL, " +
                        "which usually means the gateway's upstream channel for it is broken or the model " +
                        "is not on your plan. Pick a different model; only check the key if every model fails."
                detail.contains("403", ignoreCase = true) || detail.contains("forbidden", ignoreCase = true) ->
                    "The credential was accepted but access was denied (403): this model or region may not be allowed on your plan. Check the provider's model access settings."
                else -> "Check your API key in Settings → Providers, or regenerate it at your provider's dashboard."
            }
            is NetworkError -> "Check your internet connection and try again."
            is Timeout -> when (phase) {
                Timeout.TimeoutPhase.CONNECT -> "The server didn't respond — check your network or try a different provider."
                Timeout.TimeoutPhase.READ -> "The model is taking too long to finish — try a faster model or shorten your message."
                Timeout.TimeoutPhase.TTFB -> "The model hasn't started responding — the provider may be overloaded. Try again in a moment."
            }
            is RateLimited -> if (retryAfterSeconds != null) "Rate limited — retry after ${retryAfterSeconds}s." else "Rate limited — wait a moment and try again."
            is ContextLengthExceeded -> buildString {
                append("Your conversation is too long for this model")
                if (maxTokens != null) append(" ($maxTokens token limit)")
                append(". Use /compact to summarise older messages, or start a new chat.")
            }
            is ContentFiltered -> "The model's content policy blocked this request. Try rephrasing your message."
            is ProviderError -> "The provider returned an error. Tap to see details, or try again later."
            is TransientError -> "A temporary server error occurred. Retrying…"
            is DecodingError -> "The model's response couldn't be parsed. Retrying…"
            is Cancelled -> "The request was stopped."
            is Unknown -> "An unexpected error occurred. Tap for details."
        }
}

private fun contextLengthMessage(detail: String, requested: Int?, max: Int?): String {
    val sb = StringBuilder("Context length exceeded")
    if (requested != null) sb.append(" — requested $requested tokens")
    if (max != null) sb.append(" of $max max")
    val snip = detail.trim().replace('\n', ' ').replace(Regex("\\s+"), " ").take(160)
    if (snip.isNotBlank() && !snip.equals("context length exceeded", ignoreCase = true)) {
        sb.append(" — $snip")
    }
    return sb.toString()
}

private fun rateLimitedMessage(retryAfterSeconds: Int?, detail: String): String {
    val base = if (retryAfterSeconds != null) "Rate limited — retry after ${retryAfterSeconds}s"
    else "Rate limited — please try again later"
    val snip = detail.trim().replace('\n', ' ').replace('\r', ' ')
        .replace(Regex("\\s+"), " ").take(160)
    if (snip.isBlank()) return base
    if (snip.equals("rate limited", ignoreCase = true)) return base
    if (snip.equals("too many requests", ignoreCase = true)) return base
    return "$base — $snip"
}