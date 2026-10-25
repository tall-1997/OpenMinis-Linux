package com.openminis.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BoundedTextTest {

    @Test
    fun `icuWindow does not expose more than the cap`() {
        val huge = "x".repeat(BoundedText.MAX_ICU_INPUT_CHARS + 50_000)
        val window = BoundedText.icuWindow(huge)
        assertEquals(BoundedText.MAX_ICU_INPUT_CHARS, window.length)
        assertEquals('x', window[0])
    }

    @Test
    fun `markdownParseInput truncates at the parse cap`() {
        val huge = "hello\n" + "y".repeat(100_000)
        val parsed = BoundedText.markdownParseInput(huge)
        assertEquals(BoundedText.MAX_MARKDOWN_PARSE_CHARS, parsed.length)
        assertTrue(parsed.startsWith("hello\n"))
    }

    @Test
    fun `scanWindow keeps head and tail of a huge buffer`() {
        val head = "```kotlin\n"
        val tail = "\n| --- | --- |\n"
        val marker = "UNIQUE_MID_MARKER"
        val text = head + "Z".repeat(40_000) + marker + "Z".repeat(40_000) + tail
        val window = BoundedText.scanWindow(text)
        assertEquals(BoundedText.MAX_CONTENT_DIAG_SCAN_CHARS, window.length)
        assertTrue(window.startsWith("```"))
        assertTrue(window.contains("| --- | --- |"))
        assertFalse(window.contains(marker))
    }

    @Test
    fun `clampLogLine keeps a short line intact`() {
        assertEquals("ok", BoundedText.clampLogLine("ok"))
    }

    @Test
    fun `clampLogLine truncates a megabyte dump`() {
        val line = "A".repeat(20_000)
        val clamped = BoundedText.clampLogLine(line)
        assertTrue(clamped.length < line.length)
        assertTrue(clamped.startsWith("A".repeat(32)))
        assertTrue(clamped.contains("truncated"))
        assertTrue(clamped.length <= BoundedText.MAX_LOG_LINE_CHARS + 40)
    }

    @Test
    fun `canAppendLog refuses a file already at the cap`() {
        assertFalse(BoundedText.canAppendLog(BoundedText.MAX_LOG_FILE_BYTES, 10))
        assertTrue(BoundedText.canAppendLog(0, 10))
        assertFalse(
            BoundedText.canAppendLog(
                BoundedText.MAX_LOG_FILE_BYTES - 4,
                16,
            ),
        )
    }

    @Test
    fun `priority logs still fit in the overflow window`() {
        assertTrue(
            BoundedText.canAppendPriorityLog(BoundedText.MAX_LOG_FILE_BYTES, 10),
        )
        assertFalse(
            BoundedText.canAppendPriorityLog(
                BoundedText.MAX_LOG_FILE_BYTES + BoundedText.MAX_LOG_OVERFLOW_BYTES,
                10,
            ),
        )
        assertTrue(BoundedText.isPriorityLogLine("[12:00:00] [ERROR] [X] boom"))
        assertTrue(BoundedText.isPriorityLogLine("[12:00:00] [WARN] [X] slow"))
        assertFalse(BoundedText.isPriorityLogLine("[12:00:00] [INFO] [X] ok"))
    }

    @Test
    fun `sse payload is truncated`() {
        val huge = "S".repeat(2_000)
        val clipped = BoundedText.clampSsePayload(huge)
        assertEquals(BoundedText.MAX_SSE_LOG_CHARS + 1, clipped.length)
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun `tool input delta logs on a stride not every token`() {
        assertTrue(BoundedText.shouldLogLengthStride(10))
        assertTrue(BoundedText.shouldLogLengthStride(64))
        assertFalse(BoundedText.shouldLogLengthStride(100))
        assertTrue(BoundedText.shouldLogLengthStride(2_048))
        assertFalse(BoundedText.shouldLogLengthStride(2_100))
    }

    @Test
    fun `tool input snapshot commits first growth then 2KB buckets`() {
        assertTrue(BoundedText.shouldCommitLengthStride(1, 0))
        assertTrue(BoundedText.shouldCommitLengthStride(20, 1))
        assertTrue(BoundedText.shouldCommitLengthStride(64, 20))
        assertFalse(BoundedText.shouldCommitLengthStride(80, 64))
        assertFalse(BoundedText.shouldCommitLengthStride(2_047, 64))
        assertTrue(BoundedText.shouldCommitLengthStride(2_048, 64))
        assertFalse(BoundedText.shouldCommitLengthStride(2_048, 2_048))
        assertFalse(BoundedText.shouldCommitLengthStride(3_000, 2_048))
        assertTrue(BoundedText.shouldCommitLengthStride(4_096, 2_048))
        assertFalse(BoundedText.shouldCommitLengthStride(50, 80))
    }

    @Test
    fun `splitForCompose prefers newline breaks under the cap`() {
        val a = "a".repeat(5_000) + "\n"
        val b = "b".repeat(5_000)
        val chunks = BoundedText.splitForCompose(a + b, maxChars = 8_192)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 8_192 })
        assertEquals(a + b, chunks.joinToString(""))
        val tiny = BoundedText.splitForCompose("hi")
        assertEquals(listOf("hi"), tiny)
    }

    @Test
    fun `selectPrewarmFragments never adds a giant fragment`() {
        val giant = "G".repeat(BoundedText.MAX_PREWARM_FRAGMENT_CHARS + 1)
        val small = "ok"
        val picked = BoundedText.selectPrewarmFragments(
            newestFirst = listOf(giant, small, "also"),
            rowLimit = 16,
            charBudget = 96_000,
        )
        assertEquals(listOf("ok", "also"), picked)
        assertFalse(picked.any { it.length > BoundedText.MAX_PREWARM_FRAGMENT_CHARS })
    }

    @Test
    fun `selectPrewarmFragments does not let one fragment blow the budget`() {
        // Old ChatScreen loop added first, then checked the budget — a 5 MB
        // fence became the sole prewarm target on DefaultDispatcher.
        val almost = "a".repeat(90_000)
        val picked = BoundedText.selectPrewarmFragments(
            newestFirst = listOf(almost, "tiny"),
            rowLimit = 16,
            charBudget = 8_000,
        )
        assertEquals(listOf("tiny"), picked)
    }

    @Test
    fun `readFileRange never materializes the whole file`() {
        val dir = File.createTempFile("bounded-text", "dir").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val file = File(dir, "minis-test.log")
        file.writeText("HEAD" + "m".repeat(50_000) + "TAIL")
        val slice = BoundedText.readFileRange(file, 0, 8)
        assertEquals("HEAD" + "m".repeat(4), slice)
        val tail = BoundedText.readFileTail(file, 4)
        assertEquals("TAIL", tail)
        file.delete()
        dir.delete()
    }
}
