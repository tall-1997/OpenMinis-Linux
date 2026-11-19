package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.data.CompactSliceBoundaries
import com.openminis.app.data.repository.MemorySediment
import com.openminis.app.data.repository.commitCompactPrefix
import com.openminis.app.data.repository.loadCompactSource
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Manual compaction blocks an active turn; the agent loop may compact between iterations. */
internal fun ChatViewModel.compactAll(
    anchorIdxOverride: Int? = null,
    allowDuringProcessing: Boolean = false,
    throughId: String? = null,
    onFinished: ((Boolean) -> Unit)? = null,
) {
    fun reject(message: String) {
        appendSystemInfo(message, "compact")
        onFinished?.invoke(false)
    }
    if (_isStreaming.value && !allowDuringProcessing) {
        reject(context.getString(R.string.vm_compact_in_progress_blocked))
        return
    }
    val stages = compactStagePlan()
    if (stages.isEmpty()) {
        reject(context.getString(R.string.vm_compact_no_provider))
        return
    }
    val sid = activeSessionId
    if (sid.isBlank()) {
        reject(context.getString(R.string.vm_compact_empty_session))
        return
    }
    val bound = throughId ?: anchorIdxOverride?.let { index -> agentHistory.getOrNull(index)?.dbMessageId }
    if (anchorIdxOverride != null && bound.isNullOrBlank()) {
        reject("Cannot compact: the requested boundary isn't persisted.")
        return
    }
    if (!_isCompacting.compareAndSet(false, true)) {
        reject(context.getString(R.string.vm_compact_already_running))
        return
    }
    markSessionExplicitlyEmptied("compact")
    compactCallsIssued.set(0)
    // Enter try/finally even when cancellation arrives before the dispatcher runs.
    compactJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        var succeeded = false
        var timeoutMs = 0L
        try {
            currentCoroutineContext().ensureActive()
            val source = chatRepository.loadCompactSource(
                sid, bound, allowTerminatedTail = !_isStreaming.value &&
                    !com.openminis.app.service.SessionActivityTracker.isActive(sid),
            ) { row, raw ->
                decodeCompactMessage(row.id, row.role, raw, mediaStore.mediaBaseDir)
            }
            val boundaries = CompactSliceBoundaries(source.messages, protectPending = true)
            val end = boundaries.atOrBefore(source.messages.size)
            if (end == 0) {
                val text = if (source.limited || source.messages.isNotEmpty()) {
                    "Cannot compact the oldest uncovered messages: a body is unavailable or a tool round is incomplete. History was kept."
                } else {
                    context.getString(R.string.vm_compact_nothing_to_compact)
                }
                withContext(Dispatchers.Main) { appendSystemInfo(text, "compact") }
                return@launch
            }
            val input = source.messages.take(end)
            timeoutMs = ChatViewModel.compactTimeoutMsFor(buildConversationTextForSummary(input).length)
            _compactProgress.value = ChatViewModel.CompactProgress(
                startedAtMs = System.currentTimeMillis(),
                depth = 0,
                callsIssued = 0,
                callBudget = ChatViewModel.COMPACT_STAGE_BUDGET,
                timeoutSeconds = (timeoutMs / 1000L).toInt(),
                stage = 1,
                stageBudget = stages.size,
                stageKind = stages.first().kind,
                modelLabel = stages.first().label,
                nextHint = stages.first().nextHint,
            )
            val outcome = runTwoShotCompact(input, source.previousSummary, stages, timeoutMs)
            val coveredCount = outcome.coveredMessages
            check(coveredCount in 1..input.size && boundaries.isClosed(coveredCount)) {
                "Summary did not cover a complete persisted prefix."
            }
            currentCoroutineContext().ensureActive()
            // Beyond this point, once Room commits we must publish the same marker
            // even if the user cancels. Room failure leaves both caches untouched.
            withContext(NonCancellable) {
                val marker = chatRepository.commitCompactPrefix(
                    source, coveredCount, outcome.summary,
                    appendChunks = { preceding, text ->
                        // [P3-3] Same degradation as the read side: trim an
                        // over-budget legacy pool to its newest fitting records
                        // instead of dropping every earlier snapshot.
                        appendSummaryChunk(fitSummaryChunks(preceding), text)
                    },
                    mergeAssets = ChatCompactAssets::merge,
                )
                succeeded = true
                withContext(Dispatchers.Main) publish@{
                    if (activeSessionId != sid) return@publish
                    _cachedLatestMarker = marker
                    _compactSummary.value = marker.summary
                    stateHydrationPending = true
                    _lastTurnContextTokens.value = 0
                    val coveredIds = source.rows.take(coveredCount).mapTo(HashSet()) { it.id }
                    applyNewCompactDivider(marker, coveredIds)
                    if (source.limited || coveredCount < source.messages.size) {
                        appendSystemInfo("Some messages were not summarized and are still active.", "compact")
                    }
                    refreshContextUsage()
                }
            }
            if (_memoryEnabled.value && activeSessionId == sid) {
                try {
                    MemorySediment.recordCompaction(sessionMemoryRepo(), outcome.summary, coveredCount)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.warning(ChatViewModel.TAG, "[Compact] sediment failed: ${e.message}")
                }
            }
        } catch (e: TimeoutCancellationException) {
            withContext(Dispatchers.Main) {
                if (activeSessionId == sid) appendSystemInfo(
                    context.getString(R.string.vm_compaction_timed_out, (timeoutMs / 1000L).toInt(), compactCallsIssued.get()),
                    "compact",
                )
            }
        } catch (e: CancellationException) {
            if (!succeeded) withContext(NonCancellable + Dispatchers.Main) {
                if (activeSessionId == sid) appendSystemInfo("Compaction cancelled.", "compact")
            }
            throw e
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "Compact failed", e)
            withContext(Dispatchers.Main) {
                if (activeSessionId == sid) appendSystemInfo(
                    context.getString(R.string.vm_compaction_failed, e.message ?: e.javaClass.simpleName),
                    "compact",
                )
            }
        } finally {
            _compactProgress.value = null
            _isCompacting.value = false
            onFinished?.invoke(succeeded)
        }
        if (succeeded && activeSessionId == sid && _promptQueue.value.isNotEmpty()) resumeQueueAfterCancel()
    }
}

/** Suspend until the model pass and the verified marker write have both settled. */
internal suspend fun ChatViewModel.awaitCompaction(): Boolean = suspendCancellableCoroutine { cont ->
    compactAll(allowDuringProcessing = true) { ok ->
        if (cont.isActive) cont.resume(ok) { _, _, _ -> }
    }
}
