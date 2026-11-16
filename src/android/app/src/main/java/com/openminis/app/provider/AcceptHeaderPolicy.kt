package com.openminis.app.provider

/**
 * Accept-header policy for chat-protocol requests (OpenAI-compatible
 * `chat/completions` + `responses`, Anthropic `messages`).
 *
 * ## Field report (2026-11-16, relay 406 — user cannot use the model)
 *
 * A user-configured relay rejects every request whose declared `Accept` is
 * `text/event-stream` with
 * `406 {"error":{"message":"Could not satisfy the request Accept header."}}` —
 * **including streaming ones**. Measured matrix on the affected relay, one
 * variable per row:
 *
 * | Accept header          | stream=false | stream=true |
 * |------------------------|--------------|-------------|
 * | absent                 | 200 JSON     | 200 SSE     |
 * | `application/json`     | 200 JSON     | 200 SSE     |
 * | wildcard (`*&#47;*`)   | 200 JSON     | 200 SSE     |
 * | `application/json, text/event-stream` | 200 | 200 |
 * | `text/event-stream`    | **406**      | **406**     |
 * | `text/html` / `application/xml` | **406** | —   |
 *
 * The gateway checks the DECLARED Accept, not the wanted format: a streaming
 * request with `Accept: application/json` answers with a standard
 * `text/event-stream` chunk stream, complete. This matches the official
 * OpenAI SDK, which sends `Accept: application/json` for BOTH stream modes —
 * `text/event-stream` as a chat Accept is a hand-written-client habit that
 * strict gateways punish.
 *
 * ## Policy
 *
 * [sanitizeChatAccept] rewrites the ONE harmful family — an Accept whose
 * primary type is `text/event-stream` (the bare value, and single-type
 * `;`-parameterized variants like `text/event-stream; q=1.0`) — to
 * `application/json`, at the request-builder choke points, so every entry
 * path (user extra headers, per-call overrides, preset headers) is covered
 * at once. Every OTHER value passes through verbatim: the wildcard value and
 * the combined `application/json, text/event-stream` (primary type is the
 * safe one) both verify 200 on the affected relay and are the user's
 * per-provider verbatim escape hatch (set either in the provider's
 * extra-headers config).
 *
 * Scope: chat-protocol request builders only. The MCP client keeps its own
 * spec-mandated `Accept: application/json, text/event-stream` (MCP
 * Streamable HTTP requires the SSE offer), and non-chat surfaces
 * (embeddings, voice, quota) already send `application/json`. The images
 * builder is NOT in sanitization scope (see the OpenAIProvider images
 * path): image-generation requests to the same strict gateway can still
 * 406 — a deliberate scope decision left with maintainers.
 */
internal object AcceptHeaderPolicy {

    const val ACCEPT_JSON = "application/json"

    /** The declared-Accept primary type strict gateways are measured to 406 on. */
    private const val HARMFUL = "text/event-stream"

    /**
     * Rewrite an Accept whose PRIMARY type is [HARMFUL] to [ACCEPT_JSON]; pass
     * everything else through unchanged (same map instance when nothing
     * matches — the common case costs one case-insensitive scan and zero
     * allocation).
     *
     * The check is on the primary type only: the text before the first comma,
     * with `;`-parameters stripped and trimmed. A single-type variant with
     * parameters (`text/event-stream; q=1.0`) therefore hits, while a combined
     * value whose primary type is not [HARMFUL] (`application/json,
     * text/event-stream`) passes through verbatim — both measured 200 on the
     * affected relay.
     */
    fun sanitizeChatAccept(headers: Map<String, String>): Map<String, String> {
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?: return headers
        if (!primaryType(accept.value).equals(HARMFUL, ignoreCase = true)) return headers
        val out = LinkedHashMap<String, String>(headers.size)
        for ((key, value) in headers) {
            out[key] = if (key.equals("Accept", ignoreCase = true)) ACCEPT_JSON else value
        }
        return out
    }

    /** First comma-separated media type of an Accept value, `;`-parameters stripped, trimmed. */
    private fun primaryType(value: String): String =
        value.substringBefore(',').substringBefore(';').trim()
}
