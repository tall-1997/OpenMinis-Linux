package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-history-retrieval] The offset contract a caller relies on: reading at
 * `excerpt_start` must return text that BEGINS with the excerpt, and paging
 * must never split a code point.
 */
class HistorySearchToolTest {

    @Test
    fun latinAndCjkQueriesBecomeSearchTerms() {
        assertEquals(listOf("cargo", "build"), HistorySearchTool.keywords("Cargo build"))
        // A CJK run is bigrammed: one character alone matches almost every row.
        val cjk = HistorySearchTool.keywords("\u6570\u636e\u5e93\u8fc1\u79fb")
        assertTrue(cjk.contains("\u6570\u636e"))
        assertTrue(cjk.contains("\u636e\u5e93"))
        assertTrue(HistorySearchTool.keywords("   ").isEmpty())
    }

    @Test
    fun excerptOffsetsAreCoordinatesIntoTheSource() {
        val text = "alpha beta gamma " + "x".repeat(400) + " NEEDLE " + "y".repeat(400) + " omega"
        val (excerpt, start, end) = HistorySearchTool.excerptAt(text, "NEEDLE", width = 120)!!
        assertTrue(excerpt.contains("NEEDLE"))
        // The contract: slicing the source at [start, end) reproduces the excerpt
        // exactly, so a caller can page from the reported offset.
        val cps = text.codePoints().toArray()
        assertEquals(excerpt, String(cps, start, end - start))
    }

    @Test
    fun aHitNearTheEndStillYieldsAFullWidthExcerpt() {
        val text = "HEAD " + "z".repeat(200) + " TAILTOKEN"
        val (excerpt, start, end) = HistorySearchTool.excerptAt(text, "TAILTOKEN", width = 100)!!
        assertEquals(100, end - start)
        assertTrue(excerpt.endsWith("TAILTOKEN"))
        assertTrue(start >= 0)
    }

    @Test
    fun aMissingNeedleYieldsNull() {
        assertNull(HistorySearchTool.excerptAt("abc", "zzz"))
        assertNull(HistorySearchTool.excerptAt("", "a"))
    }

    @Test
    fun pagingNeverSplitsASurrogatePairAndReportsNextOffset() {
        // 5000 code points, but 10000 UTF-16 units: slicing by code point is
        // the only way a page boundary can never fall inside an emoji.
        val text = "\ud83d\ude00".repeat(5000)
        val (first, next, truncated) = HistorySearchTool.page(text, 0, limit = 4000)
        assertTrue(truncated)
        assertEquals(4000, first.codePoints().count().toInt())
        assertEquals(4000, next)
        // Resuming at next_offset must not begin with a low surrogate.
        val (second, secondNext, stillTruncated) =
            HistorySearchTool.page(text, next!!, limit = 4000)
        assertFalse(Character.isLowSurrogate(second[0]))
        // 1000 code points remain, so this page ends the text: no next offset.
        assertEquals(1000, second.codePoints().count().toInt())
        assertFalse(stillTruncated)
        assertNull(secondNext)
    }

    @Test
    fun anOutOfRangeOffsetYieldsEmptyAndNoNext() {
        val (body, next, truncated) = HistorySearchTool.page("abc", offset = 99)
        assertEquals("", body)
        assertNull(next)
        assertFalse(truncated)
    }

    @Test
    fun readingToTheEndClearsNextOffset() {
        val (body, next, truncated) = HistorySearchTool.page("abcdef", offset = 0, limit = 4_000)
        assertEquals("abcdef", body)
        assertNull(next)
        assertFalse(truncated)
    }
}
