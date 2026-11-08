package com.openminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-entry-sync-flatten] Decision gate + build helper for the
 * session-entry first-frame path, extracted from ChatFlatItems.kt /
 * ChatScreen.kt to keep both files under their architecture-policy
 * ceilings (the ceiling never rises — the code moves instead).
 */

/**
 * Transcript size at or below which a COLD entry may build its flat rows
 * synchronously on the composition thread so the very first frame carries
 * content (the entry lag fix: no empty-list publication, no round-trip
 * through Dispatchers.Default). One past this bound, or any non-cold tick
 * (already published / streaming), must keep the off-main path from T94 —
 * the long-transcript ANR fix stays untouched.
 */
const val ENTRY_SYNC_FLATTEN_MAX_MESSAGES = 200

/**
 * [T-android-entry-sync-flatten] True only for the first flatten tick of a
 * session entry: nothing published yet, no live stream overlay, and the
 * transcript is small enough that an on-main build is provably cheap.
 * Streaming ticks never qualify — the per-token rebuild must never move
 * back onto the composition thread.
 */
internal fun shouldSyncFlattenFirstFrame(
    wasEmpty: Boolean,
    streamEmpty: Boolean,
    msgCount: Int,
): Boolean = wasEmpty && streamEmpty && msgCount in 1..ENTRY_SYNC_FLATTEN_MAX_MESSAGES

/**
 * [T-android-entry-sync-flatten] Builds the rows for one flatten tick.
 * `sync = true` builds directly on the caller's thread (first frame on a
 * cold entry); `sync = false` keeps the T94 off-main path. The snapshot
 * copy in both branches severs the list view so the off-main build cannot
 * comodify with a later frame — see [T-android-flatitems-sublist-cme].
 */
internal suspend fun buildEntryRows(
    sync: Boolean,
    msgs: List<ChatMessage>,
    splitIdx: Int,
    sessionId: String?,
    showCompletedToolCards: Boolean,
    foldAiProcess: Boolean,
    expandedProcessIds: Set<String>,
    activeSubAgentToolIds: Set<String>,
    processCardBoundary: Int,
): List<FlatChatItem> {
    // SNAPSHOT COPY, not msgs.subList(...): a subList is a live VIEW backed
    // by msgs and shares its modCount. Building off-main while msgs is
    // concurrently replaced threw ConcurrentModificationException from a
    // later frame's SubList.equals. Copying severs the view.
    val snapshot = msgs.take(splitIdx)
    val build: () -> List<FlatChatItem> = {
        buildFlatChatItems(
            snapshot,
            sessionId,
            showCompletedToolCards = showCompletedToolCards,
            foldAiProcess = foldAiProcess,
            expandedProcessIds = expandedProcessIds,
            activeSubAgentToolIds = activeSubAgentToolIds,
            processCardBoundary = processCardBoundary,
        )
    }
    return if (sync) build() else withContext(Dispatchers.Default) { build() }
}
