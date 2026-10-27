package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-android-entry-pin-before-compose] The session-entry pin, kept out of
 * ChatScreen so the screen stays under its architecture-policy line ceiling.
 *
 * The tail flag only means the DATA is loaded. The flattened rows are
 * computed off the main thread and published a frame later, and a tail
 * attach can still be appending chunks. `scrollToItem(0)` before those rows
 * exist resolves against the header-only list, and the rows that join
 * afterwards shove the viewport into mid-history — the "进入输出中的长会话
 * 停在历史中间" report. Worse, the item count flips a frame before the
 * layout a scroll resolves against; under reverseLayout the tail rows join
 * at index 0 and push a viewport parked on the retained head into the
 * history. So the pin waits until [SessionEntryScroll.entryPinReady]: a tail
 * row composed, the attach settled, and the completed layout showing the
 * newest row (`firstVisibleItemIndex`, written by the layout pass).
 *
 * Both waits are bounded: a tail that never arrives still pins.
 */
internal suspend fun pinSessionEntryToNewest(
    viewModel: ChatViewModel,
    // Closures, not values: both are Compose delegated state, and reading
    // them at the call site would snapshot the moment the pin starts — the
    // whole point is to re-read them inside the snapshotFlow below.
    tailItemCount: () -> Int,
    tailLoading: () -> Boolean,
    listState: LazyListState,
    scrollToItem: suspend (source: String, idx: Int, off: Int) -> Unit,
) {
    withTimeoutOrNull(
        SessionEntryScroll.entryPinWaitBoundMs(
            tailReady = viewModel.sessionTailReady.value,
            isStreaming = viewModel.isStreaming.value,
            hasNewerMessages = viewModel.hasNewerMessages.value,
        ),
    ) { viewModel.sessionTailReady.first { it } }
    withTimeoutOrNull(SessionEntryScroll.TAIL_ATTACH_WAIT_BOUND_MS) {
        snapshotFlow {
            SessionEntryScroll.entryPinReady(
                tailItemCount = tailItemCount(),
                tailLoading = tailLoading(),
                listItemCount = listState.layoutInfo.totalItemsCount,
                firstVisibleIndex = listState.firstVisibleItemIndex,
            )
        }.first { it }
    }
    withFrameNanos { }
    scrollToItem("SESSION-REENTRY/tail", 0, 0)
    delay(100)
    scrollToItem("SESSION-REENTRY/tail-settle", 0, 0)
}
