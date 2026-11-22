package com.openminis.app.ui.chat

import com.openminis.app.data.LEGACY_ORPHAN_STOP_NOTE
import com.openminis.app.data.USER_STOP_NOTE
import com.openminis.app.data.db.PersistedMessageStatus
import com.openminis.app.data.isAttestedLegacyStopCarrier
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.PendingTurnRow
import com.openminis.app.data.repository.appendTurnRows
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.ActiveRun
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SubAgentActivityTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-android-stop-dup-row] Decide what text the interrupted-turn persistence
 * should commit.
 *
 *  - [unpersisted] non-empty → the tail the run tracked as not-yet-durable
 *    (current cumulative minus the last round-persisted prefix). Commit it.
 *  - [unpersisted] empty and this run persisted NOTHING yet
 *    ([runPersistedAny] false) → the delta never reached the run tracker
 *    (no ActiveRun, or stream died before the first text delta); rescue the
 *    canonical message content so the user's partial reply survives reload.
 *  - [unpersisted] empty but rounds ARE already durable → there is nothing
 *    left to commit. Returning the canonical cumulative content here (the
 *    pre-fix behaviour) inserted a second assistant row repeating every
 *    already-persisted round, so a reload rendered the reply twice: the
 *    per-round rows as split paragraphs and the duplicate row as one
 *    run-on paragraph under the fold bar.
 */
internal fun resolveInterruptedPartialText(
    unpersisted: String,
    capturedContent: String,
    runPersistedAny: Boolean,
): String = when {
    unpersisted.isNotEmpty() -> unpersisted
    !runPersistedAny -> capturedContent
    else -> ""
}

