package com.openminis.app.sandbox

import com.openminis.app.tools.ToolOutputTruncator

/**
 * Strips ANSI escape sequences and handles CR-based line overwrites
 * from terminal output. Corresponds to iOS AIChatViewModel.sanitizeTerminalOutput().
 */
object TerminalSanitizer {

    // Matches ANSI/VT escape sequences:
    //   ESC [ ... final_byte (CSI sequences)
    //   ESC ] ... ST (OSC sequences terminated by BEL or ESC\)
    //   ESC followed by single character (simple escapes)
    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[0-9;]*[A-Za-z]|\][^\x07]*(?:\x07|\x1B\\)|\[[0-9;]*m|[()][0-2AB]|[A-Za-z])"""
    )

    /**
     * Sanitize terminal output in two passes:
     * 1. CR folding — simulate carriage return overwriting
     * 2. Strip remaining ANSI/VT escape sequences
     */
    private val offloadExit = Regex("""proot info: native_offload:.*\bexit=(\d+)""")

    /**
     * Drop proot's offload trace line. The guest stub can still exit 0; the
     * logged `exit=` is the host handler's real status.
     */
    fun dropOffloadTrace(raw: String): Pair<String, Int?> {
        if (!raw.contains("proot info: native_offload:")) return raw to null
        var logged: Int? = null
        val kept = raw.lineSequence().filter { line ->
            if (!line.contains("proot info: native_offload:")) return@filter true
            logged = offloadExit.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: logged
            false
        }.joinToString("\n")
        return kept to logged
    }

    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        // Pass 1: CR folding
        val crFolded = foldCarriageReturns(raw)

        // Pass 2: Strip ANSI sequences
        val stripped = ANSI_REGEX.replace(crFolded, "")

        // Pass 3: Remove null bytes and non-printable control chars (except
        // \n \t and the record/unit separators \u001e \u001f). \u001f is the
        // column delimiter of structured shell output contracts — git's
        // `--pretty=format:%x1f` (ZCode getCommitGraph shape, the git panel's
        // history command) and similar machine-read pipelines route through
        // this sanitizer; stripping it glued every column into one and the
        // panel's parser dropped all rows as malformed (History tab showed
        // "no commits" whenever the sandbox source filled in). NUL/BEL/other
        // terminal hazards still go away.
        val cleaned = stripped.filter {
            it == '\n' || it == '\t' || it == '\u001f' || it == '\u001e' || it.code >= 0x20
        }

        // Pass 4: Remove "null" artifacts from PRoot/pipe issues
        // - Lines that are entirely "null"
        // - Runs of repeated "null" (e.g., "nullnullnull" → "")
        // - Lines that are just "null" appended to a prefix (e.g., "file:nullnullnull")
        val noNullLines = cleaned.lines()
            .filter { it.trim() != "null" }
            .joinToString("\n")
            .replace(Regex("(?:null){2,}"), "") // Remove runs of 2+ consecutive "null"

        // Pass 5: Collapse excessive blank lines (3+ consecutive → 2)
        return noNullLines.replace(Regex("\n{3,}"), "\n\n").trim('\n', '\r')
    }

    /**
     * Truncate output if it exceeds maxChars, keeping head and tail.
     */
    fun truncateIfNeeded(output: String, maxChars: Int = 50_000): String {
        if (output.length <= maxChars) return output

        val keepEach = maxChars / 2
        val head = output.substring(0, keepEach)
        val tail = output.substring(output.length - keepEach)
        val omitted = output.length - maxChars
        return "$head\n\n[... $omitted characters omitted ...]\n\n$tail"
    }

    /**
     * [pi-port §4.7] Structured shell-output truncation: same content shape
     * as [truncateIfNeeded] (head + legacy `[... N characters omitted ...]`
     * marker + tail, so nothing downstream sees a different string), paired
     * with the shared [ToolOutputTruncator.TruncationResult] describing the
     * cut so callers can append ONE structured annotation line instead of
     * hand-building report strings. Null result = nothing was cut.
     *
     * Field semantics here: this gate budgets UTF-16 chars (the pi-style
     * UTF-8 byte gates live in ToolOutputTruncator; changing this call
     * site's budget semantics would alter what the model sees, which §4.7
     * keeps out of scope), so `maxBytes` carries the char-budget value for
     * traceability and `maxLines` is unbounded — no line limit participates.
     * `truncatedBy` reports BYTES as the nearest match. `outputLines`/`outputBytes`
     * describe the returned content (the legacy marker lines included, per
     * the TruncationResult contract).
     */
    fun truncateStructured(
        output: String,
        maxChars: Int = 50_000,
    ): Pair<String, ToolOutputTruncator.TruncationResult?> {
        if (output.length <= maxChars) return output to null
        val truncated = truncateIfNeeded(output, maxChars)
        val result = ToolOutputTruncator.TruncationResult(
            content = truncated,
            truncated = true,
            truncatedBy = ToolOutputTruncator.TruncatedBy.BYTES,
            totalLines = ToolOutputTruncator.totalLines(output),
            totalBytes = ToolOutputTruncator.utf8ByteLength(output),
            outputLines = ToolOutputTruncator.totalLines(truncated),
            outputBytes = ToolOutputTruncator.utf8ByteLength(truncated),
            lastLinePartial = false,
            firstLineExceedsLimit = false,
            maxLines = Int.MAX_VALUE,
            maxBytes = maxChars,
        )
        return truncated to result
    }

    /**
     * One-line structured truncation annotation, aligned with the
     * GrepStreamScan report style (`…[output truncated at N chars — …]…`):
     *
     *   `[output truncated — showed N of M lines (K dropped)]`
     *
     * Derived entirely from the shared [ToolOutputTruncator.TruncationResult]
     * — every number is read off the result, never re-computed per call site.
     * When the kept content is not shorter than the original by lines (the
     * degenerate one-giant-line cut, where the marker inflates the kept line
     * count), the note degrades to the byte-only form rather than printing a
     * nonsensical "showed N of 1 lines".
     */
    fun formatTruncationNote(result: ToolOutputTruncator.TruncationResult): String {
        val dropped = result.totalBytes - result.outputBytes
        if (result.outputLines >= result.totalLines || result.totalLines <= 0) {
            return "[output truncated — ${ToolOutputTruncator.formatSize(dropped)} dropped]"
        }
        return "[output truncated — showed ${result.outputLines} of ${result.totalLines} lines " +
            "(${ToolOutputTruncator.formatSize(dropped)} dropped)]"
    }

    /**
     * Simulate CR (\r) behavior: when a line contains \r (without \n),
     * the text after \r overwrites from the beginning of the line.
     * Each \r resets the cursor to position 0, so only the last segment's
     * content (up to its length) is visible.
     */
    private fun foldCarriageReturns(text: String): String {
        val lines = text.split('\n')
        val result = StringBuilder()

        for ((index, line) in lines.withIndex()) {
            if (index > 0) result.append('\n')

            if ('\r' !in line) {
                result.append(line)
                continue
            }

            // Simulate a terminal line buffer. CR resets the cursor to column
            // zero; following characters overwrite existing cells while any
            // untouched suffix remains visible.
            val buffer = StringBuilder()
            var cursor = 0
            for (ch in line) {
                if (ch == '\r') {
                    cursor = 0
                } else {
                    if (cursor < buffer.length) buffer.setCharAt(cursor, ch)
                    else buffer.append(ch)
                    cursor++
                }
            }
            result.append(buffer)
        }

        return result.toString()
    }
}
