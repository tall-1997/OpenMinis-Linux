package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.R
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.service.ActiveRunContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val INTERRUPTED_TURN_BRIDGE =
    "(Interrupted mid-task by a new user message. Decide based on the new message and overall context " +
        "whether the prior task should continue — do not forget or abandon it unless the user explicitly " +
        "says to stop, or the new message makes clear it is no longer needed.)"

internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
    uiBatcher: UiUpdateBatcher,
    expectedSessionId: String? = null,
): ChatViewModel.InjectedTurn? {
    val run = ActiveRunContext.current()
    val queued = _promptQueue.value.takeIf { it.isNotEmpty() } ?: return null
    val sid = queuedTurnSession(expectedSessionId, run) ?: return null
    try {
        val prepared = prepareQueuedTurn(queued, sid, run) ?: return null
        val userId = persistQueuedTurn(queued, prepared, sid, run) ?: return null
        if (!ownsQueuedTurn(sid, run)) return null
        val queuedIds = queued.mapTo(hashSetOf()) { it.id }
        val newAssistantId = "assistant_${System.currentTimeMillis()}"
        // Wait out any coalesced isStreaming=true updates to the retired bubble.
        uiBatcher.seal(finishedAssistantId)
        val bound = withContext(Dispatchers.Main.immediate) {
            if (!ownsQueuedTurn(sid, run)) return@withContext false
            consumeQueuedPaste(prepared)
            _messages.update { messages -> messages.filterNot { it.queuedPromptId in queuedIds } }
            updateAssistantMessage(
                finishedAssistantId, finishedAccumulatedText, false, finishedAllToolBlocks,
                isAwaitingModelResponse = false,
            )
            val nextAssistant = ChatMessage(
                id = newAssistantId, role = "assistant", content = "", isStreaming = true,
                isAwaitingModelResponse = true, thinkingLevel = _thinkingLevel.value,
            )
            _messages.update { trimLoadedWindow(it + prepared.bubble(userId) + nextAssistant) }
            notePersistedUiRow(userId, userId)
            // Keep the fresh user turn distinct from a preceding user-role tool result.
            appendBoundedHistoryAll(listOf(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = INTERRUPTED_TURN_BRIDGE,
                    contentParts = listOf(AgentContentPart.Text(INTERRUPTED_TURN_BRIDGE)),
                ),
                prepared.message(userId),
            ))
            true
        }
        return ChatViewModel.InjectedTurn(newAssistantId).takeIf { bound && ownsQueuedTurn(sid, run) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(ChatViewModel.TAG, "Queued injection stopped: ${e.javaClass.simpleName}")
        withContext(Dispatchers.Main.immediate) {
            if (ownsQueuedTurn(sid, run)) setInlineError(context.getString(R.string.error_empty_response_generic))
        }
        return null
    }
}

/** Keep the thinking-phase cut separate from turns that have started answering or calling a tool. */
internal fun ChatViewModel.hasQueuedPromptDuringPureThinking(
    toolCalls: List<*>,
    turnTextSb: CharSequence,
): Boolean = toolCalls.isEmpty() && turnTextSb.isEmpty() && _promptQueue.value.isNotEmpty()

/** Shared standalone injection for the post-tool and the thinking-phase boundaries. */
internal suspend fun ChatViewModel.drainQueueIntoStandaloneTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
    uiBatcher: UiUpdateBatcher,
    expectedSessionId: String? = null,
): String? = injectQueuedPromptsAsNewTurn(
    finishedAssistantId, finishedAccumulatedText, finishedAllToolBlocks, uiBatcher, expectedSessionId,
)?.newAssistantId

/**
 * [T-android-queued-message-interrupt-thinking] Success-path cleanup for
 * the deliberate thinking-phase cut: settle the partial thinking block
 * (first text or a tool call normally ends the reasoning phase and records
 * the duration process cards sum — a deliberate cut is the same kind of
 * phase end) and clear lingering auto-retry UI state. The loop still owns
 * its generation record while it handles the next user turn.
 */
internal fun ChatViewModel.settleInterruptedThinkingTurn(
    turn: Int,
    allToolBlocks: MutableList<AssistantBlock>,
) {
    val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
    if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
        allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(
            toolStatus = ToolBlockStatus.SUCCESS,
            durationMs = settleThinkingDurationMs(allToolBlocks[thinkIdx]),
        )
    }
    if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
        _autoRetryAttempt.value = 0
        _autoRetryCountdown.value = 0
    }
}

/** Rewrite the text of a queued prompt without changing its position or attachments. */
internal fun ChatViewModel.editQueued(promptId: String, newText: String): Boolean {
    val trimmed = newText.trim()
    var updated = false
    _promptQueue.update { queue ->
        val idx = queue.indexOfFirst { it.id == promptId }
        if (idx < 0) return@update queue
        val prompt = queue[idx]
        if (trimmed.isBlank() && prompt.attachments.isEmpty()) return@update queue
        updated = true
        queue.toMutableList().apply { this[idx] = prompt.copy(text = trimmed) }
    }
    if (!updated && promptId.startsWith("steer_")) {
        updated = steeringState().queue.updateText(promptId, trimmed)
    }
    if (!updated) return false
    _messages.value = _messages.value.map { m ->
        if (m.queuedPromptId == promptId) m.copy(content = trimmed) else m
    }
    Log.i("ChatViewModel", "Edited queued prompt (${trimmed.length}ch), queue=${_promptQueue.value.size}")
    return true
}

/** Remove a queued prompt and its chat message by prompt id. */
fun ChatViewModel.removeQueuedPrompt(promptId: String) {
    _promptQueue.update { queue -> queue.filterNot { it.id == promptId } }
    _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
}

/** Withdraw a queued message before it gets injected into the agent loop. */
fun ChatViewModel.withdrawQueuedMessage(messageId: String) {
    if (withdrawSteeringPlaceholder(messageId)) {
        Log.i("ChatViewModel", "Withdrew steering placeholder")
        return
    }
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
    if (!msg.isQueued) return
    val pid = msg.queuedPromptId ?: return
    _promptQueue.update { queue -> queue.filterNot { it.id == pid } }
    _messages.value = _messages.value.filterNot { it.id == messageId }
    Log.i("ChatViewModel", "Withdrew queued message, queue=${_promptQueue.value.size}")
}
