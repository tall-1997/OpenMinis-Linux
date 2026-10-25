package com.openminis.app.session

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.di.AppGraph
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-agnostic surface for driving a live chat session. Debug / headless RPC
 * binds through [ChatRuntime] instead of importing `ui.chat.ChatViewModel`.
 */
interface ChatSessionPort {
    fun sendMessage(text: String)
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
    /**
     * The binder instance lives in [AppGraph] (installed once from
     * MinisApp.onCreate); this object stays the UI-agnostic lookup surface.
     */
    fun bind(sessionId: String): ChatSessionPort =
        AppGraph.chatBinderOrNull()?.bind(sessionId)
            ?: error("AppGraph.chatBinder not installed — MinisApp.onCreate must install ChatViewModelBinder")
}
