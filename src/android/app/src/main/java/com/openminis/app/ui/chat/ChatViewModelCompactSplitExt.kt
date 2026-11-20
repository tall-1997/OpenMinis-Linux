package com.openminis.app.ui.chat

import com.openminis.app.data.CompactSliceBoundaries
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ContextOverflowDetector
import com.openminis.app.provider.OverflowVerdict
import kotlinx.coroutines.CancellationException

internal suspend fun ChatViewModel.generateCompactSummaryWithSplitting(
    messages: List<LLMMessage>,
    previousSummary: String?,
    attempt: ChatViewModel.CompactAttempt,
    callBudget: Int,
    sessionWindowTokens: Int? = null,
): String = summarizeCompactStage(
    messages = messages,
    previousSummary = previousSummary,
    tokenBudget = ChatViewModel.compactSegmentTokenBudget(
        minOf(attempt.model.contextWindowTokens, sessionWindowTokens ?: Int.MAX_VALUE),
    ),
    callBudget = callBudget,
) { input, depth ->
    val spent = compactCallsIssued.incrementAndGet()
    _compactProgress.value = _compactProgress.value?.copy(depth = depth, callsIssued = spent)
    generateCompactSummary(input, attempt)
}

/** Summarize all segments with the same provider. A failed child fails the entire stage. */
internal suspend fun summarizeCompactStage(
    messages: List<LLMMessage>,
    previousSummary: String?,
    tokenBudget: Int,
    callBudget: Int,
    generate: suspend (CompactSummaryInput, depth: Int) -> String,
): String {
    var remaining = callBudget
    val hardCallCap = ChatViewModel.MAX_COMPACT_LLM_CALLS
    check(callBudget in 1..hardCallCap)

    suspend fun summarize(segment: List<LLMMessage>, previous: String?, depth: Int, reserved: Int): String {
        val input = buildCompactSummaryInput(segment, previous)
        val splitAt by lazy(LazyThreadSafetyMode.NONE) {
            CompactSliceBoundaries(segment, protectPending = true).interiorSplit()
        }

        suspend fun splitCompactHalves(mid: Int): String {
            AppLogger.info(ChatViewModel.TAG, "[Compact] splitting ${segment.size} rows at closed cut $mid (depth=$depth)")
            // Reserve at least one leaf for the right branch while summarizing the left.
            val left = summarize(segment.subList(0, mid), previous, depth + 1, reserved + 1)
            val right = summarize(segment.subList(mid, segment.size), null, depth + 1, reserved)
            return "$left\n\n$right"
        }

        val available = remaining - reserved
        if (ChatViewModel.shouldProactivelySplit(segment.size, input.estimatedTokens, tokenBudget, depth, hardCallCap - available)) {
            splitAt?.let { return splitCompactHalves(it) }
        }
        check(available > 0) { "No summary calls remain for this segment." }
        remaining--
        return try {
            generate(input, depth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (depth < 3 && remaining - reserved >= 2 && shouldSplitCompactStageError(e)) {
                splitAt?.let { return splitCompactHalves(it) }
            }
            throw e
        }
    }

    return summarize(messages, previousSummary, depth = 0, reserved = 0)
}

private fun shouldSplitCompactStageError(error: Exception): Boolean = when (error) {
    is CompactSummaryTermination -> error.isTruncated
    is LLMError -> ChatViewModel.shouldSplitOnError(error) &&
        ContextOverflowDetector.detectFromLlmError(error) is OverflowVerdict.Overflow
    else -> false
}
