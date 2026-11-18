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
