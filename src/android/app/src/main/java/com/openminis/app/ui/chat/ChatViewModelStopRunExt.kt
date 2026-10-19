package com.openminis.app.ui.chat

import com.openminis.app.data.db.PersistedMessageStatus
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.service.ActiveRun
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SubAgentActivityTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-stop-cleanup-race] Wait (bounded) for the background persistence
 * of the interrupted turn so the next run rebuilds history from a transcript
 * that includes it. A rebuild that beats the write sees the OLD context tail
 * and silently drops the progress made before the stop — also after switching
 * models, since the switch does not change which rows are in the DB.
 *
 * Safe from any dispatcher; no-op when nothing was stopped. The wait is
 * cancellable: if this run is itself cancelled, the cleanup keeps running on
 * its own scope.
 */
internal suspend fun ChatViewModel.awaitInterruptedTurnPersisted() {
    val job = stopCleanupJob ?: return
    withTimeoutOrNull(ChatViewModel.STOP_CLEANUP_JOIN_TIMEOUT_MS) { job.join() }
}

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

/**
 * DB rows written by the stop path, so the canonical UI bubble can own them.
 *
 * [T-android-stop-row-link] Without this linkage the rows are invisible to the
 * chat timeline ledger: `persistInterruptedTurn` appended them after the
 * in-memory queued bubble, and the next tail-attach spliced them back into the
 * painted window BELOW that bubble — the queued prompt's round then started
 * before the interrupted assistant row, its new run merged into the same round,
 * and the process cards lost their turn boundary (the reported "同一个步骤,
 * 运行卡片无间隔"). Linking the ids also updates the ledger cursor through
 * notePersistedUiRow, so the tail attach never re-fetches an already-painted
 * row.
 */
internal data class InterruptedTurnRows(
    val assistantRowId: String?,
    val toolResultRowId: String?,
)

