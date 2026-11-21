package com.openminis.app.ui.chat

import com.openminis.app.agent.HarnessConfig
import com.openminis.app.data.CompactSliceBoundaries
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.CompactMarkerView
import com.openminis.app.data.repository.RequestHistoryUnavailable
import com.openminis.app.data.repository.SessionTailRow
import com.openminis.app.data.repository.sessionTailDisplayRows
import com.openminis.app.data.repository.toReceiptHistoryRows
import com.openminis.app.data.repository.toView
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.ActiveRun
import com.openminis.app.service.ActiveRunContext
import com.openminis.app.ui.chat.retention.ResidentWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Project one history snapshot using the summary, chunks, and assets of the same marker.
 * A tail budget is request-local and never evidence of coverage. The formal request
 * projection and the compact gate pass `applyTailBudget = false` to see every active row.
 */
internal fun ChatViewModel.effectiveAgentHistoryUncounted(
    applyProgressiveTrim: Boolean = true,
    applyTailBudget: Boolean = true,
    history: List<LLMMessage> = agentHistorySnapshot(),
    windowTokens: Int? = effectiveContextWindowTokens(),
    marker: CompactMarkerView? = _cachedLatestMarker?.toView(),
    /** Reports budgeted-away groups (rows where a detached marker has no known round count). */
    onUncoveredDrop: (Int) -> Unit = {},
    /** Recover an evicted state note only for an actual budgeted request. */
    forRequest: Boolean = false,
    harnessConfig: HarnessConfig = activeHarnessConfig(),
): List<LLMMessage> {
    val summary = marker?.summary
    if (marker == null || summary.isNullOrBlank()) {
        return maybeProgressivelyTrimmed(history, applyProgressiveTrim, windowTokens)
    }

    val chunkRetrieved = fitSummaryChunks(marker.summaryChunks)?.let { json ->
        val query = history.lastOrNull { it.role == LLMMessage.Role.USER }?.content.orEmpty()
        selectSummaryChunks(json, query).takeIf { it.isNotEmpty() }?.joinToString("\n\n---\n\n")
    }
    val envelope = buildCompactSummaryEnvelope(summary, chunkRetrieved, marker.assetsJson)

    if (marker.version >= 2) {
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
        val anchorIdx = anchorId?.let { id -> history.indexOfLast { it.dbMessageId == id } } ?: -1
        if (anchorIdx < 0) {
            val cut = detachedTailStartForWindow(history, windowTokens, applyTailBudget, harnessConfig)
            if (cut > 0) onUncoveredDrop(cut)
            if (forRequest && cut > 0) {
                AppLogger.info(
                    ChatViewModel.TAG,
                    "[Compact] detached marker=${marker.id.take(8)} anchor=${anchorId?.take(8)} " +
                        "resident=${history.size} kept=${history.size - cut} dropped=$cut " +
                        "tailMax=$DETACHED_TAIL_MAX_MESSAGES",
                )
            }
            val state = if (forRequest && cut > 0) {
                stateHydrationPending = true
                evictedStateNote(history, anchorIdx, history.subList(cut, history.size))
            } else null
            val assembled = assembleDetachedCompactHistory(envelope, history, cut, harnessConfig, state)
            return maybeProgressivelyTrimmed(foldHarnessNotes(assembled, preserveFirst = true), applyProgressiveTrim, windowTokens)
        }

        // A legacy marker can sit in a live pair. Continue from before that pair
        // and do not silently budget away any of the newly included material.
        val boundaries = CompactSliceBoundaries(history)
        val activeStart = boundaries.atOrBefore(anchorIdx + 1)
        val walkBack = WalkBack.bounded(
            history = history,
            anchorIdx = activeStart - 1,
            maxUserTextTurns = ChatViewModel.COMPACT_KEEP_RECENT_USER_TURNS,
            maxMessages = 100,
            maxEstimatedTokens = WalkBack.preAnchorTokenBudget(windowTokens),
        )
        // Warm-up is already bounded by tokens. Keep its complete rows and let
        // the shared request projection protect recent results, errors and media.
        val preAnchor = walkBack.priorIdx?.takeIf(boundaries::isClosed)
            ?.let { history.subList(it, activeStart) }.orEmpty()
        val postAnchorRaw = history.subList(activeStart, history.size)
        val postAnchorBudget = PostAnchorTail.budgetTokensForWindow(
            windowTokens, applyTailBudget && activeStart == anchorIdx + 1, harnessConfig,
        )
        val outcome = PostAnchorTail.apply(
            postAnchorRaw,
            PostAnchorTail.Budget(
                budgetTokens = postAnchorBudget,
                digest = { dropped -> DroppedRoundDigest.render(dropped, config = harnessConfig)?.let(::escapeCompactEnvelopeText) },
                config = harnessConfig,
            ),
        )
        val state = if (outcome.droppedRounds > 0) {
            onUncoveredDrop(outcome.droppedRounds)
            if (forRequest) {
                stateHydrationPending = true
                AppLogger.info(
                    ChatViewModel.TAG,
                    "[CompactDiag] postAnchor: kept=${postAnchorRaw.size - outcome.droppedMessages} " +
                        "droppedGroups=${outcome.droppedRounds} droppedMessages=${outcome.droppedMessages} " +
                        "droppedTokens=${outcome.droppedTokens} budget=$postAnchorBudget",
                )
                evictedStateNote(history, anchorIdx, outcome.kept)
            } else null
        } else null

        // Keep the summary and budget notice ahead of the first retained tool step.
        val notice = outcome.notice.takeIf { outcome.droppedMessages > 0 }
        val active = attachCompactSummary(if (notice == null) outcome.kept else outcome.kept.drop(1), envelope)
        val head = listOfNotNull(notice, state).fold(active.first()) { msg, note ->
            appendUserText(msg, note.content.trimStart())
        }
        val assembled = foldHarnessNotes(preAnchor) + foldHarnessNotes(listOf(head) + active.drop(1), preserveFirst = true)
        return maybeProgressivelyTrimmed(assembled, applyProgressiveTrim, windowTokens)
    }

    return projectLegacyCompactHistory(envelope, marker, history)
}

