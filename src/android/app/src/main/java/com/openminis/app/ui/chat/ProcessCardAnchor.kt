package com.openminis.app.ui.chat

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [T-android-process-card-anchor] How long a manual expand/collapse keeps
 * re-pinning the tapped process card's top edge.
 *
 * The card animates its height (ProcessSummaryBar's animateContentSize
 * spring). Under reverseLayout the list keeps its own anchor while the tapped
 * card grows/shrinks, so the viewport appeared to jump ("折叠后会跳转到上方").
 * The toggle records the card's window Y, then scrolls by (nowY - beforeY)
 * every frame so the card stays exactly where the user tapped it. 900ms is
 * comfortably longer than the spring, and the loop exits early after four
 * stable frames, so a normal toggle costs a handful of frames.
 *
 * Extracted from ChatScreen.kt to keep that file under its architecture
 * ceiling; the state is still owned by the screen's remember scope.
 */
private const val PROCESS_CARD_ANCHOR_WINDOW_NS = 900_000_000L

internal class ProcessCardAnchorState(
    private val scope: CoroutineScope,
    private val scrollController: ChatScrollController,
) {
    private val topY = mutableMapOf<String, Float>()
    private var job: Job? = null

    /** Remember the row's current window Y; called from its onGloballyPositioned. */
    fun record(key: String, y: Float) {
        topY[key] = y
    }

    /** A row modifier that records [key]'s position on every layout pass. */
    fun modifierFor(key: String?): Modifier =
        if (key == null || key.isEmpty()) {
            Modifier
        } else {
            Modifier.onGloballyPositioned { coords -> record(key, coords.positionInWindow().y) }
        }

    /**
     * Flip the card's expansion with [applyToggle], then keep its top edge at
     * the pre-toggle Y while the height animation runs.
     */
    fun toggle(key: String, applyToggle: () -> Unit) {
        val anchorTopBefore = topY[key]
        applyToggle()
        if (anchorTopBefore == null) return
        job?.cancel()
        job = scope.launch {
            val startedNs = System.nanoTime()
            var stableFrames = 0
            while (System.nanoTime() - startedNs < PROCESS_CARD_ANCHOR_WINDOW_NS) {
                withFrameNanos { }
                val nowY = topY[key] ?: break
                val dy = nowY - anchorTopBefore
                if (kotlin.math.abs(dy) >= 0.5f) {
                    // reverseLayout: scrollBy(+) moves content up, i.e.
                    // decreases the row's window Y.
                    scrollController.scrollBy("PROCESS-CARD/keep-anchor", dy)
                    stableFrames = 0
                } else if (++stableFrames >= 4) {
                    break
                }
            }
        }
    }
}

/**
 * [T-android-process-card-anchor] New expanded-card set after a manual tap.
 * Collapsing marks the card as user-collapsed (suppresses auto-expand for THIS
 * card only); opening marks it user-reopened (the 500ms settle never closes
 * it). Pure, so the screen's onToggle stays a one-liner.
 */
internal fun processCardToggleTargets(
    id: String,
    expandedProcessIds: Set<String>,
    userCollapsedCards: MutableSet<String>,
    manualReopenedCards: MutableSet<String>,
): Set<String> = if (id in expandedProcessIds) {
    userCollapsedCards += id
    expandedProcessIds - id
} else {
    userCollapsedCards -= id
    manualReopenedCards += id
    expandedProcessIds + id
}