/** Project only stored, unpaired Stops before the wire sanitizers touch result text. */
internal fun projectLegacyOrphanedStops(history: List<LLMMessage>): List<LLMMessage> {
    if (history.none { it.isAttestedLegacyStopCarrier() }) return history
    val pending = HashMap<String, Int>()
    var projected: MutableList<LLMMessage>? = null
    history.forEachIndexed { index, message ->
        val legacyStop = message.isAttestedLegacyStopCarrier()
        var replacements: MutableList<AgentContentPart>? = null
        message.contentParts.forEachIndexed { partIndex, part ->
            when (part) {
                is AgentContentPart.ToolUse -> {
                    val key = ToolPairingCore.pairKey(part.id, part.name)
                    pending[key] = (pending[key] ?: 0) + 1
                }
                is AgentContentPart.ToolResult -> {
                    val key = ToolPairingCore.pairKey(part.id, part.name)
                    val count = pending[key] ?: 0
                    when {
                        count == 1 -> pending.remove(key)
                        count > 1 -> pending[key] = count - 1
                        legacyStop -> {
                            val parts = replacements ?: message.contentParts.toMutableList().also { replacements = it }
                            parts[partIndex] = AgentContentPart.Text(LEGACY_ORPHAN_STOP_NOTE)
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        replacements?.let { parts ->
            val out = projected ?: history.toMutableList().also { projected = it }
            out[index] = message.copy(content = LEGACY_ORPHAN_STOP_NOTE, contentParts = parts)
        }
    }
    return projected ?: history
}

/** Finish cancellation with captured run/session data, never the newly selected chat. */
internal suspend fun ChatViewModel.finishStoppedRun(
    run: ActiveRun?,
    stoppedSessionId: String,
    stoppedEpoch: Long,
    capturedSessionIds: List<String>,
    capturedAssistantId: String?,
    capturedModelSnapshot: ModelAttributionSnapshot?,
    interruptedLabel: String,
) {
    run?.awaitPersistenceDrained()
    val partial = run?.unpersistedAssistantTextTail().orEmpty()
    var parts = partial.map { AgentContentPart.Text(it.text, it.isProcessText) }
    run?.setCurrentTool(null, null)
    var capturedAssistantDbIds = emptyList<String>()

    fun ownsStoppedTurn() = ownsTranscript(stoppedSessionId, stoppedEpoch, run = null)
    val ownsAfterUi = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (!ownsStoppedTurn()) return@withContext false
        capturedAssistantId?.let(::flushStreamingDelta)
        if (capturedAssistantId != null) {
            // [review 2.1 background-pause-gate] The flush above now drains
            // the FRESHEST delta (parked pendingContent included) into the
            // canonical list first — reading `captured` before that flush
            // would rescue the pre-pause text and re-persist a regressed
            // bubble. Keep this read strictly after the flush.
            val captured = _messages.value.firstOrNull { it.id == capturedAssistantId }
            capturedAssistantDbIds = captured?.sourceDbIds.orEmpty()
            // [T-android-stop-dup-row] Only rescue the canonical cumulative
            // content when this run committed nothing durable yet; otherwise
            // the already-persisted round rows plus this row render the same
            // reply twice after a reload.
            if (parts.isEmpty()) {
                val fallback = resolveInterruptedPartialText("", captured?.content.orEmpty(), run?.hasPersistedAssistantText() == true)
                if (fallback.isNotEmpty()) parts = listOf(AgentContentPart.Text(fallback))
            }
            val byId = partial.associateBy { it.id }.toMutableMap()
            _messages.value = _messages.value.map { message ->
                if (message.role == "assistant" && message.id == capturedAssistantId) {
                    val cancelledBlocks = message.toolBlocks.map { block ->
                        val tail = byId.remove(block.id)
                        when {
                            tail != null -> block.copy(content = tail.text, isProcessText = tail.isProcessText)
                            block.kind == "tool_use" && block.toolStatus in setOf(
                                ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING,
                            ) -> block.copy(toolStatus = ToolBlockStatus.CANCELLED)
                            else -> block
                        }
                    } + byId.values.map { AssistantBlock(it.id, "text", it.text, isProcessText = it.isProcessText) }
                    message.copy(
                        content = if (partial.isEmpty()) message.content else AssistantReplyText.joined(cancelledBlocks),
                        toolBlocks = cancelledBlocks,
                        isStreaming = false,
                        isAwaitingModelResponse = false,
                    )
                } else message
            }
            SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        }
        capturedSessionIds.forEach(SubAgentActivityTracker::clearSession)
        SessionActivityTracker.setInactive(stoppedSessionId)
        true
    }
    if (!ownsAfterUi) return

    val persisted = withContext(NonCancellable + Dispatchers.IO) {
        if (!ownsStoppedTurn()) return@withContext emptyList()
        try {
            // A draft/deleted chat may have no session row to attach to.
            if (chatRepository.getSession(stoppedSessionId) == null || !ownsStoppedTurn()) return@withContext emptyList()
            chatRepository.persistUserStop(
                sessionId = stoppedSessionId,
                partial = parts,
                modelSnapshot = capturedModelSnapshot,
                capturedAssistantDbIds = capturedAssistantDbIds,
                ownsWrite = ::ownsStoppedTurn,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warning(ChatViewModel.TAG, "Could not persist user Stop: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (!ownsStoppedTurn()) return@withContext
        _messages.value = _messages.value.map { message ->
            if (message.id == capturedAssistantId && message.role == "assistant") {
                message.copy(error = interruptedLabel)
            } else message
        }
        if (persisted.isNotEmpty()) {
            appendBoundedHistoryAll(persisted)
            for (message in persisted) {
                val uiId = capturedAssistantId.takeIf { message.role == LLMMessage.Role.ASSISTANT }
                notePersistedUiRow(uiId, checkNotNull(message.dbMessageId))
            }
        }
        if (_promptQueue.value.isNotEmpty()) resumeQueueAfterCancel()
    }
}

/** Only persist observed Stop state. A current-tool snapshot is not a durable call or result. */
internal suspend fun ChatRepository.persistUserStop(
    sessionId: String,
    partial: List<AgentContentPart.Text>,
    modelSnapshot: ModelAttributionSnapshot? = null,
    capturedAssistantDbIds: List<String> = emptyList(),
    ownsWrite: () -> Boolean = { true },
): List<LLMMessage> {
    val rows = buildList {
        if (partial.isNotEmpty()) {
            add(PendingTurnRow(
                role = "assistant",
                partsJson = buildAssistantPartsJson(partial, emptyMap()),
                modelSnapshot = modelSnapshot,
                errorInfo = PersistedMessageStatus.INTERRUPTED_INFO,
            ))
        }
        add(PendingTurnRow("user", hostReminderPartsJson(JSONObject.quote(USER_STOP_NOTE))))
    }
    val stickerRow = if (partial.isEmpty() && capturedAssistantDbIds.isNotEmpty()) {
        lastAssistantMessageId(sessionId)?.takeIf { it in capturedAssistantDbIds }
    } else null
    val saved = appendTurnRows(sessionId, rows, ownsWrite = ownsWrite)
    if (saved.isNotEmpty() && stickerRow != null && ownsWrite()) {
        // The durable host note is authoritative even if refreshing this UI sticker fails.
        try {
            updateMessageErrorInfo(stickerRow, PersistedMessageStatus.INTERRUPTED_INFO)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warning(ChatViewModel.TAG, "Could not restore the Stop sticker: ${e.javaClass.simpleName}")
        }
    }
    return saved.map { row ->
        val isNote = row.role == "user"
        LLMMessage(
            role = if (isNote) LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT,
            content = if (isNote) USER_STOP_NOTE else partial.joinToString("") { it.text },
            contentParts = if (isNote) listOf(AgentContentPart.Text(USER_STOP_NOTE)) else partial,
            dbMessageId = row.id,
            isHostGenerated = isNote,
        )
    }
}
