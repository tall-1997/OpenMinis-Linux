package com.openminis.app.text

import java.io.File

/**
 * Caps CharSequence residency handed to ICU `Matcher.reset` / `utext_openUChars`.
 *
 * 2026-09-18 crash (Redmi myron / HyperOS): process SIGABRT with
 * `Scudo ERROR: internal map failure (error desc=Out of memory)` on
 * `DefaultDispatcher` while Kotlin `Regex` opened an ICU matcher on unbounded
 * markdown / log text. Device RAM was ample (~8 GB free); the app process
 * itself exhausted its native map. Same day `minis-*.log` grew to ~36 MB.
 *
 * ICU copies the input as UTF-16, so a multi-megabyte `CharSequence` is a
 * tens-of-MB native allocation *per* `findAll`/`matches` — and ContentDiag
 * plus the markdown parser issue several of those per pass.
 */
object BoundedText {

    /** ICU utext copies UTF-16; 16k chars ≈ 32 KB native per Matcher. */
    const val MAX_ICU_INPUT_CHARS = 16_384

    /**
     * Cache / cold-prewarm ceiling, aligned with LargeContentGuard collapse.
     * Expanded frozen messages and file preview must still parse the full
     * body; ICU is bounded per-line via [MAX_ICU_INPUT_CHARS], not by
     * chopping the document here.
     */
    const val MAX_MARKDOWN_PARSE_CHARS = 32_000

    /** RAW SSE / logcat payload preview. */
    const val MAX_SSE_LOG_CHARS = 240

    /** ToolInputDelta logcat stride (chars of accumulated JSON). */
    const val TOOL_INPUT_DELTA_LOG_STRIDE = 2_048

    /**
     * Max chars in one Compose `Text` / markdown block. A 512 KB file preview
     * (or an expanded fence) must still parse, but a single Paragraph of that
     * size will stall measure on the UI thread.
     */
    const val MAX_COMPOSE_BLOCK_CHARS = 8_192

    /** Extra room for ERROR/WARN after the daily file hits [MAX_LOG_FILE_BYTES]. */
    const val MAX_LOG_OVERFLOW_BYTES = 512L * 1024

    /** ContentDiag fingerprint window (head + tail). */
    const val MAX_CONTENT_DIAG_SCAN_CHARS = 8_192

    /** Daily `minis-yyyy-MM-dd.log` hard cap. */
    const val MAX_LOG_FILE_BYTES = 8L * 1024 * 1024

    /** Single log line cap so one dump cannot fill the daily file. */
    const val MAX_LOG_LINE_CHARS = 4_096

    /** `AppLogger.readLog` / debug RPC never materializes more than this. */
    const val MAX_LOG_READ_BYTES = 256 * 1024

    /** Skip cold-open prewarm of fragments above the collapse threshold. */
    const val MAX_PREWARM_FRAGMENT_CHARS = MAX_MARKDOWN_PARSE_CHARS

    fun icuWindow(text: CharSequence, maxChars: Int = MAX_ICU_INPUT_CHARS): CharSequence {
        if (text.length <= maxChars) return text
        return text.subSequence(0, maxChars)
    }

    fun markdownParseInput(text: String, maxChars: Int = MAX_MARKDOWN_PARSE_CHARS): String {
        if (text.length <= maxChars) return text
        return text.substring(0, maxChars)
    }

    /**
     * Head + tail window so a fence at the start *or* a table separator at the
     * end of a huge buffer still shows up in ContentDiag, without handing the
     * whole String to ICU.
     */
    fun scanWindow(text: String, maxChars: Int = MAX_CONTENT_DIAG_SCAN_CHARS): String {
        if (text.length <= maxChars) return text
        val head = maxChars / 2
        val tail = maxChars - head
        return text.substring(0, head) + text.substring(text.length - tail)
    }

    fun clampLogLine(line: String, maxChars: Int = MAX_LOG_LINE_CHARS): String {
        if (line.length <= maxChars) return line
        return line.substring(0, maxChars) + "…[truncated ${line.length - maxChars} chars]"
    }

    fun canAppendLog(
        currentFileBytes: Long,
        lineBytes: Int,
        maxFileBytes: Long = MAX_LOG_FILE_BYTES,
    ): Boolean {
        if (currentFileBytes >= maxFileBytes) return false
        return currentFileBytes + lineBytes.toLong() <= maxFileBytes
    }

