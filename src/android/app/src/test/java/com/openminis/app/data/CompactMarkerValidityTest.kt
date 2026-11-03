package com.openminis.app.data

import com.openminis.app.data.db.CompactMarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-m6-compact-marker-staleness] Unit coverage for the one-anchor rule that
 * decides whether a compact marker may keep speaking for a session after a
 * truncation. The bug: retry / edit / delete-from-here all cut the tail, and
 * when the cut landed inside a folded range the marker stayed in
 * `compact_markers`. The read side falls back to the full history (anchor
 * unresolvable), so nothing looked broken — but the NEXT compaction passed that
 * marker's summary to the model as `previousSummary`, folding the deleted text
 * into a brand-new summary and putting it back in context.
 */
class CompactMarkerValidityTest {

    private fun marker(
        lastCompacted: String? = null,
        firstKept: String? = null,
        boundary: String? = null,
        version: Int = 2,
    ) = CompactMarkerEntity(
        id = "m1",
        sessionId = "s1",
        summary = "folded",
        firstKeptSortOrder = 0,
        compactedCount = 3,
        createdAt = 1L,
        firstKeptMessageId = firstKept,
        lastCompactedMessageId = lastCompacted,
        boundaryMessageId = boundary,
        version = version,
    )

    private fun live(vararg ids: String): (String) -> Boolean = { it in ids }

    @Test
    fun `anchor that still exists keeps the marker`() {
        val m = marker(lastCompacted = "row9")
        assertEquals(
            CompactMarkerValidity.Verdict.Anchored,
            CompactMarkerValidity.verdict(m, live("row1", "row9", "row10")),
        )
    }

    @Test
    fun `anchor removed by the cut makes the marker stale`() {
        val m = marker(lastCompacted = "row9")
        assertEquals(
            CompactMarkerValidity.Verdict.Stale,
            CompactMarkerValidity.verdict(m, live("row1", "row2")),
        )
    }

    @Test
    fun `the v2 anchor outranks a surviving v1 boundary id`() {
        // A cut between the two ids: rows after the cut are gone, the folded
        // range is not. Only the v2 anchor decides, because that is the row the
        // summary's own contract ends on.
        val m = marker(lastCompacted = "gone", firstKept = "kept", boundary = "kept")
        assertEquals(
            CompactMarkerValidity.Verdict.Stale,
            CompactMarkerValidity.verdict(m, live("kept")),
        )
    }

    @Test
    fun `missing v2 anchor falls back to the compactBefore boundary`() {
        val m = marker(lastCompacted = null, firstKept = "row5")
        assertEquals(
            CompactMarkerValidity.Verdict.Anchored,
            CompactMarkerValidity.verdict(m, live("row5")),
        )
        assertEquals(
            CompactMarkerValidity.Verdict.Stale,
            CompactMarkerValidity.verdict(m, live("row1")),
        )
    }

    @Test
    fun `legacy boundary id is the last resort`() {
        val m = marker(boundary = "row3")
        assertEquals(
            CompactMarkerValidity.Verdict.Anchored,
            CompactMarkerValidity.verdict(m, live("row3")),
        )
    }

    @Test
    fun `a sort-order-only v1 row is left unjudged`() {
        // Renumbering (restore, fork, truncation) rewrites sort_order, so a
        // numeric anchor proves nothing. Unjudgeable = "keep the row".
        assertEquals(
            CompactMarkerValidity.Verdict.Unjudgeable,
            CompactMarkerValidity.verdict(marker(version = 1), live()),
        )
    }

    @Test
    fun `blank ids are treated as absent, not as an empty anchor`() {
        // A whitespace id would otherwise hit the DB with an id no row can
        // match and read as Stale — discarding a perfectly good summary.
        val m = marker(lastCompacted = "  ", firstKept = "row7")
        assertEquals(
            CompactMarkerValidity.Verdict.Anchored,
            CompactMarkerValidity.verdict(m, live("row7")),
        )
    }
}
