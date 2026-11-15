package com.openminis.app.tools

/**
 * Shared, dual-limit truncation utilities for tool outputs — full port of the
 * reference semantics in pi `durable/src/truncate.ts` (truncateHead) and
 * `coding-agent/src/core/tools/truncate.ts` (truncateTail + sliceHead/sliceTail),
 * plus the middle cut from pi `mcp/src/output-limit.ts` (truncateMiddle) so
 * every consumer cuts UTF-8 text through ONE boundary-safe implementation.
 *
 * Truncation is based on two independent limits — whichever is hit first wins:
 * - Line limit (default: 2000 lines)
 * - Byte limit (default: 50KB)
 *
 * [truncateHead]/[truncateTail] never return partial lines, with one documented
 * exception: when even the LAST line alone exceeds the byte limit, truncateTail
 * keeps the tail end of that line at a UTF-8 boundary (bash errors live at the
 * end of the stream). The byte limit is measured in UTF-8 bytes, because that
 * is what the model's tokenizer and the network both see — a char-based budget
 * would silently double for CJK-heavy output.
 */
object ToolOutputTruncator {

    /** pi durable/src/truncate.ts DEFAULT_MAX_LINES. */
    const val DEFAULT_MAX_LINES = 2000

    /** pi durable/src/truncate.ts DEFAULT_MAX_BYTES (50KB). */
    const val DEFAULT_MAX_BYTES = 50 * 1024

    /** Which of the two limits ended the output. Null when nothing was cut. */
    enum class TruncatedBy { LINES, BYTES }

    /**
     * Structured outcome of one truncation — the port of pi `TruncationResult`.
     * `totalLines`/`totalBytes` describe the ORIGINAL text; `outputLines`/
     * `outputBytes` describe the returned content.
     */
    data class TruncationResult(
        val content: String,
        val truncated: Boolean,
        val truncatedBy: TruncatedBy?,
        val totalLines: Int,
        val totalBytes: Int,
        val outputLines: Int,
        val outputBytes: Int,
        /** Only true for the truncateTail partial-last-line edge case. */
        val lastLinePartial: Boolean,
        /** True when the first line alone exceeded the byte limit (truncateHead). */
        val firstLineExceedsLimit: Boolean,
        val maxLines: Int,
        val maxBytes: Int,
    )

    // ── primitives ────────────────────────────────────────────────────────────

