package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the shared dual-limit truncator — port of pi
 * `durable/src/truncate.ts` (truncateHead), pi `coding-agent` truncate.ts
 * (truncateTail, sliceHead/sliceTail) and pi `mcp/src/output-limit.ts`
 * (truncateMiddle). Pins: line limit vs byte limit precedence, never-half-line,
 * first-line-over-limit both branches, UTF-8 boundary safety for CJK/emoji.
 */
class ToolOutputTruncatorTest {

    // ── truncateHead ──────────────────────────────────────────────────────────

    @Test
    fun `under both limits content passes through untouched`() {
        val content = "alpha\nbeta\ngamma\n"
        val r = ToolOutputTruncator.truncateHead(content)
        assertFalse(r.truncated)
        assertNull(r.truncatedBy)
        assertEquals(content, r.content)
        assertEquals(3, r.totalLines)
        assertFalse(r.lastLinePartial)
        assertFalse(r.firstLineExceedsLimit)
    }

    @Test
    fun `line limit hit first keeps whole lines only`() {
        val lines = (1..2500).map { "line-$it" }
        val content = lines.joinToString("\n")
        val r = ToolOutputTruncator.truncateHead(content)
        assertTrue(r.truncated)
        assertEquals(ToolOutputTruncator.TruncatedBy.LINES, r.truncatedBy)
        assertEquals(2000, r.outputLines)
        assertEquals(2500, r.totalLines)
        // No half lines: every output line is an original one.
        assertEquals(lines.take(2000), r.content.split("\n"))
    }

    @Test
    fun `byte limit hit first stops before the offending line`() {
        val lines = (1..100).map { "x".repeat(1024) }
        val content = lines.joinToString("\n")
        val r = ToolOutputTruncator.truncateHead(content)
        assertTrue(r.truncated)
        assertEquals(ToolOutputTruncator.TruncatedBy.BYTES, r.truncatedBy)
        // 49 lines + separators fit 50KB; the 50th would exceed it.
        assertEquals(49, r.outputLines)
        assertTrue("output must stay within the byte budget", r.outputBytes <= 50 * 1024)
        assertEquals(lines.take(49), r.content.split("\n"))
    }

    @Test
    fun `first line over byte limit yields empty content with the flag`() {
        val content = "y".repeat(60_000)
        val r = ToolOutputTruncator.truncateHead(content)
        assertTrue(r.truncated)
        assertEquals(ToolOutputTruncator.TruncatedBy.BYTES, r.truncatedBy)
        assertEquals("", r.content)
        assertEquals(0, r.outputLines)
        assertTrue(r.firstLineExceedsLimit)
    }

    @Test
    fun `first line within limit but second over keeps only the first`() {
        val content = "fit\n" + "z".repeat(60_000)
        val r = ToolOutputTruncator.truncateHead(content, maxLines = 2000, maxBytes = 1024)
        assertTrue(r.truncated)
        assertEquals("fit", r.content)
        assertEquals(1, r.outputLines)
        assertFalse(r.firstLineExceedsLimit)
        assertEquals(ToolOutputTruncator.TruncatedBy.BYTES, r.truncatedBy)
    }

    @Test
    fun `trailing newline byte overage is reported as BYTES with all lines kept`() {
        // pi durable line 156: no byte break + all lines kept → the overage was
        // only the trailing newline, so the result is BYTES, not LINES.
        val content = ("12345\n").repeat(200) // 200 lines of 5 chars + trailing \n
        val maxBytes = ToolOutputTruncator.utf8ByteLength(content) - 1
        val r = ToolOutputTruncator.truncateHead(content, maxBytes = maxBytes)
        assertTrue(r.truncated)
        assertEquals(ToolOutputTruncator.TruncatedBy.BYTES, r.truncatedBy)
        assertEquals(200, r.outputLines)
        assertEquals(content.removeSuffix("\n"), r.content)
    }

    // ── truncateTail ──────────────────────────────────────────────────────────

    @Test
    fun `tail keeps the last lines when the line limit wins`() {
        val lines = (1..3000).map { "row-$it" }
        val content = lines.joinToString("\n")
        val r = ToolOutputTruncator.truncateTail(content)
        assertTrue(r.truncated)
        assertEquals(ToolOutputTruncator.TruncatedBy.LINES, r.truncatedBy)
        assertEquals(2000, r.outputLines)
        assertEquals(lines.takeLast(2000), r.content.split("\n"))
        assertFalse(r.lastLinePartial)
    }

    @Test
    fun `tail keeps the last lines that fit the byte budget`() {
        val lines = (1..100).map { "y".repeat(1024) }
        val content = lines.joinToString("\n")
        val r = ToolOutputTruncator.truncateTail(content)
        assertTrue(r.truncated)
        assertEquals(ToolOutputTruncator.TruncatedBy.BYTES, r.truncatedBy)
        assertTrue(r.outputBytes <= 50 * 1024)
        assertEquals(lines.takeLast(49), r.content.split("\n"))
        assertFalse(r.lastLinePartial)
    }

    @Test
    fun `tail single huge line is cut from the end at a UTF-8 boundary`() {
        // CJK chars are 3 UTF-8 bytes each; 1024 is not a multiple of 3, so the
        // boundary walk must land on a character start.
        val content = "\u4F60".repeat(20_000) // 60000 bytes, one line
        val r = ToolOutputTruncator.truncateTail(content, maxBytes = 1024)
        assertTrue(r.truncated)
        assertEquals(ToolOutputTruncator.TruncatedBy.BYTES, r.truncatedBy)
        assertTrue(r.lastLinePartial)
        // pi counts kept entries: one (partial) line, whose content is 341 CJK chars.
        assertEquals(1, r.outputLines)
        assertEquals("\u4F60".repeat(341), r.content)
        assertFalse("no replacement chars may appear", r.content.contains("\uFFFD"))
    }

