package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.data.CompactSliceBoundaries
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.repository.CompactSourceChanged
import com.openminis.app.data.repository.MemorySediment
import com.openminis.app.data.repository.commitCompactPrefix
import com.openminis.app.data.repository.loadCompactSource
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    launchCompaction(anchorIdxOverride, allowDuringProcessing, throughId, onFinished)
}

private fun ChatViewModel.launchCompaction(
    anchorIdxOverride: Int? = null,
    allowDuringProcessing: Boolean = false,
    throughId: String? = null,
    onFinished: ((Boolean) -> Unit)? = null,
): Job? {
    fun reject(message: String): Job? {
        appendSystemInfo(message, "compact")
        onFinished?.invoke(false)
        return null
    }
    if (_isStreaming.value && !allowDuringProcessing) {
        return reject(context.getString(R.string.vm_compact_in_progress_blocked))
    }
    if (_isCompacting.value) return reject(context.getString(R.string.vm_compact_already_running))
    val sid = activeSessionId
    if (sid.isBlank()) return reject(context.getString(R.string.vm_compact_empty_session))
    val nowMs = System.currentTimeMillis()
    if (!CompactionTransaction.retryAllowedNow(sid, nowMs)) {
        AppLogger.info(ChatViewModel.TAG, "[Compact] retry suppressed: last attempt failed and nothing advanced")
        val remaining = CompactionTransaction.cooldownRemainingMs(sid, nowMs)
        return reject(context.getString(R.string.vm_compact_retry_cooldown, ((remaining + 999L) / 1_000L).toInt()))
    }
    val stages = compactStagePlan()
    if (stages.isEmpty()) return reject(context.getString(R.string.vm_compact_no_provider))
    val sessionWindow = effectiveContextWindowTokens()?.takeIf { it > 0 } ?: stages.first().model.contextWindowTokens
    val bound = throughId ?: anchorIdxOverride?.let { index -> agentHistory.getOrNull(index)?.dbMessageId }
    if (anchorIdxOverride != null && bound.isNullOrBlank()) {
        return reject("Cannot compact: the requested boundary isn't persisted.")
    }
    if (!_isCompacting.compareAndSet(false, true)) return reject(context.getString(R.string.vm_compact_already_running))

    markSessionExplicitlyEmptied("compact")
    compactCallsIssued.set(0)
    // Enter try/finally even when cancellation arrives before the dispatcher runs.
    return viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        var succeeded = false
        var timeoutMs = 0L
        var attemptId: String? = null
        var failureReason = "attempt failed"
        suspend fun showInfo(text: String) = withContext(Dispatchers.Main) {
            if (activeSessionId == sid) appendSystemInfo(text, "compact")
        }
        try {
            currentCoroutineContext().ensureActive()
            val source = chatRepository.loadCompactSource(
                sid, bound, allowTerminatedTail = !_isStreaming.value && !SessionActivityTracker.isActive(sid),
            ) { row, raw ->
                decodeCompactMessage(row.id, row.role, raw, mediaStore.mediaBaseDir)
            }
            val boundaries = CompactSliceBoundaries(source.messages, protectPending = true)
            val closedEnd = boundaries.atOrBefore(source.messages.size)
            if (closedEnd == 0) {
                showInfo(if (source.limited || source.messages.isNotEmpty()) {
                    "Cannot compact the oldest uncovered messages: a body is unavailable or a tool round is incomplete. History was kept."
                } else {
                    context.getString(R.string.vm_compact_nothing_to_compact)
                })
                return@launch
            }
            val cap = maxCompactRangeTokens(sessionWindow)
            // Cap the text the summarizer will actually get, not the raw size of
            // images or tool bodies the transcript renderer replaces with text.
            val cappedEnd = cappedCompactEnd(source.messages, 0, closedEnd - 1, cap) { message ->
                ContextTokenMeter.rawTextTokens(buildConversationTextForSummary(listOf(message)))
            } + 1
            val end = boundaries.atOrBefore(cappedEnd).takeIf { it > 0 }
                ?: (1..closedEnd).first(boundaries::isClosed) // Try the first whole round; never skip it.
            if (end != closedEnd) {
                AppLogger.info(ChatViewModel.TAG, "[Compact] range capped at $end/$closedEnd rows (cap=$cap tokens)")
            }
            val input = source.messages.subList(0, end)
            val transcriptChars = buildConversationTextForSummary(input).length
            timeoutMs = ChatViewModel.compactTimeoutMsFor(transcriptChars)
            currentCoroutineContext().ensureActive()
            if (activeSessionId != sid) throw CancellationException("Compaction session changed")
            val journalId = beginCompactionJournal(sid, end - 1, input.size, transcriptChars)
            attemptId = journalId
            _compactProgress.value = ChatViewModel.CompactProgress(
                startedAtMs = System.currentTimeMillis(),
                depth = 0,
                callsIssued = 0,
                callBudget = maxOf(ChatViewModel.MAX_COMPACT_LLM_CALLS, stages.size),
                timeoutSeconds = (timeoutMs / 1_000L).toInt(),
                stage = 1,
                stageBudget = stages.size,
                stageKind = stages.first().kind,
                modelLabel = stages.first().label,
                nextHint = stages.first().nextHint,
            )
            val outcome = runTwoShotCompact(input, source.previousSummary, stages, timeoutMs, sid, sessionWindow)
            val coveredCount = coveredCountFor(outcome, input.size)
                ?.takeIf(boundaries::isClosed) ?: error("Summary did not cover a complete persisted prefix.")
            currentCoroutineContext().ensureActive()
            if (activeSessionId != sid) throw CancellationException("Compaction session changed")
            val marker = withContext(NonCancellable) {
                val marker = chatRepository.commitCompactPrefix(
                    source, coveredCount, outcome.summary,
                    appendChunks = { preceding, text -> appendSummaryChunk(fitSummaryChunks(preceding), text) },
                    mergeAssets = ChatCompactAssets::merge,
                )
                // Beyond the Room commit, cancellation cannot undo coverage.
                succeeded = true
                settleCompactionJournal(sid, journalId, marker.summary.length, coveredCount, marker.id)
                AppLogger.info(
                    ChatViewModel.TAG,
                    "[CompactMeta] summaryChars=${marker.summary.length} covered=$coveredCount/${source.messages.size} " +
                        "chunks=${marker.summaryChunks?.length ?: 0}",
                )
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
                marker
            }
            if (_memoryEnabled.value) {
                val memory = withContext(Dispatchers.Main) {
                    if (activeSessionId == sid && _memoryEnabled.value) sessionMemoryRepo() else null
                }
                if (memory != null) {
                    try {
                        MemorySediment.recordCompaction(memory, marker.summary, coveredCount)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.warning(ChatViewModel.TAG, "[Compact] sediment failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            failureReason = "timed out"
            showInfo(context.getString(R.string.vm_compaction_timed_out, (timeoutMs / 1_000L).toInt(), compactCallsIssued.get()))
        } catch (e: CancellationException) {
            failureReason = REASON_CANCELLED
            if (!succeeded) withContext(NonCancellable) { showInfo("Compaction cancelled.") }
            throw e
        } catch (e: Exception) {
            failureReason = when (e) {
                is CompactStageTimeout -> "timed out"
                is CompactSourceChanged -> "source changed"
                else -> "attempt failed"
            }
            AppLogger.warning(ChatViewModel.TAG, "[Compact] $failureReason: ${e.javaClass.simpleName}")
            if (!succeeded) showInfo(context.getString(R.string.vm_compaction_failed, failureReason))
        } finally {
            if (!succeeded) attemptId?.let { abandonCompactionJournal(sid, it, failureReason) }
            _compactProgress.value = null
            _isCompacting.value = false
            onFinished?.invoke(succeeded)
        }
        if (succeeded && activeSessionId == sid && _promptQueue.value.isNotEmpty()) resumeQueueAfterCancel()
    }.also { compactJob = it }
}

/** A stopped caller must not leave its own paid compaction running in the ViewModel scope. */
internal suspend fun ChatViewModel.awaitCompaction(): Boolean = suspendCancellableCoroutine { cont ->
    if (!cont.isActive) return@suspendCancellableCoroutine
    val job = launchCompaction(allowDuringProcessing = true) { ok ->
        if (cont.isActive) cont.resume(ok) { _, _, _ -> }
    }
    if (job != null) cont.invokeOnCancellation { job.cancel() }
}
