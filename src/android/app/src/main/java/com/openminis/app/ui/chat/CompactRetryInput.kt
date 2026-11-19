package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage

/**
 * [T-compact-reduced-retry] Oldest slice of a failed compaction range for the
 * last-resort reduced retry. `null` for small ranges — a 5-message
 * "transcript" does not need slicing, it just needs the truncation fallback.
 *
 * [T-compact-reduced-anchor] Returns the OLDEST part, not the newest. A
 * summary replaces everything up to the marker anchor, so whatever is
 * summarized must be a PREFIX of the range. Taking the newest 3/5 while
 * advancing the anchor to the end left the oldest 2/5 behind the anchor but
 * outside both summaries — silent loss of the exact middle segment. The
 * newest part stays active verbatim instead, which is what "the most recent
 * turns are what a conversation builds on" actually requires.
 */
internal fun reducedCompactRetryInput(messages: List<LLMMessage>): List<LLMMessage>? {
    if (messages.size < 12) return null
    return messages.take((messages.size * 3 / 5).coerceAtLeast(8))
}

internal fun coveredCountFor(outcome: CompactOutcome, rangeSize: Int): Int? {
    if (!outcome.succeeded) return null
    if (rangeSize <= 0) return null
    return outcome.coveredMessages.coerceIn(1, rangeSize)
}

/**
 * [T-compact-range-cap] Maximum tokens one compaction attempt may cover.
 *
 * The summarizer has a 6-call budget and ~32k-token segments; feeding it a
 * 1M-token range (the detached-anchor case: range starts at 0) guarantees the
 * budget is exhausted, all stages fail, and the old fallback advanced the
 * marker over everything anyway. A 4-leaf split costs 4 calls (≤6), so cap
 * the range at 4 × segment. The next attempt continues from the new anchor.
 */
internal fun maxCompactRangeTokens(contextWindow: Int): Int {
    val perCall = ChatViewModel.compactSegmentTokenBudget(contextWindow)
    val capacity = perCall.toLong() * 4
    return capacity.coerceIn(8_000L, 160_000L).toInt()
}

/**
 * Last message index (within [start]..[end]) whose range still fits
 * [maxTokens]. The first message is always included even when it alone is
 * over the cap, so a compaction attempt can always make progress.
 */
internal fun cappedCompactEnd(
    history: List<LLMMessage>,
    start: Int,
    end: Int,
    maxTokens: Int,
    estimate: (LLMMessage) -> Int = WalkBack::estimateMessageTokens,
): Int {
    if (end <= start) return end
    var total = 0
    var i = start
    while (i <= end) {
        val cost = estimate(history[i]).coerceAtLeast(0)
        if (i > start && total + cost > maxTokens) break
        total += cost
        i++
    }
    return (i - 1).coerceIn(start, end)
}