    @Test
    fun `tail never returns a half line unless the partial edge case applies`() {
        val lines = (1..500).map { "z".repeat(2000) } // 500 lines, ~1MB total
        val content = lines.joinToString("\n")
        val r = ToolOutputTruncator.truncateTail(content)
        assertTrue(r.truncated)
        assertFalse(r.lastLinePartial)
        val outLines = r.content.split("\n")
        assertTrue(outLines.all { it in lines.toSet() })
        assertEquals(lines.takeLast(outLines.size), outLines)
    }

    // ── sliceHead / sliceTail (UTF-16 unit slicing, surrogate safe) ──────────

    @Test
    fun `slice head and tail never split a surrogate pair`() {
        val text = "a\uD83D\uDE00b" // a + GRINNING FACE (pair) + b
        assertEquals("a", ToolOutputTruncator.sliceHead(text, 2))
        assertEquals("a\uD83D\uDE00", ToolOutputTruncator.sliceHead(text, 3))
        assertEquals("a", ToolOutputTruncator.sliceHead(text, 1))
        assertEquals("", ToolOutputTruncator.sliceHead(text, 0))
        assertEquals("a\uD83D\uDE00b", ToolOutputTruncator.sliceHead(text, 99))
        // The orphaned low surrogate is dropped whole: the kept window may not
        // begin inside a pair (pi sliceTail drops it via the start+1 shift).
        assertEquals("b", ToolOutputTruncator.sliceTail(text, 2))
        assertEquals("\uD83D\uDE00b", ToolOutputTruncator.sliceTail(text, 3))
        assertEquals("", ToolOutputTruncator.sliceTail(text, 0))
        assertEquals("a\uD83D\uDE00b", ToolOutputTruncator.sliceTail(text, 99))
    }

    @Test
    fun `slice tail bytes walks to a character boundary`() {
        val text = "\u4F60".repeat(10) // 30 bytes
        assertEquals("\u4F60".repeat(3), ToolOutputTruncator.sliceTailBytes(text, 9))
        // 8 bytes would start inside a char → walks forward to 6 bytes.
        assertEquals("\u4F60".repeat(2), ToolOutputTruncator.sliceTailBytes(text, 8))
        assertEquals("", ToolOutputTruncator.sliceTailBytes(text, 0))
        assertEquals(text, ToolOutputTruncator.sliceTailBytes(text, 30))
    }

    // ── truncateMiddle (MCP output limit core) ────────────────────────────────

    @Test
    fun `middle cut keeps head and tail with an exact removed-char marker`() {
        val text = "A".repeat(40_000) // 40000 ASCII bytes
        val r = ToolOutputTruncator.truncateMiddle(text, 20 * 1024)!!
        assertEquals(40_000, r.totalBytes)
        assertEquals(1, r.totalLines)
        val expected = "A".repeat(10_240) + "…19520 chars truncated…" + "A".repeat(10_240)
        assertEquals(expected, r.content)
    }

    @Test
    fun `middle cut on CJK text never splits a character`() {
        val text = "\u4F60".repeat(10_000) // 30000 bytes
        val r = ToolOutputTruncator.truncateMiddle(text, 20 * 1024)!!
        val expected = "\u4F60".repeat(3413) + "…3174 chars truncated…" + "\u4F60".repeat(3413)
        assertEquals(expected, r.content)
        assertFalse(r.content.contains("\uFFFD"))
    }

    @Test
    fun `middle cut on emoji text never splits a surrogate pair`() {
        val text = "\uD83D\uDE00".repeat(6_000) // 24000 bytes (4 bytes per char)
        val r = ToolOutputTruncator.truncateMiddle(text, 20 * 1024)!!
        val expected = "\uD83D\uDE00".repeat(2560) + "…880 chars truncated…" + "\uD83D\uDE00".repeat(2560)
        assertEquals(expected, r.content)
    }

    @Test
    fun `middle cut counts lines like the line counter`() {
        val text = ("row\n").repeat(100) + "tail".repeat(1)
        val r = ToolOutputTruncator.truncateMiddle(text, 128)!!
        // 100 "row" lines + the final "tail" line (no trailing newline).
        assertEquals(101, r.totalLines)
    }

    @Test
    fun `middle cut returns null when the text fits`() {
        assertNull(ToolOutputTruncator.truncateMiddle("small", 20 * 1024))
        assertNull(ToolOutputTruncator.truncateMiddle("", 20 * 1024))
    }

    // ── shared primitives ─────────────────────────────────────────────────────

    @Test
    fun `utf8 byte length counts bytes not chars`() {
        assertEquals(9, ToolOutputTruncator.utf8ByteLength("\u4F60\u4F60\u4F60"))
        assertEquals(4, ToolOutputTruncator.utf8ByteLength("\uD83D\uDE00"))
        assertEquals(5, ToolOutputTruncator.utf8ByteLength("hello"))
    }

    @Test
    fun `total lines ignores a trailing newline and counts empty text as zero`() {
        assertEquals(0, ToolOutputTruncator.totalLines(""))
        assertEquals(1, ToolOutputTruncator.totalLines("one"))
        assertEquals(2, ToolOutputTruncator.totalLines("one\ntwo"))
        assertEquals(2, ToolOutputTruncator.totalLines("one\ntwo\n"))
    }
}
