package com.openminis.app.ui.chat

import com.openminis.app.data.db.PersistedMessageStatus
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.service.ActiveRun
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SubAgentActivityTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

/** Finish cancellation with captured run/session data, never the newly selected chat. */
internal suspend fun ChatViewModel.finishStoppedRun(
    run: ActiveRun?,
    stoppedSessionId: String,
    capturedSessionIds: List<String>,
    capturedAssistantId: String?,
    capturedModelSnapshot: com.openminis.app.data.model.ModelAttributionSnapshot?,
    interruptedLabel: String,
) {
    run?.awaitPersistenceDrained()
    var partialText = run?.unpersistedAssistantText().orEmpty()
    val stoppedTool = run?.currentToolSnapshot()?.let { Triple(it.callId, it.name, it.args) }
    run?.setCurrentTool(null, null)
    val hasToolResult = stoppedTool?.first?.isNotBlank() == true && stoppedTool.second?.isNotBlank() == true

    withContext(NonCancellable + Dispatchers.Main.immediate) {
        capturedAssistantId?.let(::flushStreamingDelta)
        if (activeSessionId == stoppedSessionId) {
            val captured = _messages.value.firstOrNull { it.id == capturedAssistantId }
            // [T-android-stop-dup-row] Only rescue the canonical cumulative
            // content when this run committed nothing durable yet; otherwise
            // the already-persisted round rows plus this row render the same
            // reply twice after a reload.
            if (partialText.isEmpty()) {
                partialText = resolveInterruptedPartialText(
                    unpersisted = "",
                    capturedContent = captured?.content.orEmpty(),
                    runPersistedAny = run?.hasPersistedAssistantText() == true,
                )
            }
            _messages.value = _messages.value.map { message ->
                if (message.role == "assistant" && (message.id == capturedAssistantId || message.isStreaming)) {
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
            SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        }
        capturedSessionIds.forEach(SubAgentActivityTracker::clearSession)
        SessionActivityTracker.setInactive(stoppedSessionId)
    }

    withContext(NonCancellable + Dispatchers.IO) {
        persistInterruptedTurn(
            sessionId = stoppedSessionId,
            partialText = partialText,
            stoppedTool = stoppedTool,
            modelSnapshot = capturedModelSnapshot,
        )
    }

    withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (activeSessionId != stoppedSessionId) return@withContext
        _messages.value = _messages.value.map { message ->
            if (message.id == capturedAssistantId && message.role == "assistant") {
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

/** Persist a partial assistant turn or pair a cancelled tool result with its saved tool call. */
private suspend fun ChatViewModel.persistInterruptedTurn(
    sessionId: String,
    partialText: String,
    stoppedTool: Triple<String?, String?, String?>?,
    modelSnapshot: com.openminis.app.data.model.ModelAttributionSnapshot?,
) {
    val assistantRowId = chatRepository.lastAssistantMessageId(sessionId)
    if (stoppedTool != null && assistantRowId != null) {
        chatRepository.updateMessageErrorInfo(assistantRowId, PersistedMessageStatus.INTERRUPTED_INFO)
    }
    if (partialText.isNotEmpty()) {
        val parts = JSONArray().put(
            JSONObject().put("type", "text").put(
                "value",
                partialText + "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>",
            ),
        )
        chatRepository.appendMessage(
            sessionId = sessionId,
            role = "assistant",
            partsJson = parts.toString(),
            modelSnapshot = modelSnapshot,
            errorInfo = if (stoppedTool == null) PersistedMessageStatus.INTERRUPTED_INFO else null,
        )
    } else if (assistantRowId != null && stoppedTool == null) {
        chatRepository.updateMessageErrorInfo(assistantRowId, PersistedMessageStatus.INTERRUPTED_INFO)
    }

    val toolId = stoppedTool?.first
    val toolName = stoppedTool?.second
    if (!toolId.isNullOrBlank() && !toolName.isNullOrBlank()) {
        val input = runCatching { JSONObject(stoppedTool.third ?: "{}") }.getOrElse { JSONObject() }
        persistToolResultMessage(
            listOf(
                AgentContentPart.ToolResult(
                    id = toolId,
                    name = toolName,
                    content = ChatViewModel.CANCELLED_MARKER,
                    isError = true,
                ),
            ),
            targetSessionId = sessionId,
        )
    }
}
