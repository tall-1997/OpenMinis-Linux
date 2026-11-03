package com.openminis.app.data

import com.openminis.app.data.db.CompactMarkerEntity

/**
 * [T-m6-compact-marker-staleness] Is a compact marker still allowed to speak
 * for this session?
 *
 * A marker is a *promise*: "everything up to and including [anchor] has been
 * folded into [summary]; only what follows needs to be replayed verbatim."
 * Truncation breaks that promise. Retry / edit / delete-from-here all cut the
 * tail off a session, and the cut can land **inside a folded range**:
 *
 *  - edit message #3 of a range folded up to #9 → rows 3..N are archived and
 *    deleted, so the summary still describes text the user just replaced;
 *  - delete-from-here at #4 of the same range → same, for content the user
 *    just asked to forget.
 *
 * Before this check the marker survived both cases. Nothing reads it
 * immediately (the anchor is gone, so [com.openminis.app.ui.chat.ChatViewModel]
 * falls back to the full history — the same safety net iOS has), but the row
 * stays in `compact_markers`, and the *next* compaction picks it up as
 * `previousSummary`: the deleted text is folded back into a fresh summary and
 * re-enters model context on the following turn. Deleting a message and still
 * finding its content in the model's context three turns later is exactly the
 * leak this class closes.
 *
 * The check is deliberately one anchor deep. A marker's folded range runs from
 * the session start (or the previous marker's anchor + 1) to its own anchor, so
 * if the anchor row still exists the whole range predates the cut and the
 * summary is still accurate — a cut *after* the anchor leaves the marker
 * perfectly valid, and pruning it there would throw away a good summary. Only
 * "the anchor itself is gone" proves the folded range was disturbed.
 */
object CompactMarkerValidity {

    enum class Verdict {
        /** The marker's authoritative anchor is still a live row. Summary is usable. */
        Anchored,

        /** The anchor row no longer exists — the marker describes deleted content. */
        Stale,

        /**
         * No id anchor at all (a v1 row that only ever carried sort_order
         * numbers). Sort orders are renumbered by truncation and by session
         * forks, so they prove nothing here; leave the row to the legacy
         * resolution path instead of guessing.
         */
        Unjudgeable,
    }

    /**
     * The one anchor this maker's [verdict] would interrogate, preferring the
     * same fallback chain as the read side. Extracted so callers that hold a
     * suspend DAO can answer the row-existence question *before* entering
     * [verdict]'s synchronous lambda — production cannot call `messageById`
     * (a suspend function) inside a plain `(String) -> Boolean`.
     */
    fun markerAnchor(marker: CompactMarkerEntity): String? = marker.lastCompactedMessageId?.takeIf { it.isNotBlank() }
        ?: marker.firstKeptMessageId?.takeIf { it.isNotBlank() }
        ?: marker.boundaryMessageId?.takeIf { it.isNotBlank() }

    /**
     * [rowExists] is injected so this stays a pure function — production
     * passes a single `EXISTS` query per marker, tests pass a set lookup.
     */
    fun verdict(marker: CompactMarkerEntity, rowExists: (String) -> Boolean): Verdict {
        // Preference order mirrors the read side: v2's lastCompactedMessageId is
        // authoritative, then the compactBefore boundary, then the legacy
        // boundary id. The first one present decides.
        val anchor = markerAnchor(marker) ?: return Verdict.Unjudgeable
        return if (rowExists(anchor)) Verdict.Anchored else Verdict.Stale
    }
}
