package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMStreamChunk
import org.json.JSONObject

/**
 * [T-responses-reasoning-item] Reasoning text carried on a FINISHED Responses
 * item, rather than streamed as `response.reasoning_*_text.delta`.
 *
 * Extracted from [OpenAIRawStream] to keep that file inside its architecture
 * ceiling. Pure (no I/O, no state), so it is JVM-testable.
 *
 * WHY: a relay may compute reasoning and put the text on the item instead of
 * streaming deltas. The delta path then stays silent and the Thinking region
 * renders nothing even though the tokens were billed — the field report showed
 * `reasoning_tokens:4201` beside an empty Thinking block, on a relay that did
 * send the text, just not as deltas.
 *
 * Callers MUST only use this when no delta arrived: reading the item after a
 * verbatim stream would duplicate the text.
 */
internal fun reasoningTextFromItem(item: JSONObject): String? {
    val carried = StringBuilder()
    for (field in listOf("summary", "content")) {
        val arr = item.optJSONArray(field) ?: continue
        for (n in 0 until arr.length()) {
            val piece = arr.optJSONObject(n)?.optString("text", "")
            if (!piece.isNullOrEmpty()) carried.append(piece)
        }
    }
    return carried.toString().takeIf { it.isNotEmpty() }
}

/** [T-responses-reasoning-item] The item's body as one ThinkingDelta, or null. */
internal fun reasoningThinkingDelta(item: JSONObject): LLMStreamChunk.ThinkingDelta? =
    reasoningTextFromItem(item)?.let { LLMStreamChunk.ThinkingDelta(it) }
