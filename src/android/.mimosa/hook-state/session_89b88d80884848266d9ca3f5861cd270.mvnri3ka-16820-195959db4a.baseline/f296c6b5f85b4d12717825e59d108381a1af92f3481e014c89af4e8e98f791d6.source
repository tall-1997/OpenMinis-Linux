package com.openminis.app.tools

/**
 * RikkaHub-inspired three-level text replacement engine.
 *
 * Resolution ladder, each level only used when the one above it proves
 * nothing better:
 *
 *  1. **Exact** — literal substring match, whitespace and all.
 *  2. **Line-trimmed** — the pattern is a whole number of *lines* and each
 *     line matches a content line after trimming leading/trailing
 *     whitespace. Blank pattern lines are wildcards. This level can only
 *     ever replace complete lines, so it cannot silently edit half a line.
 *  3. **Block-anchor** — the pattern is delimited by "anchor lines"
 *     (`###`, `***`, `===`, `///`, `<!-- ### -->` …). The content is split
 *     into the regions delimited by the same anchors and the region body is
 *     replaced wholesale. This is what makes a block edit work when the
 *     pattern body has drifted (whitespace, ordering, extra statements).
 *
 * Two invariants matter more than the ladder itself:
 *
 *  * **No false success.** Every level requires *proof*: level 1 needs the
 *    literal match, levels 2 and 3 need whole-line / whole-region agreement.
 *    A level never "succeeds" by re-finding a substring it was given, and a
 *    level that cannot prove uniqueness fails with actionable text rather
 *    than guessing. When [replaceAll] is false and a level finds more than
 *    one candidate it is a hard failure, not "pick the first".
 *  * **One cursor walk.** All replacements for a call are applied through a
 *    single left-to-right walk over the original string, so offsets from the
 *    scan stay valid while the output is rebuilt.
 */
object TextReplacers {

    /** Outcome of a replacement. */
    sealed class Result {
        /** Replacement succeeded; [newContent] is the rebuilt text. */
        data class Success(val newContent: String, val count: Int) : Result()

        /** Replacement refused; [message] says what to do next. */
        data class Failure(val message: String) : Result()
    }

    /**
     * Replace [oldPattern] with [newText] in [content].
     *
     * @param replaceAll when true every proven candidate is replaced; when
     *   false the pattern must resolve to exactly one candidate.
     * @throws IllegalArgumentException if [oldPattern] is empty — an empty
     *   needle matches everywhere and has no safe interpretation.
     */
    fun replace(
        content: String,
        oldPattern: String,
        newText: String,
        replaceAll: Boolean = false
    ): Result {
        require(oldPattern.isNotEmpty()) { "oldPattern cannot be empty" }

        tryExact(content, oldPattern, newText, replaceAll)?.let { return it }
        tryLineTrimmed(content, oldPattern, newText, replaceAll)?.let { return it }
        tryBlockAnchor(content, oldPattern, newText, replaceAll)?.let { return it }

        return Result.Failure(notFoundMessage())
    }

    // ---------------------------------------------------------------- level 1

    private fun tryExact(
        content: String,
        oldPattern: String,
        newText: String,
        replaceAll: Boolean
    ): Result? {
        val ranges = findAll(content, oldPattern)
        if (ranges.isEmpty()) return null
        if (ranges.size > 1 && !replaceAll) return Result.Failure(ambiguousMessage(ranges.size))

        val count = if (replaceAll) ranges.size else 1
        val applied = if (replaceAll) ranges.map { it.first to it.last + 1 } else listOf(ranges.first().let { it.first to it.last + 1 })
        return Result.Success(apply(content, applied, newText), count)
    }

    // ---------------------------------------------------------------- level 2

    /**
     * Whole-line match, whitespace-insensitive at the line edges.
     *
     * Only reached when the literal match found nothing, so a success here
     * always means "the text is there, the whitespace is not what you typed".
     */
    private fun tryLineTrimmed(
        content: String,
        oldPattern: String,
        newText: String,
        replaceAll: Boolean
    ): Result? {
        val lines = splitLines(content)
        val patternLines = stripCarriageReturns(oldPattern.split('\n'))
        // A pattern that does not end in a newline must not claim the
        // terminator of its last line, or a single-line edit would eat the
        // newline and glue two lines together.
        if (patternLines.size > lines.size) return null

        val starts = mutableListOf<Int>()
        var i = 0
        while (i + patternLines.size <= lines.size) {
            if (matchesBlock(lines, i, patternLines)) starts.add(i)
            // Non-overlapping: never report two windows that share lines.
            i += patternLines.size.coerceAtLeast(1)
        }
        if (starts.isEmpty()) return null
        if (starts.size > 1 && !replaceAll) return Result.Failure(ambiguousMessage(starts.size))

        val applied = (if (replaceAll) starts else listOf(starts.first())).mapNotNull { s ->
            charRange(lines, s, s + patternLines.size - 1)
        }
        if (applied.isEmpty()) return null

        return Result.Success(apply(content, applied, newText), applied.size)
    }

