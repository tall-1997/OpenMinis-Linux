package com.openminis.app.data

import com.openminis.app.data.db.ChatDao
import com.openminis.app.logging.AppLogger

/**
 * [T-m6-compact-marker-staleness] Drop compact markers whose anchor row no
 * longer exists, newest first. Returns how many were removed.
 *
 * Every truncation path (retry / edit / delete-from-here) calls this after the
 * rows are gone. The read side would already fall back to the full history when
 * the anchor can't be resolved, so this is not about the *current* turn — it is
 * about the NEXT compaction: `compactAll` hands the latest marker's summary to
 * the model as `previousSummary`, so a marker standing on deleted rows folds
 * that deleted text into a brand-new summary and puts it back in context. That
 * is the whole bug. Removing the marker reverts the compaction for that range
 * instead, which is what the user's cut implies.
 *
 * The walk stops at the first marker that is still anchored. A marker's folded
 * range ends at its own anchor, so an older marker's anchor is strictly earlier
 * and therefore also alive — pruning it would discard a summary of rows that
 * still exist, often the last summary the session has. [Unjudgeable] legacy
 * rows (sort-order-only) stop the walk for the same reason: without proof of
 * staleness, the row stays.
 *
 * Bounded at [PRUNE_MARKER_LIMIT] steps so a pathological history can't turn
 * this into an unbounded loop. Each truncation only disturbs the tail, so a
 * longer chain drains over successive cuts instead of one long pass.
 *
 * Lives here rather than on ChatRepository so the anchor rule (and its
 * ceiling) is untouched by the truncation paths: [ChatDao] and
 * ChatRepository are both at their line ceilings, and a prune walk is not a
 * repository concern — it is a repair the caller runs after a cut.
 */
internal suspend fun pruneStaleCompactMarkers(dao: ChatDao, sessionId: String): Int {
    if (sessionId.isEmpty()) return 0
    var removed = 0
    var steps = 0
    while (steps++ < PRUNE_MARKER_LIMIT) {
        val marker = try { dao.latestCompactMarker(sessionId) } catch (_: Exception) { null } ?: return removed
        // verdict() wants a synchronous exists-lambda; the DAO row check is
        // suspend, so answer it OUTSIDE the lambda (markerAnchor is the same
        // preference chain verdict itself uses) and hand it a closure.
        val anchor = CompactMarkerValidity.markerAnchor(marker)
        // A throwing read counts as "still here" — a transient DB error must
        // not silently discard a valid summary.
        val alive = anchor != null &&
            (try { dao.messageById(anchor) != null } catch (_: Exception) { true })
        val verdict = CompactMarkerValidity.verdict(marker) { id -> id == anchor && alive }
        if (verdict != CompactMarkerValidity.Verdict.Stale) return removed
        val deleted = try { dao.deleteCompactMarker(marker.id) } catch (_: Exception) { 0 }
        if (deleted <= 0) return removed
        removed++
        AppLogger.info(
            "CompactMarkerPrune",
            "[T-m6-compact-marker-staleness] dropped stale marker " +
                "${marker.id.take(8)} session=${sessionId.take(8)}",
        )
    }
    return removed
}

/** Bound for [pruneStaleCompactMarkers]; see its note. */
private const val PRUNE_MARKER_LIMIT = 8
