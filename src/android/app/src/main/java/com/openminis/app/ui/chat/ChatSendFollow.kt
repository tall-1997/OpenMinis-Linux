package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * [T-android-send-follow-race] Deterministic send-follow pipeline. The old
 * in-handler scrolls ran before sendMessage's state had propagated
 * (permission gate + DB write + the 200ms UI batcher), so on an ended
 * session they landed on the PREVIOUS turn's tail — the viewport stayed
 * pinned there while every new flat item (tool cards, thinking, text)
 * displaced the anchor further, and the old reading guard read that
 * displacement as user intent (see [ScrollAnchor]).
 *
 * Pipeline, driven by [seq] (bumped on every send):
 *   1. `initial` snap — immediate feedback, fires on the seq bump;
 *   2. await THIS user message joining the flat list (keyed on [flatItems],
 *      every publication re-checks until the row exists);
 *   3. `awaited` snap + 100ms settle — catches async self-sizing of the
 *      freshly inserted row.
 *
 * [userSendSnap] is the enqueue-while-streaming fallback (a send whose row
 * arrives while a previous turn is still streaming): fire only when the
 * policy is [pinned], and record the append for the sendGrace window.
 *
 * Companion fix in [ScrollAnchor]: under Pinned the streaming follow no
 * longer treats anchor displacement as reading, so resume/retry (whose
 * placeholder has no user-message row to await) is covered by the 120ms
 * tick instead.
 */
@Composable
internal fun SendFollowEffects(
    seq: Long,
    messagesSize: Int,
    lastMessage: ChatMessage?,
    isPinned: Boolean,
    flatItems: List<FlatChatItem>,
    snap: suspend (source: String) -> Unit,
    onConsumed: () -> Unit,
    onUserAppend: () -> Unit,
) {
    LaunchedEffect(seq) {
        if (seq == 0L) return@LaunchedEffect
        snap("SEND-PATH/initial")
    }
    LaunchedEffect(seq, flatItems) {
        if (seq == 0L) return@LaunchedEffect
        val lastMsg = lastMessage ?: return@LaunchedEffect
        if (lastMsg.role != "user") return@LaunchedEffect
        if (flatItems.none { it.key.contains(lastMsg.id) }) return@LaunchedEffect
        snap("SEND-PATH/awaited-item")
        kotlinx.coroutines.delay(100)
        snap("SEND-PATH/awaited-settle")
        onConsumed()
    }
    LaunchedEffect(messagesSize) {
        val lastMsg = lastMessage ?: return@LaunchedEffect
        if (lastMsg.role != "user") return@LaunchedEffect
        onUserAppend()
        if (isPinned) snap("LE(messages.size)USER-SEND-SNAP")
    }
}
