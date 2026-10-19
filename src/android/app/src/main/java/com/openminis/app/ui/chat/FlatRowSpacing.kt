package com.openminis.app.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Extracted verbatim from ChatFlatItems.kt when adding the scheduled-task row
// pushed that file past its architecture ceiling: row spacing is a closed rule
// set, and it reads better beside itself than at the end of a 1200-line file.

// ── [P1-3] Row rhythm ───────────────────────────────────────────────────
//
// Vertical spacing between list rows, replacing the flat LazyColumn
// spacedBy(2.dp) plus per-row ad-hoc paddings. flatItems is oldest-first and
// rendered via asReversed() under reverseLayout, so the row at index p-1
// renders directly ABOVE the row at index p — the "top gap" of row p is the
// visual gap between p-1 and p, and it is applied as top padding on row p.
//
// Rhythm (taixu ChatMessageList-derived, adapted to our row kinds):
//  - above a user bubble: 12dp turn boundary (16dp between back-to-back
//    user bubbles — candidate/queued pairs, T-android-candidate-bubble-gap);
//  - above an assistant header: 16dp after a user bubble
//    (T-android-user-assistant-spacing-16), else 12dp;
//  - process row → process row: 2.5dp (dense activity cluster);
//  - process row → reply text: 4dp (process hands off to the answer);
//  - header → first row of its turn: 4dp;
//  - md fragment → md fragment of the SAME message: 2dp (paragraph flow);
//  - everything else: 8dp (default breath).

internal val ChatRowDefaultGap: Dp = 8.dp

internal fun isProcessRow(item: FlatChatItem): Boolean =
    item is FlatChatItem.AssistantThinking ||
        item is FlatChatItem.AssistantToolUse ||
        item is FlatChatItem.AssistantProcessSummary

private fun isTextRow(item: FlatChatItem): Boolean =
    item is FlatChatItem.AssistantMarkdownBlock

internal fun flatItemTopGap(prev: FlatChatItem?, cur: FlatChatItem): Dp = when {
    isUserTurnRow(cur) ->
        if (isUserTurnRow(prev)) 16.dp else 12.dp
    cur is FlatChatItem.AssistantHeader ->
        if (isUserTurnRow(prev)) 16.dp else 12.dp
    prev == null -> 0.dp
    isTextRow(cur) && isTextRow(prev) &&
        (prev as? FlatChatItem.AssistantMarkdownBlock)?.messageId ==
        (cur as? FlatChatItem.AssistantMarkdownBlock)?.messageId -> 2.dp
    isProcessRow(prev) && isProcessRow(cur) -> 2.5.dp
    isProcessRow(prev) && isTextRow(cur) -> 4.dp
    prev is FlatChatItem.AssistantHeader -> 4.dp
    else -> ChatRowDefaultGap
}