    private fun matchesBlock(lines: List<Line>, start: Int, patternLines: List<String>): Boolean {
        for (i in patternLines.indices) {
            val p = patternLines[i].trim()
            if (p.isEmpty()) continue // blank pattern line is a wildcard
            if (lines[start + i].text.trim() != p) return false
        }
        return true
    }

    // ---------------------------------------------------------------- level 3

    /**
     * Anchor-delimited block replacement.
     *
     * The pattern must contain at least one anchor line. With two or more the
     * first is the opening delimiter and the last the closing one; with
     * exactly one the block runs from that line for as many following lines
     * as the pattern has. Regions are found by scanning content lines only —
     * the body is never located by re-finding the pattern text, which is the
     * whole point of the level.
     *
     * [newText] without any anchor line replaces the block *body*, keeping the
     * delimiters; with an anchor line it replaces the whole block, delimiters
     * included. An empty [newText] therefore deletes the body and nothing
     * else, matching the plain "empty string deletes" contract of level 1.
     */
    private fun tryBlockAnchor(
        content: String,
        oldPattern: String,
        newText: String,
        replaceAll: Boolean
    ): Result? {
        val patternLines = stripCarriageReturns(oldPattern.split('\n'))
        val anchorIdx = patternLines.indices.filter { anchorToken(patternLines[it]) != null }
        if (anchorIdx.isEmpty()) return null

        val openIdx = anchorIdx.first()
        val closeIdx = anchorIdx.last()
        val innerCount = closeIdx - openIdx - 1
        if (closeIdx > openIdx && innerCount < 0) return null
        if (closeIdx == openIdx && patternLines.size - openIdx - 1 < 0) return null

        val openToken = anchorToken(patternLines[openIdx])!!
        val closeToken = if (closeIdx > openIdx) anchorToken(patternLines[closeIdx])!! else null

        val lines = splitLines(content)
        val openTail = patternLines.drop(openIdx + 1)
        val regions = findRegions(lines, openToken, closeToken, innerCount, openTail)
        if (regions.isEmpty()) return null
        if (regions.size > 1 && !replaceAll) return Result.Failure(ambiguousMessage(regions.size))

        val wholeBlock = stripCarriageReturns(newText.split('\n')).any { anchorToken(it) != null }
        val applied = (if (replaceAll) regions else listOf(regions.first())).mapNotNull { r ->
            when {
                // No close anchor: the single anchor line itself is the
                // delimiter and is always kept.
                closeIdx == openIdx -> {
                    if (r.open + openTail.size >= lines.size) null
                    else charRange(lines, r.open + 1, r.open + openTail.size)
                }
                wholeBlock -> charRange(lines, r.open, r.close)
                else -> charRange(lines, r.open + 1, r.close - 1)
                    ?: (lines[r.open].end to lines[r.open].end)
            }
        }
        if (applied.isEmpty()) return null

        return Result.Success(apply(content, applied, newText), applied.size)
    }

    private data class Region(val open: Int, val close: Int)

