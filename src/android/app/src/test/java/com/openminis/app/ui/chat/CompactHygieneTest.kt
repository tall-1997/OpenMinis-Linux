package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hygiene tests for the compact pipeline fixes proven against the production
 * DB (545MB, real markers):
 *  - [T-compact-degenerate] runCompactPasses refuses a stage whose output is a
 *    byte-identical echo of the previous summary; later stages still run.
 *  - [T-compact-pool-dedup] appendSummaryChunk skips a chunk whose normalized
 *    text already sits in the pool; the pool still caps at MAX.
 *  - [T-compact-envelope-overlap] compactExcerptOverlapsSummary flags
 *    containment in both directions and high bigram overlap, passes disjoint
 *    and below-threshold excerpts; selectSummaryChunks dedups its output for
 *    legacy pools that already carry duplicate entries.
 */
class CompactHygieneTest {

    private fun msg(i: Int) = LLMMessage(role = LLMMessage.Role.USER, content = "m$i")
    private fun poolCount(json: String?): Int = JSONArray(json ?: "[]").length()

    // ---- [T-compact-degenerate] ----

    @Test
    fun echoedSummaryIsRejectedAsStageFailure() = runBlocking {
        val prev = "previous summary content ".repeat(40).trim() // trimmed baseline; >= MIN_ACCEPTABLE chars
        val e = runCatching {
            runCompactPasses(listOf(msg(1)), prev, 1) { _, _, _, _ -> prev }
        }.exceptionOrNull()
        assertTrue("expected echo failure, got: $e", e?.message?.contains("echoed") == true)
    }

    @Test
    fun echoFallsThroughToFallbackStage() = runBlocking {
        val good = "notification pipeline 压缩机制 recap " + "x".repeat(300)
        val outcome = runCompactPasses(listOf(msg(1)), "PREVIOUS SUMMARY", 2) { _, _, stage, _ ->
            if (stage == 0) "PREVIOUS SUMMARY" else good
        }
        assertEquals(good, outcome.summary)
        assertTrue(outcome.succeeded)
    }

    @Test
    fun distinctSummaryStillAccepted() = runBlocking {
        val good = "goal ledger and receipt gate recap " + "y".repeat(300)
        val outcome = runCompactPasses(listOf(msg(1)), null, 1) { _, _, _, _ -> good }
        assertEquals(good, outcome.summary)
    }

    // ---- [T-compact-pool-dedup] ----

    @Test
    fun appendSkipsByteIdenticalChunk() {
        val text = "SAME TEXT ".repeat(50)
        val pool = appendSummaryChunk(null, text)
        assertEquals(1, poolCount(pool))
        assertEquals(1, poolCount(appendSummaryChunk(pool, text)))
    }

    @Test
    fun appendSkipsWhitespaceVariantOfExistingChunk() {
        val text = "SAME TEXT ".repeat(50)
        val pool = appendSummaryChunk(null, text)
        assertEquals(1, poolCount(appendSummaryChunk(pool, " SAME  TEXT ".repeat(50) + " ")))
    }

    @Test
    fun appendKeepsDistinctChunks() {
        var pool: String? = null
        repeat(3) { pool = appendSummaryChunk(pool, "chunk number $it " + "y".repeat(200)) }
        assertEquals(3, poolCount(pool))
    }

    @Test
    fun poolStillCapsAtMax() {
        var pool: String? = null
        repeat(12) { pool = appendSummaryChunk(pool, "distinct chunk $it " + "z".repeat(200)) }
        assertEquals(SUMMARY_CHUNK_POOL_MAX, poolCount(pool))
    }

    // ---- [T-compact-envelope-overlap] ----

    @Test
    fun overlapDetectsContainmentBothWays() {
        val summary = "A".repeat(80) + "B".repeat(80)
        assertTrue(compactExcerptOverlapsSummary(summary, summary.substring(0, 100)))
        assertTrue(compactExcerptOverlapsSummary(summary.substring(0, 100), summary))
        assertTrue(compactExcerptOverlapsSummary(summary, summary))
    }

    @Test
    fun disjointExcerptPassesTheFilter() {
        val summary = "A".repeat(80) + "B".repeat(80)
        assertFalse(compactExcerptOverlapsSummary(summary, "C".repeat(200)))
    }

    @Test
    fun belowThresholdOverlapPassesTheFilter() {
        val summary = "A".repeat(80) + "B".repeat(80)
        val half = summary.substring(0, 80) + "C".repeat(80)
        assertFalse(compactExcerptOverlapsSummary(summary, half))
    }

    @Test
    fun blankInputsNeverCountAsOverlap() {
        assertFalse(compactExcerptOverlapsSummary("", "x"))
        assertFalse(compactExcerptOverlapsSummary("x", "  "))
    }

    @Test
    fun selectDedupsLegacyPoolDuplicates() {
        // Legacy pools (pre-dedup) stored the same text twice; the SELECT side
        // must still return distinct texts.
        val dup = "pipeline alpha recap " + "p".repeat(200)
        val unique = "pipeline beta ledger " + "q".repeat(200)
        val arr = JSONArray()
            .put(JSONObject().put("t", dup).put("a", 1L))
            .put(JSONObject().put("t", dup).put("a", 2L))
            .put(JSONObject().put("t", unique).put("a", 3L))
        val picked = selectSummaryChunks(arr.toString(), "pipeline")
        assertEquals(2, picked.size)
        assertTrue(picked.contains(unique))
    }

    // ---- pool format compat ----

    @Test
    fun appendPreservesLegacyRecordShape() {
        val legacy = JSONArray()
            .put(JSONObject().put("t", "legacy kept text " + "r".repeat(200)).put("a", 99L))
            .toString()
        val updated = appendSummaryChunk(legacy, "new distinct chunk " + "s".repeat(200))
        assertEquals(2, poolCount(updated))
        assertEquals("legacy kept text " + "r".repeat(200), JSONArray(updated).getJSONObject(0).getString("t"))
    }
}
