package com.openminis.app.ui.chat

// [T-android-split-chat] Shared constants and small data holders extracted
// verbatim from ChatScreen.kt (architecture ceiling). File-private
// declarations were flipped private->internal so the remaining ChatScreen.kt
// body (same package) still resolves them — the documented pattern of the
// earlier ChatScreen splits.

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// iOS ChatColors equivalent
internal val ToolCheckColor = Color(0xFF34C759) // iOS .green
internal val ToolErrorColor = Color(0xFFFF3B30) // iOS .red
internal val ToolCancelColor = Color(0xFFFFCC00) // iOS .yellow
// Memory tool accent — matches iOS `.pink` on SF Symbols.
internal val ToolMemoryAccent = Color(0xFFFF2D55)
// Sparkle gradient colors (iOS uses linear gradient)
internal val SparkleColor1 = Color(0xFFB8B096) // rgb(0.72, 0.69, 0.59)
internal val SparkleColor2 = Color(0xFF99998C) // rgb(0.6, 0.6, 0.55)

// T129: cap photo/video and file pickers at 50 items per launch. Above this
// count Android's PickMultipleVisualMedia silently truncates anyway, but our
// document picker has no native cap — so we apply the same limit on both
// sides and toast the user when their selection is trimmed. Mirrors iOS
// PHPickerConfiguration.selectionLimit = 50.
internal const val ATTACHMENT_PICK_LIMIT = 50

/** One gesture window for "the user just took the viewport": a single 1 s covers a fling settle, and SEND_FOLLOW_GRACE_MS shares it so a just-sent message's transient "not at bottom" read is bypassed. */
internal const val USER_SCROLL_INTERRUPT_MS = 1_000L
internal const val SEND_FOLLOW_GRACE_MS = USER_SCROLL_INTERRUPT_MS

// [T-card-autoclose-pending] The process-card settle window (reply text lands
// first, last steps stay readable for a beat) and the idle poll of the closer
// loop below while nothing is pending.
internal const val PROCESS_CARD_AUTO_CLOSE_MS = 500L
internal const val PROCESS_CARD_AUTO_CLOSE_IDLE_POLL_MS = 500L

/** [T-android-chat-max-content-width] Reading-measure cap for the conversation and composer; 900dp verbatim from iOS maxContentWidth (dp maps 1:1 to pt). widthIn is an upper bound, so narrower windows are unaffected. */
internal val CHAT_MAX_CONTENT_WIDTH = 900.dp

// [T-android-tool-autoscroll] Combined signal for the streaming auto-follow
// LaunchedEffect. data class so distinctUntilChanged uses structural equality
// — any field flip propagates a tick. Per-block (id, kind, status, length)
// folded into [blockSig] (FNV-1a 64-bit hash) so a RUNNING→SUCCESS flip on a
// tool block, a new block appearing (id flips), or a kind change all wake the
// collector even when growth/size/awaiting alone would have stayed equal.
internal data class ScrollFollowKey(
    val lastIndex: Int,
    val growth: Long,
    val toolBlockCount: Int,
    val awaiting: Boolean,
    val blockSig: Long,
)

internal data class HistoryEdgeVisible(
    val olderSentinel: Boolean,
    val newerSentinel: Boolean,
    val newestEdge: Boolean,
    val oldestEdge: Boolean,
)

/**
 * [T-history-prefetch] Older-edge prefetch predicate: the sentinel is
 * composed OR the viewport is within [prefetchItems] lazy indices of the
 * OLDEST content (highest index in this reversed layout), so older pages
 * load before the user reaches the "load earlier" pill.
 */
internal fun olderEdgePrefetchVisible(
    info: androidx.compose.foundation.lazy.LazyListLayoutInfo,
    hasOlder: Boolean,
    prefetchItems: Int = 12,
): Boolean {
    if (!hasOlder) return false
    if (info.visibleItemsInfo.any { it.key == "__load_older__" }) return true
    val oldestVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: return false
    return info.totalItemsCount - oldestVisible <= prefetchItems
}

/**
 * [T-upbtn-landing] Two-step calibrated landing shared by the up-button
 * turn walk and the search-focus jump: position once to materialise the
 * row, read its size, then place the target's TOP edge just under the
 * header. Under reverseLayout the TOP visual inset is the AFTER content
 * padding; the offset math (rowSize − viewportHeight + inset) is the
 * device-calibrated landing from the original implementation.
 */
internal suspend fun landJumpTop(
    listState: androidx.compose.foundation.lazy.LazyListState,
    targetKey: String,
    landIndex: Int,
    trace: suspend (String, Int, Int) -> Unit,
    landReading: (String, Int) -> Unit,
) {
    trace("$targetKey/land", landIndex, 0)
    val rowSize = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.key == targetKey }?.size ?: 0
    val vpH = listState.layoutInfo.viewportSize.height
    val topOffset = rowSize - vpH + listState.layoutInfo.afterContentPadding
    trace("$targetKey/top", landIndex, topOffset)
    landReading(targetKey, topOffset)
}
