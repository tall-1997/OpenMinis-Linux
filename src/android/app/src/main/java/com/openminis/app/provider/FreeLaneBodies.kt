package com.openminis.app.provider

import org.json.JSONObject

/**
 * One parsed Zen/OpenCode structured refusal:
 * `{"type":"error","error":{"type":"FreeTierError"|…,"message":…}}`.
 *
 * [P2-convergence] Single parse for the two former readers — the free-lane
 * classifier (via [FreeLaneBodies.errorType]) and the OpenAI chat error mapper
 * (`zenStructuredProviderRefusal` in provider/openai) — so the envelope shapes
 * they accept cannot drift apart. [envelopeType] is the OUTER `type` field
 * ("" when the body did not arrive as a bare JSON object, e.g. an SSE frame);
 * [errorType]/[errorMessage] come from the nested `error` object.
 */
internal data class ZenErrorBody(
    val envelopeType: String,
    val errorType: String,
    val errorMessage: String,
) {
    companion object {
        /**
         * Null when [body] carries no structured `error` object in either
         * spelling (bare JSON or SSE `data:` frame). Reads exactly what
         * [FreeLaneBodies.errorObject] accepts, so the lane verdicts and the
         * chat mapper see the same set of bodies.
         */
        fun parse(body: String): ZenErrorBody? {
            val error = FreeLaneBodies.errorObject(body) ?: return null
            return ZenErrorBody(
                envelopeType = bareEnvelopeType(body),
                errorType = error.optString("type"),
                errorMessage = error.safeOptString("message"),
            )
        }

        /** The outer `type` of the bare spelling; "" for SSE frames/unparseable bodies. */
        private fun bareEnvelopeType(body: String): String = runCatching {
            JSONObject(body).optString("type")
        }.getOrDefault("")
    }
}

/**
 * Reads a structured error out of a response body, in either spelling.
 *
 * Shared by every lane because the two spellings are a property of HTTP+SSE and
 * not of an upstream: the bare JSON object, and the same object inside an SSE
 * `data:` frame. A lane that only reads one of them learns a 200-shaped refusal
 * as [FreeLaneVerdict.FREE] — worse than learning nothing, because it puts an id
 * in front of the user that then fails at runtime.
 *
 * Deliberately strict about what counts as an error object: `error` must be a
 * nested JSON object. A string-shaped `{"error":"Model is unavailable."}` has no
 * structure to read, so it returns null and the lane's transport map may speak —
 * which is exactly what keeps Zen's switched-off models distinguishable from
 * Zen's credential refusals. Generated content that merely contains the word
 * "error" is not a frame, and the substring pre-check keeps a long successful
 * stream from being parsed at all.
 */
internal object FreeLaneBodies {

    /** The first structured `error` object, or null when the body carries none. */
    fun errorObject(body: String): JSONObject? {
        if (body.isEmpty() || !body.contains("error")) return null
        bareError(body)?.let { return it }
        if (!body.contains("data:")) return null
        return body.lineSequence()
            .map { it.substringAfter("data:", "").trim() }
            .filter { it.length > 1 && it != "[DONE]" && it.contains("error") }
            .firstNotNullOfOrNull { bareError(it) }
    }

    /** The nested `error.type` — Zen's dialect word. */
    fun errorType(body: String): String? =
        ZenErrorBody.parse(body)?.errorType?.takeIf { it.isNotEmpty() }

    /** The nested `error.code` — Kilo's dialect word. */
    fun errorCode(body: String): String? =
        errorObject(body)?.optString("code")?.takeIf { it.isNotEmpty() }

    /**
     * The nested `error.metadata.raw`, which is where Kilo puts the sentence
     * that actually names the problem ("Provider function not found for
     * account"). A generic `code: "Not Found"` alone cannot distinguish a
     * retired model from a malformed request.
     */
    fun errorDetail(body: String): String =
        errorObject(body)?.optJSONObject("metadata")?.optString("raw").orEmpty() +
            " " + errorObject(body)?.optString("message").orEmpty()

    private fun bareError(json: String): JSONObject? = runCatching {
        JSONObject(json).optJSONObject("error")
    }.getOrNull()
}
