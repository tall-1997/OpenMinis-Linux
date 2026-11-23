package com.openminis.app.ui.chat

/**
 * [T-reading-viewport-yank] Whether this message's markdown keeps its
 * fine-grained (per-fragment) rows.
 *
 * The "last assistant turn" always keeps them so the streaming tail can grow
 * without touching earlier items. Every older turn used to coalesce into one
 * row — and when a NEW turn starts, the previous turn flipped from N
 * fragment-keyed rows to a single coalesced key. Those rows are what the
 * reader is looking at: LazyColumn loses its key anchor, falls back to a
 * numeric index and the viewport jumps a screenful mid-read.
 *
 * Rows that already reached the screen keep their shape for the rest of the
 * session, so the key set only ever grows. Coalescing still applies to history
 * that was never painted in fragment form (cold open, paging), which keeps the
 * row-count budget that motivation existed for.
 */
internal fun keepFragmentsFor(
    isLastAssistantTurn: Boolean,
    messageId: String,
    keepShapeIds: Set<String>,
): Boolean = isLastAssistantTurn || messageId in keepShapeIds

/** [T-reading-viewport-yank] Session-scoped set behind [keepFragmentsFor]. */
@androidx.compose.runtime.Composable
internal fun rememberFragmentShapeSet(sessionId: String): MutableSet<String> =
    androidx.compose.runtime.remember(sessionId) { mutableSetOf<String>() }

/** Record the turn that just painted; keys only ever grow. */
internal fun recordPaintedFragmentShape(keep: MutableSet<String>, messages: List<ChatMessage>) {
    messages.lastOrNull { it.role == "assistant" }?.let { keep.add(it.id) }
}