/** Keep the summary even if its carrier was evicted; this projection does not establish coverage. */
internal fun projectLegacyCompactHistory(
    summary: String,
    marker: CompactMarkerView,
    history: List<LLMMessage>,
): List<LLMMessage> {
    val firstKeptId = marker.firstKeptMessageId?.takeIf { it.isNotBlank() }
        ?: marker.boundaryMessageId?.takeIf { it.isNotBlank() }
    val rawStart = if (firstKeptId != null) {
        history.indexOfFirst { it.dbMessageId == firstKeptId }.coerceAtLeast(0)
    } else {
        val id = marker.lastCompactedMessageId?.takeIf { it.isNotBlank() }
        (id?.let { history.indexOfLast { row -> row.dbMessageId == it } } ?: -1) + 1
    }
    // Old markers did not guarantee a closed tool round at their boundary.
    val start = if (rawStart == 0) 0 else CompactSliceBoundaries(history).atOrBefore(rawStart)
    return listOf(compactSummaryMessage(summary)) + history.subList(start, history.size)
}

/** Find a committed state note that is still relevant but was dropped by a tail budget. */
internal fun evictedStateNote(
    snapshot: List<LLMMessage>,
    anchorIdx: Int,
    kept: List<LLMMessage>,
): LLMMessage? {
    if (anchorIdx !in -1 until snapshot.size || kept.any { it.isCompactStateNote() }) return null
    return snapshot.subList(anchorIdx + 1, snapshot.size).lastOrNull { it.isCompactStateNote() }
}

private fun LLMMessage.isCompactStateNote(): Boolean = HarnessNoteMarkers.isUserHarnessNote(this) &&
    content.trimStart().removePrefix("<system-reminder>").trimStart().startsWith(SessionStateSnapshot.MARKER)

internal fun LLMMessage.canCarryCompactSummary(): Boolean = role == LLMMessage.Role.USER &&
    contentParts.none { it is AgentContentPart.ToolResult || it is AgentContentPart.ToolUse } &&
    !HarnessNoteMarkers.isUserHarnessNote(this)