    fun isPriorityLogLine(line: String): Boolean =
        "[ERROR]" in line || "[WARN]" in line

    /**
     * After the daily cap, ERROR/WARN may still land until
     * [MAX_LOG_FILE_BYTES] + [MAX_LOG_OVERFLOW_BYTES].
     */
    fun canAppendPriorityLog(
        currentFileBytes: Long,
        lineBytes: Int,
    ): Boolean {
        val hard = MAX_LOG_FILE_BYTES + MAX_LOG_OVERFLOW_BYTES
        if (currentFileBytes >= hard) return false
        return currentFileBytes + lineBytes.toLong() <= hard
    }

    fun clampSsePayload(payload: String, maxChars: Int = MAX_SSE_LOG_CHARS): String {
        if (payload.length <= maxChars) return payload
        return payload.substring(0, maxChars) + "…"
    }

    fun shouldLogLengthStride(
        length: Int,
        stride: Int = TOOL_INPUT_DELTA_LOG_STRIDE,
    ): Boolean {
        if (length <= 64) return true
        if (stride <= 0) return false
        return length % stride < 32
    }

    /**
     * Snapshot accumulated tool JSON only when it grows across a 2 KB bucket
     * (plus every growth in the first 64 chars, so `tool_title` still lands).
     * Avoids `StringBuilder.toString()` on every SSE token — that copy is
     * O(n) per token, O(n²) over a large file_write.
     */
    fun shouldCommitLengthStride(
        length: Int,
        lastCommitted: Int,
        stride: Int = TOOL_INPUT_DELTA_LOG_STRIDE,
    ): Boolean {
        if (length <= lastCommitted) return false
        if (lastCommitted == 0 || length <= 64) return true
        if (stride <= 0) return true
        return length / stride > lastCommitted / stride
    }

    /** Split a huge paragraph/code body so LazyColumn can compose viewport chunks. */
    fun splitForCompose(text: String, maxChars: Int = MAX_COMPOSE_BLOCK_CHARS): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>((text.length + maxChars - 1) / maxChars)
        var i = 0
        while (i < text.length) {
            val end = minOf(i + maxChars, text.length)
            var cut = end
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end - 1)
                if (nl > i + maxChars / 2) cut = nl + 1
            }
            out.add(text.substring(i, cut))
            i = cut
        }
        return out
    }

    /**
     * Newest-first fragment picker for markdown prewarm. Skips fragments above
     * [maxFragmentChars] (those take the LargeContentGuard collapse path) and
     * never lets a single giant fragment blow past [charBudget] the way
     * "add then check" did on 2026-09-18.
     */
    fun selectPrewarmFragments(
        newestFirst: List<String>,
        rowLimit: Int,
        charBudget: Int,
        maxFragmentChars: Int = MAX_PREWARM_FRAGMENT_CHARS,
    ): List<String> {
        if (rowLimit <= 0 || charBudget <= 0) return emptyList()
        val out = ArrayList<String>(minOf(rowLimit, newestFirst.size))
        var charSum = 0
        for (raw in newestFirst) {
            if (out.size >= rowLimit) break
            if (raw.length > maxFragmentChars) continue
            if (charSum + raw.length > charBudget) continue
            out.add(raw)
            charSum += raw.length
        }
        return out
    }

    fun readFileRange(file: File, offset: Long, maxBytes: Int): String {
        if (!file.exists() || maxBytes <= 0) return ""
        val len = file.length()
        if (len <= 0L) return ""
        val off = offset.coerceIn(0L, len)
        val want = maxBytes.coerceAtMost(MAX_LOG_READ_BYTES)
        file.inputStream().use { ins ->
            var remaining = off
            while (remaining > 0) {
                val skipped = ins.skip(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
            val buf = ByteArray(want)
            val n = ins.read(buf)
            if (n <= 0) return ""
            return String(buf, 0, n, Charsets.UTF_8)
        }
    }

    fun readFileTail(file: File, maxBytes: Int): String {
        if (!file.exists() || maxBytes <= 0) return ""
        val len = file.length()
        val off = (len - maxBytes.toLong()).coerceAtLeast(0L)
        return readFileRange(file, off, maxBytes)
    }
}