/** Finish cancellation with captured run/session data, never the newly selected chat. */
internal suspend fun ChatViewModel.finishStoppedRun(
    run: ActiveRun?,
    stoppedSessionId: String,
    /**
     * [T-android-stop-cleanup-race] The stream job that was stopped. The cleanup
     * runs from a background scope after `job.join()`, so by the time it lands
     * the user may already have sent / resumed a NEW turn. UI mutations and
     * agentHistory appends must only happen while this is still the current
     * stream job — otherwise the late cleanup marks the new bubble cancelled,
     * clears its streaming flags (the new run's process visually disappears)
     * and appends a stale partial tail to the new run's history.
     */
    stoppedJob: Job?,
    capturedSessionIds: List<String>,
    capturedAssistantId: String?,
    /** Last streaming assistant id at stop time, used when the run had not attached one yet. */
    fallbackAssistantId: String?,
    capturedModelSnapshot: com.openminis.app.data.model.ModelAttributionSnapshot?,
    interruptedLabel: String,
) {
    run?.awaitPersistenceDrained()
    var partialText = run?.unpersistedAssistantText().orEmpty()
    val stoppedTool = run?.currentToolSnapshot()?.let { Triple(it.callId, it.name, it.args) }
    run?.setCurrentTool(null, null)
    val hasToolResult = stoppedTool?.first?.isNotBlank() == true && stoppedTool?.second?.isNotBlank() == true
    val stillCurrentRun = stoppedJob == null || streamJob === stoppedJob
    // The bubble this stop owns: the run's attached id, or the streaming
    // bubble captured at cancel time (a run killed before attachAssistantMessage).
    val targetAssistantId = capturedAssistantId ?: fallbackAssistantId
    // Snapshot of the stopped bubble's blocks, read on Main below; used to
    // round-trip the in-flight tool call's metadata into persistence.
    var capturedBlocks: List<AssistantBlock> = emptyList()

    withContext(NonCancellable + Dispatchers.Main.immediate) {
        targetAssistantId?.let(::flushStreamingDelta)
        // Always settle the bubble that belonged to the stopped run — even
        // when a newer run has taken over, its predecessor must stop showing
        // the streaming spinner. The fence is the CAPTURED id: never the
        // `isStreaming` flag, which is true for the new run's bubble too.
        val captured = targetAssistantId?.let { id -> _messages.value.firstOrNull { it.id == id } }
        capturedBlocks = captured?.toolBlocks.orEmpty()
        if (partialText.isEmpty()) {
            // [T-android-stop-dup-row] Only rescue the canonical cumulative
            // content when this run committed nothing durable yet; otherwise
            // the already-persisted round rows plus this row render the same
            // reply twice after a reload.
            partialText = resolveInterruptedPartialText(
                unpersisted = "",
                capturedContent = captured?.content.orEmpty(),
                runPersistedAny = run?.hasPersistedAssistantText() == true,
            )
        }
        if (activeSessionId == stoppedSessionId && targetAssistantId != null) {
            _messages.value = _messages.value.map { message ->
                if (message.role == "assistant" && message.id == targetAssistantId) {
                    val cancelledBlocks = message.toolBlocks.map { block ->
                        if (block.kind == "tool_use" && block.toolStatus in setOf(
                                ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING,
                            )
                        ) block.copy(toolStatus = ToolBlockStatus.CANCELLED) else block
                    }
                    message.copy(
                        toolBlocks = cancelledBlocks,
                        isStreaming = false,
                        isAwaitingModelResponse = false,
                    )
                } else message
            }
        }
        if (stillCurrentRun && activeSessionId == stoppedSessionId) {
            // Global session state may only be cleared while no newer run
            // owns it: otherwise this would clear the NEW run's tool spinner
            // and mark its session inactive.
            SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
            capturedSessionIds.forEach(SubAgentActivityTracker::clearSession)
            SessionActivityTracker.setInactive(stoppedSessionId)
        }
    }

    val rows = withContext(NonCancellable + Dispatchers.IO) {
        persistInterruptedTurn(
            sessionId = stoppedSessionId,
            partialText = partialText,
            stoppedTool = stoppedTool,
            capturedBlocks = capturedBlocks,
            modelSnapshot = capturedModelSnapshot,
        )
    }

    // [T-android-stop-row-link] Give the canonical bubble ownership of the rows
    // just written (and advance the timeline cursor past them) before any
    // queued-prompt drain can append its user row below. See InterruptedTurnRows.
    withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (targetAssistantId != null) {
            rows.assistantRowId?.let { notePersistedUiRow(targetAssistantId, it) }
            rows.toolResultRowId?.let { notePersistedUiRow(targetAssistantId, it) }
        }
    }

    withContext(NonCancellable + Dispatchers.Main.immediate) {
        // A newer run owns the conversation now; appending the old tail to
        // agentHistory would duplicate the interrupted turn in its context.
        if (!stillCurrentRun || activeSessionId != stoppedSessionId) return@withContext
        _messages.value = _messages.value.map { message ->
            if (message.id == targetAssistantId && message.role == "assistant") {
                message.copy(error = interruptedLabel)
            } else message
        }
        if (partialText.isNotEmpty() && !hasToolResult) {
            appendBoundedHistory(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = partialText,
                    contentParts = listOf(
                        AgentContentPart.Text(partialText),
                        AgentContentPart.Text(
                            "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>",
                        ),
                    ),
                ),
            )
        }
        // Match the persistence order in persistInterruptedTurn(): the
        // assistant interruption is followed by the synthetic tool result.
        // Resume must see that complete pair before a queued prompt starts.
        val cancelledToolParts = stoppedTool?.let { (toolId, toolName, _) ->
            if (!toolId.isNullOrBlank() && !toolName.isNullOrBlank()) {
                listOf(
                    AgentContentPart.ToolResult(
                        id = toolId,
                        name = toolName,
                        content = ChatViewModel.CANCELLED_MARKER,
                        isError = true,
                    ),
                )
            } else null
        }
        if (!cancelledToolParts.isNullOrEmpty()) {
            val alreadyRecorded = agentHistory.any { message ->
                message.contentParts.any { part ->
                    part is AgentContentPart.ToolResult &&
                        cancelledToolParts.any { it.id == part.id && it.name == part.name }
                }
            }
            if (!alreadyRecorded) {
                appendBoundedHistory(
                    LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = cancelledToolParts),
                )
            }
        }
        if (_promptQueue.value.isNotEmpty()) resumeQueueAfterCancel()
    }
}

