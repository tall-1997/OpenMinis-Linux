package com.openminis.app.ui.chat

/**
 * [T-android-throttle-ladder-converge] Single source for the streaming
 * time-throttle ladder ported verbatim from iOS AIChatViewModel+SSEStream
 * (the `throttle` tiers). Tiers scale with total content length to hold the
 * Pixel 4a ANR / Pixel 6 Scudo-OOM line on dense streams while keeping short
 * replies responsive:
 *
 *   < 500  : [firstTierMs]   < 2000 : 300ms    < 32K  : 500ms
 *   < 64K  : 1000ms          < 128K : 1500ms   else   : 2000ms
 *
 * Three call sites consumed this ladder (byte-identical except one tier):
 *  - StreamSessionController.streamFlushThrottleMs — dual-path text flush gate
 *  - StreamingMarkdownText.streamingThrottleFor — per-fragment parse throttle
 *  - ChatViewModelAgentLoopExt.textDeltaThrottleMs — tool input-delta pill gate
 *
 * The agent-loop variant runs a FASTER first tier (150ms vs 200ms) so
 * command/url previews in the pill stay legible at low latency on short
 * inputs; the rest of the ladder is shared. The difference is kept and
 * expressed via [firstTierMs] rather than silently unified.
 */
internal object StreamingThrottle {
    /** First tier for the flush/parse surfaces (agent loop passes 150L). */
    const val DEFAULT_FIRST_TIER_MS: Long = 200L

    /**
     * [T-android-tail-sprint] When this many characters have arrived but are
     * still unflushed, the ladder is clamped to [SPRINT_MS]: the throttle
     * exists to bound per-flush render cost on dense streams, not to hold
     * back content that already reached the app. A gateway/relay that
     * buffers and then bursts the stream tail (or a smooth-forwarding relay)
     * otherwise leaves the display seconds behind the arrival — the ladder
     * climbs with total length while the burst lands inside one or two
     * gates. 2000 chars ≈ one screen of text; 300ms keeps the Pixel 4a
     * flush/parse budget (a long-document markdown parse runs on
     * Dispatchers.Default and stays far under that) while catching up at
     * ~3 batches/second.
     */
    const val TAIL_SPRINT_CHARS: Int = 2_000
    const val SPRINT_MS: Long = 300L

    /**
     * [T-android-tail-sprint] Sprint ceiling for the parse surface. A
     * full-document markdown parse costs several times a string flush and
     * its allocation rate is what the Pixel 6 Scudo-OOM note budgets
     * against, so the render hop clamps to the ladder's own <64K tier (1s)
     * instead of the 300ms flush sprint: still 2x the long-document
     * cadence, allocation-safe to run for a whole burst.
     */
    const val PARSE_SPRINT_MS: Long = 1_000L

    fun ladderFor(len: Int, firstTierMs: Long = DEFAULT_FIRST_TIER_MS): Long = when {
        len < 500 -> firstTierMs
        len < 2_000 -> 300L
        len < 32_000 -> 500L
        len < 64_000 -> 1_000L
        len < 128_000 -> 1_500L
        else -> 2_000L
    }

    /**
     * The ladder, clamped when a large backlog has built up: normal streams
     * (small unflushed delta) get the exact ladder above; a tail burst gets
     * the sprint cadence ([sprintMs]) so the display chases the arrival
     * instead of the document length. Flush surfaces keep [SPRINT_MS]; the
     * parse surface passes [PARSE_SPRINT_MS] — a full-document markdown
     * parse is the Scudo-OOM budget line, so it sprints less aggressively.
     */
    fun throttleFor(
        len: Int,
        unflushedChars: Int,
        firstTierMs: Long = DEFAULT_FIRST_TIER_MS,
        sprintMs: Long = SPRINT_MS,
    ): Long =
        if (unflushedChars >= TAIL_SPRINT_CHARS) {
            minOf(ladderFor(len, firstTierMs), sprintMs)
        } else {
            ladderFor(len, firstTierMs)
        }
}
