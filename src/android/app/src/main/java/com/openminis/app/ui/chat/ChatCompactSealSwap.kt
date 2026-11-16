package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-inloop-compact-divider-order / GH#235][T-context-overflow-usage]
 * Shared seal+swap step of the in-loop compaction sites: seal the assistant
 * bubble the run has been writing into (flush its text, stop streaming, drop
 * the row entirely when nothing was rendered), then continue in a FRESH
 * placeholder BELOW the "N messages compacted" divider compactAll()
 * tail-appends — so post-compaction output renders above nothing and below
 * the divider instead of continuing inside the sealed bubble.
 *
 * Extracted so the error-path and success-path overflow compaction
 * (ChatViewModelAgentLoopExt.compactAndRetryForOverflow) and the in-loop
 * COMPACTED branch share ONE implementation instead of three drifting copies.
 * Callers reset their loop-scope accumulators afterwards (assistantId /
 * accumulatedText / per-turn builders) — those are caller-owned state.
 */
internal suspend fun ChatViewModel.sealCompactedBubbleAndSwap(
    sealedId: String,
    sealedText: String,
    sealedToolBlocks: List<AssistantBlock>,
    freshAssistantId: String,
    thinkingLevel: ThinkingLevel,
) {
    withContext(Dispatchers.Main) {
        // Flush whatever the sealed bubble accumulated and stop it streaming,
        // so it renders as finished history.
        updateAssistantMessage(
            sealedId,
            sealedText,
            false,
            sealedToolBlocks,
            isAwaitingModelResponse = false,
        )
        // If compaction fired before the bubble produced anything, it would
        // render as an empty row stranded above the divider — drop it.
        val sealed = _messages.value.firstOrNull { it.id == sealedId }
        if (sealed != null && sealed.content.isEmpty() && sealed.toolBlocks.isEmpty()) {
            _messages.value = _messages.value.filterNot { it.id == sealedId }
            AppLogger.info(ChatViewModel.TAG, "[Compact] overflow: dropped empty sealed bubble $sealedId")
        }
        _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
            id = freshAssistantId,
            role = "assistant",
            content = "",
            isStreaming = true,
            isAwaitingModelResponse = true,
            thinkingLevel = thinkingLevel,
        ))
    }
    dropStreamingEntry(sealedId)
}