internal fun firstInjectableUserIndex(messages: List<LLMMessage>): Int =
    messages.indexOfFirst { it.canCarryCompactSummary() }

internal fun compactSummaryMessage(summary: String): LLMMessage =
    LLMMessage(LLMMessage.Role.USER, summary, contentParts = listOf(AgentContentPart.Text(summary)))

/** Fold host-only user notes into the nearest preceding user turn without changing persisted rows. */
internal fun foldHarnessNotes(history: List<LLMMessage>, preserveFirst: Boolean = false): List<LLMMessage> {
    if (history.none { HarnessNoteMarkers.isUserHarnessNote(it) }) return history
    val out = ArrayList<LLMMessage>(history.size)
    val leadingNotes = StringBuilder()
    var lastUserTarget = -1
    for ((index, msg) in history.withIndex()) {
        if (!(preserveFirst && index == 0) && HarnessNoteMarkers.isUserHarnessNote(msg)) {
            if (lastUserTarget >= 0) {
                out[lastUserTarget] = appendUserText(out[lastUserTarget], msg.content.trimStart())
            } else {
                leadingNotes.append(msg.content.trimStart()).append('\n')
            }
            continue
        }
        if (msg.role == LLMMessage.Role.USER) {
            if (leadingNotes.isNotEmpty()) {
                out.add(prependUserText(msg, leadingNotes.toString().trimEnd()))
                leadingNotes.clear()
            } else {
                out.add(msg)
            }
            lastUserTarget = out.lastIndex
        } else {
            out.add(msg)
        }
    }
    if (leadingNotes.isNotEmpty()) {
        val note = leadingNotes.toString().trimEnd()
        out.add(LLMMessage(LLMMessage.Role.USER, note, contentParts = listOf(AgentContentPart.Text(note))))
    }
    return out
}

/** Keep reasoning reclamation pure. Tool-result shortening belongs to the shared request layers. */
private fun maybeProgressivelyTrimmed(
    list: List<LLMMessage>,
    apply: Boolean,
    windowTokens: Int?,
): List<LLMMessage> {
    if (!apply || list.size <= ProgressiveTrim.PROTECTED_RECENT_MESSAGES || windowTokens == null) return list
    val estimated = ContextTokenMeter.rawMessagesTokens(list)
    return if (ProgressiveTrim.levelFor(estimated, windowTokens) == ProgressiveTrim.Level.NONE) list
    else ProgressiveTrim.trim(list, ProgressiveTrim.Level.STRIP_OLD_THINKING)
}

internal fun ChatViewModel.appendBoundedHistory(message: LLMMessage) {
    agentHistory.add(message)
    trimAgentHistory()
}

internal fun ChatViewModel.appendBoundedHistoryAll(messages: Collection<LLMMessage>) {
    agentHistory.addAll(messages)
    trimAgentHistory()
}

internal fun ChatViewModel.trimAgentHistory() {
    // The request window decides what the model receives; the resident cache only
    // has a hard memory guard. The old4,000-row cut is no longer a semantic cap.
    val dropped = ArrayList<LLMMessage>()
    val byBytes = ResidentWindow.byteCut(agentHistory)
    if (byBytes > 0) {
        dropped.addAll(agentHistoryHeadSnapshot(byBytes))
        removeAgentHistoryHead(byBytes)
        llmHistoryStartOffset += byBytes
    }
    if (dropped.isNotEmpty()) {
        rememberDigestLines(dropped.map { HistoryDigest.fromMessage(it) })
    }
}

internal fun ChatViewModel.ownsTranscript(sid: String, epoch: Long, run: ActiveRun? = ActiveRunContext.current()): Boolean =
    activeSessionId == sid && snapshotResumeEpoch() == epoch && run?.isStopped != true

/**
 * One bounded, decoded request-history snapshot. Pure read: the caller owns
 * publication, receipt reconciliation and any live-state mutation.
 */
