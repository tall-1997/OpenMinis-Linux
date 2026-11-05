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

    fun ladderFor(len: Int, firstTierMs: Long = DEFAULT_FIRST_TIER_MS): Long = when {
        len < 500 -> firstTierMs
        len < 2_000 -> 300L
        len < 32_000 -> 500L
        len < 64_000 -> 1_000L
        len < 128_000 -> 1_500L
        else -> 2_000L
    }
}
