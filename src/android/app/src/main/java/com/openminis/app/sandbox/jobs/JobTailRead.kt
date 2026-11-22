package com.openminis.app.sandbox.jobs

import java.io.File
import java.io.RandomAccessFile

/**
 * Bounded tail reading for [JobRegistry] job output — the job's stdout
 * spill file is never read whole: a capped byte window is read from the
 * end of the file, split to lines, and further capped in chars before
 * anything reaches the model. Pure file helpers (verbatim extraction from
 * JobRegistry); no registry state, no locking.
 */
object JobTailRead {

    /**
     * Output is read as a bounded tail, not the whole file: a chatty build
     * log can be megabytes and the model asked for the last N lines. The
     * window caps the read; [MAX_TAIL_CHARS] then caps what actually reaches
     * the model (same spirit as [com.openminis.app.tools.ToolOutputSpill.LIMIT]).
     */
    private const val TAIL_WINDOW_BYTES = 256 * 1024
    private const val MAX_TAIL_CHARS = 16_000

    // ---------------------------------------------------------- tail read

    /**
     * Bounded tail read: last [TAIL_WINDOW_BYTES] of the file, split to
     * lines, last [maxLines] kept. The first (possibly partial) line of the
     * window is dropped when the window starts mid-file, so a UTF-8 char cut
     * in half by the seek never leaks mojibake into the result.
     */
    fun readTailLines(file: File, maxLines: Int): Pair<String, Boolean> {
        val len = file.length()
        if (len <= 0L) return "" to false
        val window = minOf(len, TAIL_WINDOW_BYTES.toLong())
        val start = len - window
        val bytes = ByteArray(window.toInt())
        var read = 0
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            while (read < bytes.size) {
                val n = raf.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
        }
        var text = String(bytes, 0, read, Charsets.UTF_8)
        var truncated = start > 0L
        if (start > 0L) {
            val nl = text.indexOf('\n')
            if (nl < 0) return "" to true // one partial line fills the whole window
            text = text.substring(nl + 1)
        }
        val lines = text.split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        if (lines.size > maxLines) {
            while (lines.size > maxLines) lines.removeAt(0)
            truncated = true
        }
        var out = lines.joinToString("\n")
        if (out.length > MAX_TAIL_CHARS) {
            out = safeTail(out, MAX_TAIL_CHARS)
            truncated = true
        }
        return out to truncated
    }

    /** Surrogate-safe tail cut (mirror of ToolOutputPolicy.safeSubstring). */
    private fun safeTail(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var cut = text.length - maxChars
        if (Character.isLowSurrogate(text[cut])) cut++
        return text.substring(cut)
    }
}
