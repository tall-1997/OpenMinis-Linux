package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.speech.correction.ScreenContextBuilder
import com.openminis.app.speech.correction.TypedVocabularyBuilder

/**
 * [T-android-voice-viewport-context] Bridges the chat list to voice
 * correction's screen context: which rows are on screen, and what text each
 * row carries.
 *
 * The chat LazyColumn renders `flatItems.asReversed()` keyed by
 * [FlatChatItem.key], so a laid-out row's key identifies its flat item
 * exactly; no index arithmetic against the list's extra header items
 * (compact progress, resume banner) is needed.
 */
internal class VoiceScreenItemsHolder {
    /** The rows currently rendered, oldest first. Written from composition. */
    @Volatile
    var items: List<FlatChatItem> = emptyList()
}

/// [T-android-voice-viewport-context] Creates the holder at a scope both the
/// message list and the input bar can reach; pair with [syncVoiceScreenItems]
/// where the flattened rows are computed.
@androidx.compose.runtime.Composable
internal fun rememberVoiceScreenItems(): VoiceScreenItemsHolder =
    androidx.compose.runtime.remember { VoiceScreenItemsHolder() }

/// [T-android-voice-viewport-context] Publishes the flattened rows to the
/// holder after every successful composition, so the holder always holds what
/// the LazyColumn just rendered. SideEffect, not LaunchedEffect: no restart,
/// and it runs even when only unrelated state changed.
@androidx.compose.runtime.Composable
internal fun syncVoiceScreenItems(holder: VoiceScreenItemsHolder, flatItems: List<FlatChatItem>) {
    androidx.compose.runtime.SideEffect { holder.items = flatItems }
}

/**
 * What a row contributes: user text, assistant reply text, or a tool call's
 * TITLE (the pill's line: `tool_title`, else the tool name; never arguments or
 * output). Headers, thinking, usage, cards and notices carry no text but keep
 * their place for geometry.
 */
internal fun FlatChatItem.toScreenSegment(): ScreenContextBuilder.Segment = when (this) {
    is FlatChatItem.UserBubble -> ScreenContextBuilder.Segment(
        key, message.id, ScreenContextBuilder.Kind.USER,
        TypedVocabularyBuilder.stripAttachmentMarkup(message.content),
    )
    is FlatChatItem.AssistantMarkdownBlock -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.ASSISTANT, rawText,
    )
    is FlatChatItem.AssistantLegacyContent -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.ASSISTANT, content,
    )
    is FlatChatItem.AssistantToolUse -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.TOOL, block.toolTitle.ifEmpty { block.toolName },
    )
    else -> ScreenContextBuilder.Segment(key, "", null, "")
}

/**
 * Capture the snapshot. Call on the main thread: layout info is consistent
 * only there. Returns null when the list has not been laid out.
 */
internal fun captureVoiceScreenSnapshot(
    listState: LazyListState,
    items: List<FlatChatItem>,
    reverseLayout: Boolean,
): ScreenContextBuilder.Snapshot? {
    val info = listState.layoutInfo
    if (items.isEmpty() || info.visibleItemsInfo.isEmpty()) return null
    return ScreenContextBuilder.Snapshot(
        segments = items.map { it.toScreenSegment() },
        visible = info.visibleItemsInfo.mapNotNull { v ->
            (v.key as? String)?.let { ScreenContextBuilder.VisibleItem(it, v.offset, v.size) }
        },
        viewportStart = info.viewportStartOffset,
        viewportEnd = info.viewportEndOffset,
        olderAtLargerOffsets = reverseLayout,
    )
}
