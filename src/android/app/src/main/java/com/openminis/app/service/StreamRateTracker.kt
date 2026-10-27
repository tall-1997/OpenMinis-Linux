package com.openminis.app.service

/**
 * Wall-clock window of an agent run's streamed text, used for the per-reply
 * tokens-per-second footer readout. [onTextDelta] marks stream activity;
 * [takeDurationMs] closes the current window (first→last delta) and returns
 * it, resetting so the next persisted row measures only its own window.
 *
 * Thread-safe: deltas arrive on the agent-loop dispatcher, takes happen
 * inside the NonCancellable persistence block.
 *
 * [T-android-usage-tok-s]
 */
class StreamRateTracker {
    private var firstDeltaMs = 0L
    private var lastDeltaMs = 0L

    @Synchronized
    fun onTextDelta(nowMs: Long = System.currentTimeMillis()) {
        if (firstDeltaMs == 0L) firstDeltaMs = nowMs
        lastDeltaMs = nowMs
    }

    /** first→last delta window; null when the window saw no streamed text. Resets. */
    @Synchronized
    fun takeDurationMs(): Long? {
        val duration = if (firstDeltaMs == 0L) null else lastDeltaMs - firstDeltaMs
        firstDeltaMs = 0L
        lastDeltaMs = 0L
        return duration?.takeIf { it > 0L }
    }
}

/**
 * Output speed for the usage footer. The guards keep noisy numbers out of
 * the UI: windows under 500ms and replies under 5 output tokens read as
 * near-fixed text, not a measurable stream. Pure — JVM-tested.
 */
fun outputTokensPerSecond(outputTokens: Int, durationMs: Long): Int? {
    if (outputTokens < 5 || durationMs < 500L) return null
    return (outputTokens * 1000L / durationMs).toInt()
}
