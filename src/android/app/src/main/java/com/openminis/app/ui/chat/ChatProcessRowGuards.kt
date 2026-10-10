package com.openminis.app.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * [T-process-card-row-guards] Stateful row-level guards for the process
 * card, split out of ChatProcessRunCardUI.kt to respect the 400-line
 * ratchet: the live duration ticker for the folded bar, the collapse
 * scroll-compensation guard, and the thinking char-count formatter.
 */

/**
 * [T-process-card-live-duration] The folded bar must show elapsed time
 * WHILE the run is live, not only after it ends: totalMs only sums
 * COMPLETED blocks (durationMs is written at completion), so a run whose
 * first block is still streaming reads as "no duration". Adds the
 * wall-clock elapsed of the in-flight block (startTimeMs → now), ticking
 * once per second while the card is composed and running. Monotonic:
 * when the block completes its durationMs lands in totalMs and the
 * ticker moves to the next in-flight block.
 */
@Composable
internal fun rememberLiveProcessDurationMs(item: FlatChatItem.ProcessRunCard): Long {
    val liveStartMs = item.blocks.lastOrNull {
        it.startTimeMs > 0L && it.toolStatus in IN_FLIGHT_PROCESS_TOOL_STATUSES
    }?.startTimeMs ?: 0L
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item.isRunning, liveStartMs) {
        if (item.isRunning && liveStartMs > 0L) {
            while (true) {
                delay(1_000L)
                nowMs = System.currentTimeMillis()
            }
        }
    }
    return if (item.isRunning && liveStartMs > 0L) {
        item.totalMs + (nowMs - liveStartMs)
    } else {
        item.totalMs
    }
}

/** [T-thinking-char-count] 折叠行也读得出输出量（紧凑 8.4k/1.2M）。 */
@Composable
internal fun ThinkingCharCountBadge(chars: Int) {
    Text(formatThinkingCharCount(chars), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF8E8E93).copy(alpha = 0.7f), maxLines = 1)
    Spacer(modifier = Modifier.width(4.dp))
}

/**
 * [T-thinking-collapse-jump] Collapsing a live thinking row shrinks the
 * card item's height by thousands of pixels; in the reverseLayout list
 * the scroll anchor's offset clamps to the item's new top and the
 * viewport jumps to the card start. The guard records the row header's
 * root-Y when a collapse is armed, then two frames later (post-remeasure)
 * scrolls the list by the delta so the header lands back where the user
 * tapped. Expand needs no guard: growth never clamps the anchor.
 */
internal class ThinkingCollapseGuard internal constructor(
    internal val onHeaderPositioned: (Float) -> Unit,
    internal val armCollapse: () -> Unit,
)

@Composable
internal fun rememberThinkingCollapseGuard(listState: LazyListState): ThinkingCollapseGuard {
    var headerY by remember { mutableStateOf(0f) }
    var anchorY by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(anchorY) {
        val anchor = anchorY ?: return@LaunchedEffect
        // Wait for the post-collapse remeasure to LAND: a 2000px row
        // collapse can take more than two frames to settle (item
        // remeasure -> viewport refill -> relayout). Poll until the
        // header actually moves off the anchor, or give up after 6
        // frames — a collapse that moves nothing needs no compensation.
        var frames = 0
        while (headerY == anchor && frames < 6) {
            withFrameNanos { }
            frames++
        }
        val dy = anchor - headerY
        anchorY = null
        if (dy != 0f) listState.scrollBy(dy)
    }
    return remember {
        ThinkingCollapseGuard(
            onHeaderPositioned = { headerY = it },
            armCollapse = { anchorY = headerY },
        )
    }
}

/**
 * [T-thinking-char-count] The thinking row carries how much output it
 * holds so a collapsed row still reads as "3 chars vs 30k chars".
 * Compact: 999 → "999", 8400 → "8.4k", 10000 → "10k", 1200000 → "1.2M".
 */
internal fun formatThinkingCharCount(chars: Int): String = when {
    chars < 1_000 -> chars.toString()
    chars < 10_000 -> String.format(java.util.Locale.US, "%.1fk", chars / 1_000.0)
    chars < 1_000_000 -> "${chars / 1_000}k"
    else -> String.format(java.util.Locale.US, "%.1fM", chars / 1_000_000.0)
}
