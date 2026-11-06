// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (provider/StopReasons.kt), refusal half only — the max-tokens notice it also
// carries belongs to a different wave.
package com.openminis.app.provider

/**
 * [T-android-model-refusal] Terminal stop reasons that mean "the model DECLINED
 * this request". Port of iOS `AgentStopReason.refusal` (54c8ea2a4 + the OpenAI
 * mappings in OpenAIAgentProvider).
 *
 * Android carries a turn's stop reason as the provider's own string
 * ([com.openminis.app.data.model.LLMStreamChunk.Finished.stopReason]), so this
 * is the one place that knows which spellings are a refusal:
 *   - Anthropic `stop_reason: "refusal"` — the safety classifier declined the
 *     request: HTTP 200, input tokens billed, EMPTY content. Seen as a false
 *     positive on ordinary agent turns (large system prompt + tool set) on
 *     Claude Fable 5 / Sonnet 5.5.
 *   - OpenAI `finish_reason: "content_filter"` (Chat Completions), and the
 *     Responses API's `response.incomplete` with
 *     `incomplete_details.reason = "content_filter"` — the safety system
 *     blocked the response.
 *
 * A refusal is deterministic: resending the identical request is declined
 * again. It must not go through the empty-turn nudge / retry path, and it
 * must not end the run silently — the agent loop surfaces it on the reply.
 */
object StopReasons {
    const val REFUSAL = "refusal"
    const val CONTENT_FILTER = "content_filter"

    fun isRefusal(stopReason: String?): Boolean =
        stopReason == REFUSAL || stopReason == CONTENT_FILTER

    /**
     * [FIX-261018-12] The error shown for a refusal. Port of iOS
     * `AIChatViewModel.refusalErrorText`: with the provider's category
     * (already formatted) it names the policy and says resending will not
     * help; otherwise the generic text. The provider's own explanation
     * (English, from the API) follows after a blank line.
     */
    fun refusalErrorText(categoryMessage: String?, genericMessage: String, explanation: String?): String {
        val base = categoryMessage?.takeIf { it.isNotBlank() } ?: genericMessage
        val detail = explanation?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        return base + "\n\n" + detail
    }
}