    /** UTF-8 byte length of [s] (pi `utf8ByteLength` / `Buffer.byteLength`). */
    fun utf8ByteLength(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /**
     * Line count with pi semantics: split on '\n', and a TRAILING newline ends
     * the last line instead of starting an empty one. Empty text has 0 lines.
     */
    fun totalLines(text: String): Int = splitLinesForCounting(text).size

    /** pi `splitLinesForCounting`: no trailing-newline phantom line. */
    internal fun splitLinesForCounting(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split("\n")
        if (content.endsWith("\n")) return lines.subList(0, lines.size - 1)
        return lines
    }

    /** Human-readable byte size (pi `formatSize`). Locale-independent decimal point. */
    fun formatSize(bytes: Int): String {
        val value = when {
            bytes < 1024 -> bytes.toDouble()
            bytes < 1024 * 1024 -> bytes / 1024.0
            else -> bytes / (1024.0 * 1024.0)
        }
        val suffix = when {
            bytes < 1024 -> "B"
            bytes < 1024 * 1024 -> "KB"
            else -> "MB"
        }
        return if (suffix == "B") "${bytes}B" else String.format(java.util.Locale.ROOT, "%.1f", value) + suffix
    }

    /**
     * At most the first [maxUnits] UTF-16 code units of [text], never splitting
     * a surrogate pair (pi `sliceHead`). Used for preview slices where the
     * budget is char-based.
     */
    fun sliceHead(text: String, maxUnits: Int): String {
        val end = maxOf(0, maxUnits)
        if (end == 0) return ""
        if (end >= text.length) return text
        val high = text[end - 1]
        val low = text[end]
        return if (high.isHighSurrogate() && low.isLowSurrogate()) {
            text.substring(0, end - 1)
        } else {
            text.substring(0, end)
        }
    }

    /**
     * At most the last [maxUnits] UTF-16 code units of [text], never splitting
     * a surrogate pair (pi `sliceTail`).
     */
    fun sliceTail(text: String, maxUnits: Int): String {
        if (maxUnits <= 0) return ""
        val start = maxOf(0, text.length - maxUnits)
        if (start == 0) return text
        val low = text[start]
        val high = text[start - 1]
        return if (low.isLowSurrogate() && high.isHighSurrogate()) {
            text.substring(start + 1)
        } else {
            text.substring(start)
        }
    }

    /**
     * At most the last [maxBytes] UTF-8 BYTES of [str], cut at a character
     * boundary (pi `truncateStringToBytesFromEnd`). Continuation bytes
     * (10xxxxxx) are never character starts, so the cut walks forward until it
     * lands on one. This is the documented truncateTail exception: the tail
     * line itself is longer than the whole budget.
     */
    internal fun sliceTailBytes(str: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val buf = str.toByteArray(Charsets.UTF_8)
        if (buf.size <= maxBytes) return str
        var start = buf.size - maxBytes
        while (start < buf.size && (buf[start].toInt() and 0xc0) == 0x80) start++
        return String(buf, start, buf.size - start, Charsets.UTF_8)
    }

    // ── truncateHead (file-read direction: keep the beginning) ───────────────

    /**
     * Keep the first [maxLines] lines / [maxBytes] bytes of [content] —
     * whichever limit is hit first. Never returns partial lines; when even the
     * FIRST line exceeds [maxBytes], returns empty content with
     * `firstLineExceedsLimit = true` instead of an unbounded dump.
     */
    fun truncateHead(
        content: String,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): TruncationResult {
        val totalBytes = utf8ByteLength(content)
        val lines = splitLinesForCounting(content)
        val totalLines = lines.size

        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return TruncationResult(
                content = content, truncated = false, truncatedBy = null,
                totalLines = totalLines, totalBytes = totalBytes,
                outputLines = totalLines, outputBytes = totalBytes,
                lastLinePartial = false, firstLineExceedsLimit = false,
                maxLines = maxLines, maxBytes = maxBytes,
            )
        }

        // First line alone over the byte budget: no complete line fits.
        val firstLineBytes = utf8ByteLength(lines[0])
        if (firstLineBytes > maxBytes) {
            return TruncationResult(
                content = "", truncated = true, truncatedBy = TruncatedBy.BYTES,
                totalLines = totalLines, totalBytes = totalBytes,
                outputLines = 0, outputBytes = 0,
                lastLinePartial = false, firstLineExceedsLimit = true,
                maxLines = maxLines, maxBytes = maxBytes,
            )
        }

        val kept = mutableListOf<String>()
        var keptBytes = 0
        var truncatedBy = TruncatedBy.LINES

        for (i in 0 until minOf(lines.size, maxLines)) {
            // +1 for the newline that separates this line from the previous one.
            val lineBytes = utf8ByteLength(lines[i]) + (if (i > 0) 1 else 0)
            if (keptBytes + lineBytes > maxBytes) {
                truncatedBy = TruncatedBy.BYTES
                break
            }
            kept.add(lines[i])
            keptBytes += lineBytes
        }

        // Without a byte break, only omitted lines prove the line limit was
        // reached; otherwise the overage was just the trailing newline.
        // (pi durable/src/truncate.ts line 156.)
        if (truncatedBy != TruncatedBy.BYTES) {
            truncatedBy = if (kept.size < totalLines) TruncatedBy.LINES else TruncatedBy.BYTES
        }

        val outputContent = kept.joinToString("\n")
        return TruncationResult(
            content = outputContent, truncated = true, truncatedBy = truncatedBy,
            totalLines = totalLines, totalBytes = totalBytes,
            outputLines = kept.size, outputBytes = utf8ByteLength(outputContent),
            lastLinePartial = false, firstLineExceedsLimit = false,
            maxLines = maxLines, maxBytes = maxBytes,
        )
    }

    // ── truncateTail (shell direction: errors live at the end) ───────────────

