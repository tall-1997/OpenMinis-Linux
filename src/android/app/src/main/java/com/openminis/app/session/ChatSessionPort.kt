package com.openminis.app.session

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.scheduled.PrefilledToolCall
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-agnostic surface for driving a live chat session. Debug / headless RPC
 * binds through [ChatRuntime] instead of importing `ui.chat.ChatViewModel`.
 */
interface ChatSessionPort {
    fun sendMessage(text: String)

    /**
     * Send [text] with tool calls the agent loop runs as its first turn,
     * before asking the model anything — a scheduled task's prefilled call
     * rides here. Abstract on purpose: a port that silently dropped the call
     * would look like it worked while the task lost its first step.
     */
    fun submitPrompt(text: String, prefill: List<PrefilledToolCall>)

    fun cancelStream()
    val isStreaming: StateFlow<Boolean>
    val isCompacting: StateFlow<Boolean>
    val activeEntryId: StateFlow<String?>
    val compactSummary: StateFlow<String?>
    val modelName: StateFlow<String>
    val thinkingLevel: StateFlow<ThinkingLevel>
    fun setThinkingLevel(level: ThinkingLevel)
    fun addAttachment(attachment: InputAttachment)
    fun retryFromMessage(messageId: String)
    fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean
    fun assistantMessageIdForToolBlock(blockId: String): String?
    fun runCompactNow()
    fun compactBefore(dbMessageId: String, includesBoundary: Boolean = false)
    fun revertCompact()
    fun selectEntry(entryId: String)
}

interface ChatSessionBinder {
    fun bind(sessionId: String): ChatSessionPort
    val activeSessionId: String?
}

object ChatRuntime {
    @Volatile
    var binder: ChatSessionBinder? = null

    fun bind(sessionId: String): ChatSessionPort =
        binder?.bind(sessionId)
            ?: error("ChatRuntime.binder not registered — MinisApp.onCreate must install ChatViewModelBinder")
}