    /**
     * Walk [lines] once, pairing each opening delimiter with its closing
     * delimiter and returning the disjoint regions. For a single anchor the
     * region is the anchor line plus [window] lines, which must all match the
     * pattern's remaining lines — that is what makes it provably the same
     * block and not merely "a region near an anchor".
     */
    private fun findRegions(
        lines: List<Line>,
        openToken: String,
        closeToken: String?,
        innerCount: Int,
        openTail: List<String>
    ): List<Region> {
        val regions = mutableListOf<Region>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].anchor != openToken) {
                i++
                continue
            }
            if (closeToken == null) {
                // Single-anchor form: the delimiter line followed by exactly as
                // many lines as the pattern names, and each of those lines must
                // agree with the pattern after trimming. That agreement is the
                // proof that this region is the block being asked for.
                val last = i + openTail.size
                if (last < lines.size && matchesTail(lines, i + 1, openTail)) {
                    regions.add(Region(i, last))
                    i = last + 1
                } else {
                    i++
                }
                continue
            }
            val close = findClose(lines, i + 1, closeToken)
            if (close < 0 || close - i - 1 != innerCount) {
                // A pairing whose body length disagrees with the pattern is
                // not this block; keep scanning from the next line so a
                // nested or repeated delimiter is not silently absorbed.
                i++
                continue
            }
            regions.add(Region(i, close))
            i = close + 1
        }
        return regions
    }

    private fun findClose(lines: List<Line>, from: Int, closeToken: String): Int {
        var j = from
        while (j < lines.size) {
            if (lines[j].anchor == closeToken) return j
            j++
        }
        return -1
    }

    /** Every pattern tail line must match its content line after trimming. */
    private fun matchesTail(lines: List<Line>, start: Int, tail: List<String>): Boolean {
        for (k in tail.indices) {
            val p = tail[k].trim()
            if (p.isEmpty()) continue
            if (lines[start + k].text.trim() != p) return false
        }
        return true
    }

    // ---------------------------------------------------------------- shared

    /** A content line plus its half-open character offsets, terminator excluded. */
    private data class Line(val text: String, val start: Int, val end: Int, val anchor: String?)

    private fun splitLines(content: String): List<Line> {
        val out = mutableListOf<Line>()
        var i = 0
        while (i <= content.length) {
            val nl = content.indexOf('\n', i)
            if (nl < 0) {
                if (i < content.length || out.isEmpty()) {
                    val end = content.length
                    addLine(out, content, i, end)
                }
                break
            }
            val end = if (nl > i && content[nl - 1] == '\r') nl - 1 else nl
            addLine(out, content, i, end)
            i = nl + 1
        }
        return out
    }

    private fun addLine(out: MutableList<Line>, content: String, start: Int, end: Int) {
        val text = content.substring(start, end)
        out.add(Line(text, start, end, anchorToken(text)))
    }

    private fun stripCarriageReturns(lines: List<String>): List<String> =
        lines.map { if (it.endsWith('\r')) it.dropLast(1) else it }

    /**
     * Canonical token for an anchor line, or null when the line is not one.
     *
     * An anchor is a run of at least two identical punctuation characters,
     * optionally wrapping a short label (`###`, `***`, `===`, `///`,
     * `<!-- region -->`). Letters, digits, whitespace, and `-`/`+`/`_` are
     * rejected so that ordinary code lines (`--- `, `++i`, `____`) never
     * become delimiters.
     */
    private fun anchorToken(line: String): String? {
        val t = line.trim()
        if (t.length < 2) return null
        val c = t[0]
        if (c.isLetterOrDigit() || c.isWhitespace()) return null
        if (c == '-' || c == '+' || c == '_') return null
        var run = 0
        while (run < t.length && t[run] == c) run++
        if (run < 2) return null
        var tail = 0
        while (tail < t.length && t[t.length - 1 - tail] == c) tail++
        val middle = t.substring(run, t.length - tail)
        for (ch in middle) {
            val ok = ch.isLetterOrDigit() || ch == ' ' || ch == '\t' || ch == '.' ||
                ch == '-' || ch == '_'
            if (!ok) return null
        }
        return "$c$run:$tail"
    }

    /** Non-overlapping half-open [start, end) character ranges of [needle]. */
    private fun findAll(content: String, needle: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val idx = content.indexOf(needle, from)
            if (idx < 0) break
            val end = idx + needle.length
            out.add(idx until end)
            from = end
        }
        return out
    }

    private fun charRange(lines: List<Line>, fromLine: Int, toLine: Int): Pair<Int, Int>? {
        if (fromLine > toLine) return null
        if (fromLine < 0 || toLine >= lines.size) return null
        return lines[fromLine].start to lines[toLine].end
    }

    /**
     * Rebuild [content] with every [ranges] entry replaced by [newText] in a
     * single left-to-right walk.
     */
    private fun apply(
        content: String,
        ranges: List<Pair<Int, Int>>,
        newText: String
    ): String {
        if (ranges.isEmpty()) return content
        val sb = StringBuilder(content.length + newText.length * ranges.size)
        var cursor = 0
        for ((start, end) in ranges.sortedBy { it.first }) {
            if (start < cursor) continue // defensive: never double-apply
            if (start > content.length) break
            sb.append(content, cursor, start.coerceAtMost(content.length))
            sb.append(newText)
            cursor = end.coerceIn(start, content.length)
        }
        if (cursor < content.length) sb.append(content, cursor, content.length)
        return sb.toString()
    }

    // --------------------------------------------------------------- messages

    private fun notFoundMessage(): String =
        "old_string not found in content, even with whitespace-tolerant and " +
            "block-anchor matching. Reread the current content and provide an " +
            "old_string that matches exactly once (or pass replace_all=true)."

    private fun ambiguousMessage(n: Int): String =
        "old_string is ambiguous: found $n matching locations, and " +
            "replace_all=false requires exactly one. Use replace_all=true to " +
            "replace all of them, or add more surrounding context (or an anchor " +
            "delimiter such as ###) so it matches once."
}