/**
 * Persist a partial assistant turn or pair a cancelled tool result with its saved tool call.
 *
 * [T-android-stop-tool-turn] When the user stops while a tool is in flight, the
 * agent loop has not written this turn yet (turns are persisted only AFTER
 * their tools run). The old code persisted just the text plus a CANCELLED
 * tool_result, which left an orphan result in the transcript: the interrupted
 * tool_use, its pill and its arguments vanished on the next history rebuild —
 * the "运行过程丢失一部分" report. The tool_use is now written in the same
 * assistant row as the unpersisted text, immediately before its result, so the
 * pair round-trips and the next run sees the complete step.
 */
private suspend fun ChatViewModel.persistInterruptedTurn(
    sessionId: String,
    partialText: String,
    stoppedTool: Triple<String?, String?, String?>?,
    capturedBlocks: List<AssistantBlock>,
    modelSnapshot: com.openminis.app.data.model.ModelAttributionSnapshot?,
): InterruptedTurnRows {
    val assistantRowId = chatRepository.lastAssistantMessageId(sessionId)
    val toolId = stoppedTool?.first
    val toolName = stoppedTool?.second
    val hasTool = !toolId.isNullOrBlank() && !toolName.isNullOrBlank()
    val reminder =
        "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"
    val textForRow = if (partialText.isNotEmpty()) partialText + reminder else ""

    var newAssistantRowId: String? = null
    if (hasTool) {
        val parts = mutableListOf<AgentContentPart>()
        if (textForRow.isNotEmpty()) parts += AgentContentPart.Text(textForRow)
        val rawArgs = stoppedTool?.third
        val input = runCatching { JSONObject(rawArgs ?: "{}") }.getOrElse { JSONObject() }
        val meta = capturedBlocks.firstOrNull { it.id == toolId }
        parts += AgentContentPart.ToolUse(
            id = toolId!!,
            name = toolName!!,
            input = input,
            thoughtSignature = meta?.thoughtSignature,
        )
        val metaMap = meta?.let { mapOf(it.id to it) } ?: emptyMap()
        newAssistantRowId = chatRepository.appendMessage(
            sessionId = sessionId,
            role = "assistant",
            partsJson = buildAssistantPartsJson(parts, metaMap),
            modelSnapshot = modelSnapshot,
            errorInfo = PersistedMessageStatus.INTERRUPTED_INFO,
        ).id
    } else if (partialText.isNotEmpty()) {
        val parts = JSONArray().put(
            JSONObject().put("type", "text").put("value", textForRow),
        )
        newAssistantRowId = chatRepository.appendMessage(
            sessionId = sessionId,
            role = "assistant",
            partsJson = parts.toString(),
            modelSnapshot = modelSnapshot,
            errorInfo = PersistedMessageStatus.INTERRUPTED_INFO,
        ).id
    } else if (assistantRowId != null) {
        chatRepository.updateMessageErrorInfo(assistantRowId, PersistedMessageStatus.INTERRUPTED_INFO)
    }

    var toolResultRowId: String? = null
    if (hasTool) {
        toolResultRowId = persistToolResultMessage(
            listOf(
                AgentContentPart.ToolResult(
                    id = toolId!!,
                    name = toolName!!,
                    content = ChatViewModel.CANCELLED_MARKER,
                    isError = true,
                ),
            ),
            targetSessionId = sessionId,
        )
    }
    return InterruptedTurnRows(newAssistantRowId, toolResultRowId)
}
