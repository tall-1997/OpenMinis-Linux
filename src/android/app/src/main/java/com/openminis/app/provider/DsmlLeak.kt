// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (provider/DsmlLeak.kt). One deliberate deviation: MAX_RETRIES is 1, not 2 —
// this wave's guardrail is a single free resend per turn ("最多 1 次"), so a
// leaking endpoint can at most double one request, never loop.
package com.openminis.app.provider

/**
 * [T-android-dsml-leak] Detects a DeepSeek tool call that leaked into the
 * reasoning channel.
 *
 * DeepSeek serializes tool calls in its own markup (DSML in V4: `<｜DSML｜invoke>`
 * / `<｜DSML｜parameter>`; `<｜tool▁calls▁begin｜>` in V3/R1). The server parses
 * that markup into `tool_calls` only after the model closes its thinking. When
 * the model writes the call while still thinking, everything stays in
 * `reasoning_content`: the opening tags are swallowed, the closing tags and the
 * argument body show up as "reasoning", `tool_calls` is empty and the turn ends
 * with `finish_reason=stop`. To the loop that looks like an empty turn.
 *
 * Measured on the official API (sid 41295a4c turn 45 replayed from the Mac):
 * 5/25 identical requests leaked, each ending in `</｜｜DSML｜｜ parameter>` with
 * completion − reasoning = 82..83 tokens (the swallowed tags). It is random
 * per request and most likely while the model writes a long tool argument
 * (a document written in chunks via file_write), so a resend usually works.
 */
object DsmlLeak {

    /**
     * Substrings only DeepSeek's tool-call serialization produces. Kept as one
     * list so a new variant is a one-line change.
     */
    val MARKERS: List<String> = listOf(
        "｜DSML｜", // V4, fullwidth bars; also matches the doubled ｜｜DSML｜｜ form
        "|DSML|", // ASCII-degraded form some relays emit
        "<｜tool▁calls▁begin｜>", // V3 / R1 special tokens (U+2581 separators)
        "<｜tool▁call▁begin｜>",
        "<｜tool▁sep｜>",
        "<｜tool▁call▁end｜>",
        "<｜tool▁calls▁end｜>",
        "tool_calls_begin", // the same tokens written with ASCII underscores
        "tool_call_begin",
    )

    /** Resends of the same request before the loop gives up and asks the user. */
    const val MAX_RETRIES: Int = 1

    /** The first marker found in [reasoning], or null. */
    fun marker(reasoning: String?): String? {
        if (reasoning.isNullOrEmpty()) return null
        return MARKERS.firstOrNull { reasoning.contains(it) }
    }

    /**
     * True when a finished turn is a tool call lost in the reasoning channel:
     * no text this turn, no tool call, a clean stop, and DeepSeek tool-call
     * markup in the reasoning.
     */
    fun isLeak(turnHasText: Boolean, hasToolCalls: Boolean, finishReason: String?, reasoning: String?): Boolean {
        if (turnHasText || hasToolCalls) return false
        if (finishReason != "stop" && finishReason != "end_turn") return false
        return marker(reasoning) != null
    }

    sealed class Step {
        /** Resend the same request; [attempt] is 1-based. */
        data class Retry(val attempt: Int) : Step()

        /** Retries spent: show the notice and offer Resume. */
        object GiveUp : Step()
    }

    fun next(retriesSoFar: Int): Step =
        if (retriesSoFar < MAX_RETRIES) Step.Retry(retriesSoFar + 1) else Step.GiveUp
}
