package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-large-file-guard] A TEXT file above [FileReadTool.MAX_STREAM_FILE_SIZE]
 * refuses the line-window pass — that pass scans to EOF so the header's "of N
 * lines" stays truthful, which on a multi-hundred-MB log means a full
 * sequential read with no feedback in between.
 *
 * These pin the three properties that make the refusal safe rather than lossy:
 * the preview comes from the end the caller asked for, the size wording is
 * derived from the constant instead of hardcoded, and control bytes cannot
 * escape into the transcript.
 */
class FileReadLargeFileGuardTest {

    @Test
    fun previewLabelsTheRequestedEnd() {
        val bytes = "hello world".toByteArray()
        val budget = FileReadTool.LARGE_TEXT_PREVIEW_BYTES

        val head = FileReadTool.largeTextPreview("/tmp/x.log", 999L, bytes, false)
        assertTrue(head, head.contains("First $budget bytes"))
        assertTrue(head, head.contains("direction=tail for the end"))
        assertFalse(head, head.contains("Last "))

        val tail = FileReadTool.largeTextPreview("/tmp/x.log", 999L, bytes, true)
        assertTrue(tail, tail.contains("Last $budget bytes"))
        assertTrue(tail, tail.contains("direction=head for the beginning"))
        assertTrue(tail, tail.contains("hello world"))
    }

    @Test
    fun thresholdWordingFollowsTheConstant() {
        val msg = FileReadTool.largeTextPreview("/tmp/x.log", 1L, ByteArray(0), false)
        val mb = FileReadTool.MAX_STREAM_FILE_SIZE / (1024 * 1024)
        assertTrue(msg, msg.contains("exceeds $mb MB"))
        assertTrue(msg, msg.contains("too large to stream"))
    }

    @Test
    fun controlBytesAreSanitised() {
        // A, NUL, BEL, B — both control bytes must become the replacement dot.
        val noisy = byteArrayOf(0x41, 0x00, 0x07, 0x42)
        val out = FileReadTool.largeTextPreview("/tmp/x", 4L, noisy, false)
        assertTrue(out, out.contains("A\u00b7\u00b7B"))
        assertFalse(out, out.contains(0.toChar().toString()))
        assertFalse(out, out.contains(7.toChar().toString()))
    }

    @Test
    fun previewIsTruncatedToTheBudget() {
        val over = ByteArray(FileReadTool.LARGE_TEXT_PREVIEW_BYTES + 500) { 'x'.code.toByte() }
        val out = FileReadTool.largeTextPreview("/tmp/x", over.size.toLong(), over, false)
        val body = out.substringAfter(":\n").substringBefore("\nFile exceeds")
        assertEquals(FileReadTool.LARGE_TEXT_PREVIEW_BYTES, body.length)
    }

    @Test
    fun readTailTakesTheLastBytesWithoutScanning() {
        val f = File.createTempFile("minis-tail-guard", ".txt")
        try {
            f.writeText("0123456789ABCDEFGHIJ")
            assertEquals("HIJ", String(FileReadTool.readTail(f, 3)))
            assertEquals("BCDEFGHIJ", String(FileReadTool.readTail(f, 9)))
            // Asking for more than the file holds yields the whole file.
            assertEquals("0123456789ABCDEFGHIJ", String(FileReadTool.readTail(f, 1000)))
            assertEquals("", String(FileReadTool.readTail(f, 0)))
        } finally {
            f.delete()
        }
    }

    @Test
    fun tailPreviewMatchesWhatReadTailReturns() {
        val f = File.createTempFile("minis-tail-preview", ".log")
        try {
            f.writeText("first line\nsecond line\nlast line\n")
            val bytes = FileReadTool.readTail(f, 10)
            assertEquals("last line", String(bytes).trim())
            val out = FileReadTool.largeTextPreview(f.path, f.length(), bytes, true)
            assertTrue(out, out.contains("Last ${FileReadTool.LARGE_TEXT_PREVIEW_BYTES} bytes"))
            assertTrue(out, out.contains("last line"))
        } finally {
            f.delete()
        }
    }
}
