package com.openminis.app.ui.chat

import com.openminis.app.R
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel.InLoopContextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IN_LOOP_COMPACTIONS = 3

/**
 * Token pressure alone decides compaction. Coverage gaps are represented by a
 * bounded history digest in the request; they must not force a paid compaction
 * while the request is below the configured threshold.
 */
@Suppress("UNUSED_PARAMETER")
internal fun projectedCapacity(
    policy: ContextPolicy,
    tokens: Int,
    window: Int,
    hasCoverageGap: Boolean,
    bypassSoftThreshold: Boolean = false,
): ContextPolicy.CheckResult {
    if (window >0 && tokens >= window) {
        return if (policy.compactThreshold >0 && !bypassSoftThreshold) {
            ContextPolicy.CheckResult.NEEDS_COMPACT
        } else {
            ContextPolicy.CheckResult.EXHAUSTED
        }
    }
    if (bypassSoftThreshold || window <=0) return ContextPolicy.CheckResult.OK
    return policy.check(tokens, window)
}

internal fun ProjectedChatRequest.capacity(bypassSoftThreshold: Boolean = false): ContextPolicy.CheckResult =
    projectedCapacity(policy, estimate.tokens, window, hasCoverageGap, bypassSoftThreshold)

internal suspend fun ChatViewModel.stopAtContextLimit(
    assistantId: String,
    text: String,
    blocks: List<AssistantBlock>,
    resumeEpoch: Long,
) = withContext(Dispatchers.Main) {
    drainLiveAssistantUi(assistantId, text, blocks)
    appendSystemInfo(context.getString(R.string.vm_context_full_unreduced), "compact")
    markLiveInterruptionAndArmResume(resumeEpoch)
}

/** Called after the next request has been assembled with its actual route and tools. */
internal suspend fun ChatViewModel.inLoopContextCheck(
    projection: ProjectedChatRequest,
    compactionsSoFar: Int,
    bypassSoftThreshold: Boolean,
): InLoopContextAction = when (projection.capacity(bypassSoftThreshold)) {
    ContextPolicy.CheckResult.OK -> InLoopContextAction.PROCEED
    ContextPolicy.CheckResult.EXHAUSTED -> InLoopContextAction.STOP
    ContextPolicy.CheckResult.NEEDS_COMPACT -> {
        if (compactionsSoFar >= MAX_IN_LOOP_COMPACTIONS) {
            AppLogger.warning(ChatViewModel.TAG, "[AutoCompact] context still needs compaction after $compactionsSoFar passes")
            InLoopContextAction.STOP
        } else {
            appendSystemInfo(
                context.getString(R.string.vm_context_filling_compacting, projection.estimate.tokens, projection.window),
                "compact",
            )
            if (awaitCompaction()) InLoopContextAction.COMPACTED else InLoopContextAction.STOP
        }
    }
}
