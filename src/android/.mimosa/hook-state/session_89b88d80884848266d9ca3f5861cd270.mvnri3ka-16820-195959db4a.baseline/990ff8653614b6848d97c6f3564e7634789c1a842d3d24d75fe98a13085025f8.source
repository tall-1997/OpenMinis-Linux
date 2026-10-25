package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.CancellationException

/**
 * Summarize [messages], recursively halving when a whole-input attempt
 * fails. Mirrors iOS `generateCompactSummaryWithSplitting`.
 *
 * Depth cap = 3 (matches iOS) so a pathologically large conversation
 * still terminates instead of fanning out indefinitely. At each split we
 * halve by message count, summarize each half independently, then
 * concatenate the partial summaries oldest-first. The concatenation is a
 * plain string join, NOT a further LLM call — see the comment at the join
 * for why the extra round-trip was removed. Both platforms must keep this
 * the same, or the summary a session carries differs by device.
 */
internal suspend fun ChatViewModel.generateCompactSummaryWithSplitting(
    messages: List<LLMMessage>,
    previousSummary: String? = null,
    depth: Int = 0,
): String {
    val transcript = buildConversationTextForSummary(messages)
    val conversationText = if (previousSummary.isNullOrBlank()) {
        transcript
    } else {
        "Previous context summary:\n$previousSummary\n\n" +
            "New conversation to merge:\n$transcript"
    }
    val tokenBudget = ChatViewModel.compactSegmentTokenBudget(currentModel?.contextWindow ?: 128_000)
    val estimatedTokens = ChatViewModel.estimateCompactTokens(conversationText)
    if (compactAllowSplit && ChatViewModel.shouldProactivelySplit(
            messages.size,
            estimatedTokens,
            tokenBudget,
            depth,
            compactCallsIssued.get(),
        )
    ) {
        AppLogger.info(
            ChatViewModel.TAG,
            "[Compact] proactive split ${messages.size} msgs ~$estimatedTokens tok " +
                "(budget=$tokenBudget, depth=$depth)",
        )
        return splitCompactHalves(messages, previousSummary, depth)
    }
    // [T-android-compact-runaway] Spend one unit of the run's call budget.
    // The depth cap bounds how DEEP the recursion goes; this bounds how
    // WIDE it gets in total, which is what actually determines wall-clock
    // time when each call is slow rather than failing fast.
    val spent = compactCallsIssued.incrementAndGet()
    if (spent > ChatViewModel.MAX_COMPACT_LLM_CALLS) {
        throw IllegalStateException(
            "compaction exceeded its budget of ${ChatViewModel.MAX_COMPACT_LLM_CALLS} model calls"
        )
    }
    // [T-android-compact-progress] Publish before the call so the UI shows
    // the segment that is actually running, not the one that just finished.
    _compactProgress.value = _compactProgress.value?.copy(
        depth = depth,
        callsIssued = spent,
    )
    return try {
        generateCompactSummary(conversationText)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!compactAllowSplit || !isSegmentRetryableError(e) || messages.size < 2 || depth >= 3) {
            throw e
        }
        // Don't start a split we cannot afford to finish: a half that
        // immediately throws on budget would discard the sibling's work.
        if (compactCallsIssued.get() + 2 > ChatViewModel.MAX_COMPACT_LLM_CALLS) {
            AppLogger.info(
                ChatViewModel.TAG,
                "[Compact] not splitting at depth=$depth — " +
                    "${compactCallsIssued.get()}/${ChatViewModel.MAX_COMPACT_LLM_CALLS} calls already spent",
            )
            throw e
        }
        AppLogger.info(
            ChatViewModel.TAG,
            "[Compact] Splitting ${messages.size} messages after retryable error (depth=$depth)",
        )
        return splitCompactHalves(messages, previousSummary = null, depth)
    }
}
