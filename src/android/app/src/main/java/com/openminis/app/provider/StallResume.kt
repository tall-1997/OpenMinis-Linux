package com.openminis.app.provider

/**
 * [T-stall-resume] Resume-from-partial for mid-stream stalls.
 *
 * `streamStallWatchdog` throws [com.openminis.app.data.model.LLMError.TransientError]
 * with `stalledAfterFirstEvent=true` when the stream WAS alive and then went
 * quiet. For that case a plain retry discards the partial text and regenerates
 * from scratch — duplicated content, wasted tokens, and a visible "rewind" in
 * the UI. Instead we carry a bounded tail of the already-streamed text into
 * the next attempt as a continuation instruction so the model picks up where
 * it stopped.
 *
 * [T-stall-resume-seed-shrink] The seed used to be 6000 chars wrapped in
 * triple-quote fencing. Echo-prone models restated the whole fenced block
 * verbatim before continuing (observed 2026-10-05 on 2.0.36: duplication
 * restarts byte-exact at the seed's first character). Two hardening changes:
 * the tail is now 400 chars — enough to locate the resume point, small
 * enough that restating it is cheap to suppress — and the quote fencing is
 * gone (a verbatim quoted block reads like text to reproduce). The
 * deterministic backstop for models that echo anyway is
 * [com.openminis.app.harness.agent.StallEchoStripper], which aligns the retried attempt against the exact
 * [tail] embedded here.
 *
 * `note()` and `tail()` are pure functions so the retry wiring stays
 * testable without a ViewModel.
 */
object StallResume {
    /** Trailing chars of the partial text carried into the retried request. */
    const val SEED_CHARS = 400

    /** The exact tail [note] embeds; the echo stripper aligns against this. */
    fun tail(partialText: String, maxChars: Int = SEED_CHARS): String =
        partialText.trim().takeLast(maxChars)

    /** Build the continuation note, or "" when there is nothing to resume. */
    fun note(partialText: String, maxChars: Int = SEED_CHARS): String {
        val seed = tail(partialText, maxChars)
        if (seed.isEmpty()) return ""
        return buildString {
            append("<stall-resume>")
            append("The previous attempt was interrupted mid-stream. The answer ")
            append("already shown to the user ends with: ")
            append(seed)
            append(" Continue from exactly after that point. Start with the next ")
            append("unfinished sentence. Do not restate, quote, or re-introduce any ")
            append("earlier text.")
            append("</stall-resume>")
        }
    }
}
