package com.openminis.app.diagnostics

import com.openminis.app.text.BoundedText
import java.util.concurrent.atomic.AtomicReference

/**
 * [T-android-content-perf-diag] Summary-level content-shape diagnostics for the
 * markdown render perf path. Given a message body, computes a cheap structural
 * fingerprint (length, lines, paragraphs, table / code-block / math markers)
 * that pinpoints *which content structure* is driving a render hang — without
 * ever logging the body itself (no perf overhead, no privacy leak).
 *
 * Used by:
 *  - ChatScreen cold-open summary — one line per large message.
 *  - LargeContentGuard — at streaming degrade + stream-end markdown swap.
 *  - HangDetector — attaches the currently-rendering message's fingerprint to
 *    each JankDiag stall sample (via [currentRender]) so a Matcher/Pattern
 *    stack maps straight to "this message, this structure" from the log alone.
 *
 * Regex fingerprints run only on a bounded head+tail window. A 36 MB log or a
 * 5 MB tool dump must never reach `Matcher.reset` (2026-09-18 Scudo OOM).
 */
/** Below this length a message never drives a render hang, so skip the summary. */
const val CONTENT_DIAG_MIN_CHARS = 5_000

object ContentDiag {

    data class Summary(
        val chars: Int,
        val lines: Int,
        val paragraphs: Int,
        val tableCount: Int,
        val codeBlockCount: Int,
        val mathCount: Int,
    ) {
        val hasTable: Boolean get() = tableCount > 0
        val hasCodeBlock: Boolean get() = codeBlockCount > 0
        val hasMath: Boolean get() = mathCount > 0

        /** Compact, greppable key=value fragment (no session/idx prefix). */
        fun asLogFields(): String =
            "chars=$chars lines=$lines paragraphs=$paragraphs " +
                "hasTable=$hasTable tableCount=$tableCount " +
                "hasCodeBlock=$hasCodeBlock codeBlockCount=$codeBlockCount " +
                "hasMath=$hasMath mathCount=$mathCount"
    }

    // Fenced code block opener/closer (``` or ~~~), start-of-line.
    private val FENCE = Regex("""(?m)^\s{0,3}(```|~~~)""")
    // A markdown table separator row: | --- | :---: | --- | (the load-bearing
    // line that turns pipe rows into a real table).
    private val TABLE_SEP = Regex("""(?m)^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")
    // Display math: $$…$$ or \[…\]. Counted as blocks (pairs → count via markers/2).
    private val DISPLAY_MATH_DOLLAR = Regex("""\$\$""")
    private val DISPLAY_MATH_BRACKET = Regex("""\\\[""")
    // Inline math: \(…\) or single-$…$ (rough — single-$ is only counted when it
    // is NOT part of a $$ run, to avoid double-counting display math).
    private val INLINE_MATH_PAREN = Regex("""\\\(""")

    /**
     * Compute the structural summary. Length / line count walk the full string
     * (O(n) Java, no ICU). Regex fingerprints run only on a bounded head+tail
     * window.
     */
    fun summarize(text: String): Summary {
        if (text.isEmpty()) return Summary(0, 0, 0, 0, 0, 0)
        val lines = text.count { it == '\n' } + 1
        val scan = BoundedText.scanWindow(text)
        val paragraphs = countParagraphs(scan)
        val fenceMarkers = FENCE.findAll(scan).count()
        val codeBlockCount = (fenceMarkers + 1) / 2
        val tableCount = TABLE_SEP.findAll(scan).count()
        val displayDollar = DISPLAY_MATH_DOLLAR.findAll(scan).count() / 2
        val displayBracket = DISPLAY_MATH_BRACKET.findAll(scan).count()
        val inlineParen = INLINE_MATH_PAREN.findAll(scan).count()
        val mathCount = displayDollar + displayBracket + inlineParen
        return Summary(
            chars = text.length,
            lines = lines,
            paragraphs = paragraphs,
            tableCount = tableCount,
            codeBlockCount = codeBlockCount,
            mathCount = mathCount,
        )
    }

    /** Blank-line-separated paragraphs without compiling a Regex on the body. */
    internal fun countParagraphs(text: String): Int {
        var n = 0
        var inPara = false
        var atLineStart = true
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                if (atLineStart) {
                    if (inPara) {
                        n++
                        inPara = false
                    }
                } else {
                    atLineStart = true
                }
                i++
                continue
            }
            if (atLineStart && (c == ' ' || c == '\t')) {
                i++
                continue
            }
            inPara = true
            atLineStart = false
            i++
        }
        if (inPara) n++
        return n.coerceAtLeast(1)
    }

    // ─── Current-render fingerprint for HangDetector correlation ───────────────

    data class RenderMarker(val sessionId: String, val messageId: String, val summary: Summary)

    private val current = AtomicReference<RenderMarker?>(null)

    /** Called by the render path when it begins rendering a large message body. */
    fun setCurrentRender(sessionId: String, messageId: String, summary: Summary) {
        current.set(RenderMarker(sessionId, messageId, summary))
    }

    /** Cleared when the large render completes / the block leaves composition. */
    fun clearCurrentRender(messageId: String) {
        val cur = current.get()
        if (cur != null && cur.messageId == messageId) current.set(null)
    }

    /** For HangDetector: a greppable fragment for the message on-screen, or "". */
    fun currentRenderLogFields(): String {
        val cur = current.get() ?: return ""
        return " renderSession=${cur.sessionId} renderMsg=${cur.messageId} ${cur.summary.asLogFields()}"
    }
}
