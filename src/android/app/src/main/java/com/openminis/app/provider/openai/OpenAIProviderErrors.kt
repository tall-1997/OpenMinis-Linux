package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.ZenErrorBody
import com.openminis.app.provider.mapHttpErrorShared

internal fun OpenAIProvider.mapHttpError(statusCode: Int, body: String, retryAfterHeader: String? = null): LLMError {
    // [T-zen-structured-error] OpenCode Zen answers with a structured body
    // ({"type":"error","error":{"type":"FreeTierError",…}}) whose type carries
    // semantics the generic HTTP mapping cannot see. FreeTierError means the
    // request was refused as a non-official client; ModelError means the
    // catalogue lists the id but the server refuses to serve it; RegionError
    // means the model is geo-fenced. All three are provider refusals where
    // "Invalid API key" is simply false, and each has a hint worth showing.
    //
    // [T-zen-sniffer-host-gate] GATED ON THE ZEN HOST, deliberately. This
    // function is the single error mapper for EVERY OpenAI-compatible provider,
    // and `ModelError` / `RegionError` are generic type names — a relay or a
    // self-hosted vLLM that happens to answer `{"type":"error","error":
    // {"type":"ModelError"}}` would otherwise be captured by Zen-specific
    // wording ("geo-fenced upstream", "Refresh the model list") that describes
    // a service it knows nothing about. Only the Zen endpoint speaks this
    // dialect, so only the Zen endpoint gets this mapping; every other host
    // keeps the generic path it had before.
    if (com.openminis.app.provider.ZenEndpoint.isZenHost(basePath)) {
        zenStructuredProviderRefusal(body)?.let { return it }
    }
    return mapHttpErrorShared(
        statusCode,
        body,
        retryAfterHeader,
        credentialGateKey = credentialGateKey,
        isContextLimit = ::openAIContextLimit,
        isContentFiltered = ::openAIContentFiltered,
        map413ToContextLength = true,
    )
}

// [T-llm-error-classification] 细分 OpenAI 400 类错误，不再笼统地归 ProviderError。
private fun openAIContextLimit(statusCode: Int, lower: String): Boolean =
    !lower.contains("max_tokens too large") && !lower.contains("max_output_tokens") && (
        lower.contains("context_length_exceeded") || lower.contains("maximum context length") ||
            (statusCode == 400 && (
                lower.contains("too many tokens") ||
                    lower.contains("reduce the length") ||
                    // [T-llm-error-413-note] Excludes output-parameter errors
                    // (max_output_tokens mismatches) — a request-param problem is
                    // not the context-window problem this class's hint assumes.
                    (lower.contains("token") && (lower.contains("max") || lower.contains("limit") || lower.contains("exceed")))
            ))
    )

private fun openAIContentFiltered(statusCode: Int, lower: String): Boolean =
    lower.contains("content_filter") || lower.contains("content_policy") ||
        lower.contains("safety") || statusCode == 422 && lower.contains("content")

internal fun OpenAIProvider.mapError(error: Throwable): LLMError = mapThrowableToLLMError(error)

/**
 * [T-zen-structured-error] Parses an OpenCode Zen structured refusal body —
 * `{"type":"error","error":{"type":"FreeTierError"|"ModelError"|"RegionError",
 * "message":…}}` — into a precise [LLMError]; null when the body is not one, so
 * generic OpenAI bodies flow to the historical mapping untouched. The upstream
 * message is user-visible verbatim, so it goes through [maskSecrets] like every
 * other server-provided string in this file.
 *
 * [P2-convergence] The envelope scan is the shared [ZenErrorBody.parse] (same
 * reader the free-lane classifier uses). The `envelopeType == "error"` gate is
 * this mapper's own strictness and stays here: only the bare spelling carries
 * an envelope type, so an SSE-framed error keeps flowing to the generic
 * mapping exactly as before.
 */
internal fun zenStructuredProviderRefusal(body: String): LLMError? {
    val zen = ZenErrorBody.parse(body) ?: return null
    if (zen.envelopeType != "error") return null
    val message = zen.errorMessage
    return when (zen.errorType) {
        // [T-zen-free-lane-retired] Measured 2026-10-17: this refusal is not a
        // header problem and not a key problem — the official 2.0.32 CLI answers
        // 200 on the same models from the same machine, while the CLI's own
        // header set, replayed from curl / Node / Python / Chromium, answers 403
        // on every model over both HTTP/1.1 and HTTP/2. The keyless lane this
        // app used to ship is therefore retired, and the honest instruction is
        // "bring your own Zen key".
        "FreeTierError" -> LLMError.ProviderError(
            "FreeTierError: ${maskSecrets(message.ifBlank { "free tier refused" })} — " +
                "upstream only accepts requests from the official OpenCode client, so the " +
                "keyless free lane is no longer usable here. Add your own OpenCode Zen API " +
                "key in this provider's settings, or switch provider.",
        )
        "RegionError" -> LLMError.ProviderError(
            "RegionError: ${maskSecrets(message.ifBlank { "not available in your country" })} — " +
                "this model is geo-fenced upstream and cannot be reached from your " +
                "current location. Try another model or another provider.",
        )
        "ModelError" -> LLMError.ProviderError(
            "ModelError: ${maskSecrets(message.ifBlank { "model refused by server" })} — " +
                "the server's catalogue lists this id but refuses to serve it. " +
                "Refresh the model list, or pick another model.",
        )
        else -> null
    }
}

/** Receiver-free so the unit tests can exercise the mapping directly. */
internal fun mapThrowableToLLMError(error: Throwable): LLMError {
    if (error is LLMError) return error
    // [T-llm-error-tls] TLS / certificate failures are permanent
    // misconfigurations (clock skew, MITM proxy, untrusted CA). They are
    // IOExceptions, so the old mapping filed them under retryable
    // NetworkError and burned the full retry budget on a failure no retry
    // can fix — and then the actionable hint told the user to "check your
    // internet connection".
    if (error is javax.net.ssl.SSLException) {
        return LLMError.ProviderError("TLS error: ${error.message ?: error.javaClass.simpleName}")
    }
    if (error is java.io.IOException) return LLMError.NetworkError(error)
    return LLMError.Unknown(error)
}

/**
 * [T-llm-error-secret-mask] Upstream error bodies occasionally echo parts of
 * the request — and with them key-shaped material. Nothing in
 * [LLMError.message] should ever be able to carry a credential into the chat
 * UI (message is truncated to 160 chars for display, but truncation is not
 * redaction).
 *
 * [T-secret-mask-single-source] The patterns moved to
 * [com.openminis.app.util.SecretMasking] so the context-assembly snapshot can
 * use the same rule. This stays as the local name the call sites and
 * LLMErrorClassificationTest already use.
 */
internal fun maskSecrets(text: String): String =
    com.openminis.app.util.SecretMasking.mask(text)