    /**
     * Keep the last [maxLines] lines / [maxBytes] bytes of [content] — suitable
     * for shell/bash output where the verdict is at the end. Whole lines only,
     * except the documented edge case: when the LAST line alone exceeds
     * [maxBytes], its tail end is kept at a UTF-8 boundary and
     * `lastLinePartial = true`.
     */
    fun truncateTail(
        content: String,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): TruncationResult {
        val totalBytes = utf8ByteLength(content)
        val lines = splitLinesForCounting(content)
        val totalLines = lines.size

        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return TruncationResult(
                content = content, truncated = false, truncatedBy = null,
                totalLines = totalLines, totalBytes = totalBytes,
                outputLines = totalLines, outputBytes = totalBytes,
                lastLinePartial = false, firstLineExceedsLimit = false,
                maxLines = maxLines, maxBytes = maxBytes,
            )
        }

        val kept = ArrayDeque<String>()
        var keptBytes = 0
        var truncatedBy = TruncatedBy.LINES
        var lastLinePartial = false

        var i = lines.size - 1
        while (i >= 0 && kept.size < maxLines) {
            val line = lines[i]
            // +1 for the newline between this line and the kept suffix.
            val lineBytes = utf8ByteLength(line) + (if (kept.isNotEmpty()) 1 else 0)
            if (keptBytes + lineBytes > maxBytes) {
                truncatedBy = TruncatedBy.BYTES
                // Edge case: not a single line added yet and this one overflows
                // the whole budget — keep its end (partial, boundary-safe).
                if (kept.isEmpty()) {
                    val partial = sliceTailBytes(line, maxBytes)
                    kept.addFirst(partial)
                    keptBytes = utf8ByteLength(partial)
                    lastLinePartial = true
                }
                break
            }
            kept.addFirst(line)
            keptBytes += lineBytes
            i--
        }

        if (kept.size >= maxLines && keptBytes <= maxBytes) truncatedBy = TruncatedBy.LINES

        val outputContent = kept.joinToString("\n")
        return TruncationResult(
            content = outputContent, truncated = true, truncatedBy = truncatedBy,
            totalLines = totalLines, totalBytes = totalBytes,
            outputLines = kept.size, outputBytes = utf8ByteLength(outputContent),
            lastLinePartial = lastLinePartial, firstLineExceedsLimit = false,
            maxLines = maxLines, maxBytes = maxBytes,
        )
    }

    // ── truncateMiddle (MCP output limit direction: keep both ends) ──────────

    /** Text cut in the middle, with the size of the whole text (pi output-limit.ts). */
    data class MiddleTruncation(
        /** Head + `…N chars truncated…` + tail. */
        val content: String,
        val totalBytes: Int,
        val totalLines: Int,
    )

    /**
     * Keep the start and the end of [text], half of [maxBytes] each, and replace
     * the middle with a `…N chars truncated…` marker. Cuts only at UTF-8
     * character boundaries (never splits a CJK character, an emoji, or any
     * multi-byte sequence). Returns null when [text] already fits.
     * Port of pi `mcp/src/output-limit.ts truncateMiddle`.
     */
    fun truncateMiddle(text: String, maxBytes: Int): MiddleTruncation? {
        val buf = text.toByteArray(Charsets.UTF_8)
        if (buf.size <= maxBytes) return null
        // Continuation bytes (10xxxxxx) are not character starts.
        val isBoundary = { index: Int -> index >= buf.size || (buf[index].toInt() and 0xc0) != 0x80 }
        var headEnd = maxBytes / 2
        while (headEnd > 0 && !isBoundary(headEnd)) headEnd--
        var tailStart = buf.size - (maxBytes - maxBytes / 2)
        while (tailStart < buf.size && !isBoundary(tailStart)) tailStart++
        val head = String(buf, 0, headEnd, Charsets.UTF_8)
        val tail = String(buf, tailStart, buf.size - tailStart, Charsets.UTF_8)
        val removed = String(buf, headEnd, tailStart - headEnd, Charsets.UTF_8)
        val removedChars = removed.codePointCount(0, removed.length)
        return MiddleTruncation(
            content = "${head}…${removedChars} chars truncated…$tail",
            totalBytes = buf.size,
            totalLines = totalLines(text),
        )
    }
}
