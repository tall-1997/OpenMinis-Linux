package com.openminis.app.ui.chat

import com.openminis.app.data.pruneStaleCompactMarkers
import com.openminis.app.logging.AppLogger

/**
 * [T-m6-compact-marker-staleness] Re-point the in-memory compact state after a
 * truncation (retry / edit / delete-from-here).
 *
 * Three fields describe "what the model currently sees of the folded past", and
 * all three were left untouched by every truncation path:
 *
 *  - `_cachedLatestMarker` — the row `effectiveAgentHistory` slices on. After a
 *    cut that removed the anchor it kept pointing at a deleted row, so the
 *    divider UI described a boundary that no longer existed and every turn
 *    re-resolved a doomed anchor.
 *  - `_compactSummary` — the same marker's summary text. It is what the NEXT
 *    `compactAll` hands the model as `previousSummary`, so a stale one folds
 *    deleted content into a fresh summary and returns it to context (the
 *    actual leak this fixes; see [com.openminis.app.data.CompactMarkerValidity]).
 *  - `_compactMarkerDetached` — "the anchor fell out of the bounded history
 *    window", probed once per session load. The cut moves the window and
 *    changes which rows exist, so a verdict measured before it is meaningless.
 *
 * Deliberately silent about `_messages`: the caller already truncated the list
 * and rebuilt `agentHistory`; a divider repaint rides the next session reload,
 * and forcing one here would race the send that follows an edit.
 */
internal suspend fun ChatViewModel.reconcileCompactMarkersAfterTruncation(sessionId: String) {
    if (sessionId.isEmpty()) return
    val removed = runCatching {
        pruneStaleCompactMarkers(chatRepository.dao, sessionId)
    }.getOrElse { 0 }
    val latest = runCatching { chatRepository.dao.latestCompactMarker(sessionId) }.getOrNull()
    _cachedLatestMarker = latest
    _compactSummary.value = latest?.summary
    _compactMarkerDetached = false
    if (removed > 0) {
        AppLogger.info(
            ChatViewModel.TAG_STREAM,
            "🧹 truncation dropped $removed stale compact marker(s) — " +
                "summary reverted, remaining=${latest?.id?.take(8) ?: "none"}",
        )
    }
}