internal data class RequestHistorySnapshot(
    val rows: List<LLMMessage>,
    val receiptRows: List<com.openminis.app.data.repository.ReceiptHistoryRow>,
    val startOffset: Int,
    val digest: List<HistoryDigest.Line>,
    val digestOmitted: Int,
)

/** The same authoritative history the next dispatch will send, without publishing it. */
internal suspend fun ChatViewModel.requestHistorySnapshot(sid: String): RequestHistorySnapshot {
    val window = effectiveContextWindowTokens()
    val readLimit = if (window == null) ChatViewModel.MAX_AGENT_HISTORY_MESSAGES else Int.MAX_VALUE
    val tokenBudget = window?.let { w ->
        (w - maxOf(8_000, w /10)).coerceAtLeast(8_000)
    }
    val tail = chatRepository.loadRequestHistory(sid, readLimit, tokenBudget)
    val parsed = tail.messages.map { toLLMMessage(it) }
    val cut = ResidentWindow.byteCut(parsed)
    val firstLoadedSort = tail.messages.firstOrNull()?.sortOrder
    val prefixLines = if (firstLoadedSort == null) emptyList() else collectDigestLines(firstLoadedSort, sid)
    val prefixCount = if (firstLoadedSort == null) 0 else chatRepository.dao.countMessagesBeforeSort(sid, firstLoadedSort)
    val cutLines = (0 until cut).map { i ->
        val line = HistoryDigest.fromMessage(parsed[i])
        line.copy(text = HistoryDigest.clip(line.role, line.text))
    }
    val allDigest = prefixLines + cutLines
    val digest = HistoryDigest.retainNewest(allDigest)
    val omitted = (prefixCount - prefixLines.size).coerceAtLeast(0) + (allDigest.size - digest.size)
    return RequestHistorySnapshot(
        parsed.drop(cut), tail.messages.toReceiptHistoryRows(), tail.firstMessageOffset + cut, digest, omitted,
    )
}

/** Complete fallible reads and decoding before replacing the live history. */
internal suspend fun ChatViewModel.rebuildAgentHistoryFromDatabase(
    sid: String,
    epoch: Long = snapshotResumeEpoch(),
): Boolean {
    val run = ActiveRunContext.current()
    try {
        return publishOwnedHistory({ ownsTranscript(sid, epoch, run) }, load = {
            val snapshot = requestHistorySnapshot(sid)
            reconcileReceiptRows(sid, snapshot.receiptRows)
            snapshot
        }) { snapshot ->
            agentHistory.clear()
            agentHistory.addAll(snapshot.rows)
            llmHistoryStartOffset = snapshot.startOffset
            toolLoopDetector.reset()
            llmDigestLines.clear()
            llmDigestLines.addAll(snapshot.digest)
            llmDigestOmitted = snapshot.digestOmitted
        }
    } catch (e: RequestHistoryUnavailable) {
        // Run entry points already surface terminal errors and stop their queue drain.
        if (run != null) throw e
        withContext(Dispatchers.Main.immediate) {
            if (ownsTranscript(sid, epoch, run = null)) _error.value = e.message
        }
        return false
    }
}

internal suspend fun ChatViewModel.awaitBoundedHistoryRebuild(sid: String): List<SessionTailRow>? {
    val epoch = snapshotResumeEpoch()
    if (!ownsTranscript(sid, epoch)) return null
    val display = chatRepository.sessionTailDisplayRows(sid, ChatViewModel.MAX_AGENT_HISTORY_MESSAGES)
    if (!rebuildAgentHistoryFromDatabase(sid, epoch) || !ownsTranscript(sid, epoch)) return null
    return display
}

/** Check again after the dispatcher hop, where the non-suspending publish runs. */
internal suspend fun <T : Any> publishOwnedHistory(
    owns: () -> Boolean,
    load: suspend () -> T,
    publish: (T) -> Unit,
): Boolean {
    if (!owns()) return false
    val value = withContext(Dispatchers.IO) { if (owns()) load() else null } ?: return false
    return withContext(Dispatchers.Main.immediate) {
        if (!owns()) return@withContext false
        publish(value)
        true
    }
}
