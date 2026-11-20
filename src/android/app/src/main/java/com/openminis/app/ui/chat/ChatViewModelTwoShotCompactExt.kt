package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A successful pass describes only the exact prefix submitted to it. */
internal data class CompactOutcome(val summary: String, val coveredMessages: Int, val succeeded: Boolean = true)

internal class CompactStageTimeout(attemptLabel: String, timeoutMs: Long) :
    IllegalStateException("Compaction with $attemptLabel timed out after ${timeoutMs / 1_000}s.")

/** Try every configured stage on the full input, then on the oldest closed prefix. */
internal suspend fun runCompactPasses(
    messages: List<LLMMessage>,
    previousSummary: String?,
    stageCount: Int,
    generate: suspend (List<LLMMessage>, String?, stageIndex: Int, reduced: Boolean) -> String,
): CompactOutcome {
    check(stageCount > 0) { "No LLM provider available for compaction" }
    var lastError: Exception? = null
    val reduced = reducedCompactRetryInput(messages)
    for (input in listOfNotNull(messages, reduced)) {
        for (stage in 0 until stageCount) {
            try {
                val text = generate(input, previousSummary, stage, input === reduced).trim()
                if (ChatViewModel.isCompactSummaryAcceptable(text, input)) {
                    return CompactOutcome(text, input.size)
                }
                lastError = IllegalStateException("Compact summary failed the quality check (${text.length} chars).")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
    }
    throw lastError ?: IllegalStateException("Compaction produced no summary; history was kept")
}

internal suspend fun ChatViewModel.runTwoShotCompact(
    messages: List<LLMMessage>,
    previousSummary: String?,
    stages: List<ChatViewModel.CompactAttempt>,
    timeoutMs: Long,
    sessionId: String = activeSessionId,
    sessionWindowTokens: Int? = null,
): CompactOutcome = runCompactPasses(messages, previousSummary, stages.size) { input, existing, index, reduced ->
    val stage = stages[index]
    val stageNo = index + 1
    if (index == 0) compactCallsIssued.set(0)
    // Large fallback groups retain one leaf per stage, with no split fan-out.
    val passCallBudget = maxOf(ChatViewModel.MAX_COMPACT_LLM_CALLS, stages.size)
    val spent = compactCallsIssued.get()
    val callBudget = passCallBudget - spent - (stages.size - stageNo)
    _compactProgress.value = _compactProgress.value?.copy(
        stage = stageNo,
        stageBudget = stages.size,
        stageKind = stage.kind,
        modelLabel = stage.label,
        nextHint = stage.nextHint,
        depth = 0,
        callsIssued = spent,
        callBudget = passCallBudget,
    )
    val headline = when (stage.kind) {
        "fallback" -> "Compressing ($stageNo/${stages.size}) with fallback model ${stage.label}."
        "session-retry" -> "Compressing ($stageNo/${stages.size}) retrying current model ${stage.label}. No fallback set."
        else -> "Compressing ($stageNo/${stages.size}) with current model ${stage.label}."
    } + when (stage.nextHint) {
        "fallback" -> " If this fails, the next model is tried."
        "session-retry" -> " If this fails, the current model is tried once more."
        else -> " If no attempt succeeds, unsummarized history stays active."
    }
    withContext(Dispatchers.Main) {
        if (activeSessionId != sessionId) throw CancellationException("Compaction session changed")
        if (reduced && index == 0) {
            appendSystemInfo("Full compaction failed. Retrying the oldest complete prefix (${input.size}/${messages.size}).", "compact")
        }
        appendSystemInfo(headline, "compact")
    }
    val stageTimeout = minOf(timeoutMs, 120_000L)
    try {
        withTimeoutOrNull(stageTimeout) {
            generateCompactSummaryWithSplitting(input, existing, stage, callBudget, sessionWindowTokens)
        } ?: throw CompactStageTimeout(stage.label, stageTimeout)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.warning(ChatViewModel.TAG, "[Compact] stage ${stage.kind} failed: ${e.javaClass.simpleName}")
        throw e
    }
}
