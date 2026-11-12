package com.openminis.app.ui.markdown

/**
 * Markdown 数学提取的纯辅助函数（从 MarkdownParser 拆出，架构行数上限）：
 * 定界符扫描 / 货币启发式 / 引用块前缀剥离。行为逐字节保持。
 */
internal object MarkdownMathHelpers {

    private val blankLineRegex = Regex("\\n[ \\t]*\\n")
    private val trailingNlRegex = Regex("\\n[ \\t]*$")

    /**
     * Strip blockquote markers from a multi-line math body. Math is extracted
     * before block-level parsing, so a display formula written inside a
     * blockquote drags the continuation lines' `> ` markers into the LaTeX:
     *
     *     > $$
     *     > E = mc^2
     *     > $$
     *
     * used to yield `"\n> E = mc^2\n> "` and render a literal `>`. Only strip
     * when *every* non-blank line after the first carries a `^ {0,3}> ?`
     * marker — that proves the span really sits inside a quote, and protects
     * legitimate formula content whose lines begin with `>` (comparisons,
     * matrix rows). Mirrors iOS MarkdownMathExtractor.stripBlockquoteMarkers.
     */
    fun stripBlockquoteMarkers(latex: String): String {
        if (!latex.contains('\n')) return latex

        // The opening `$$` / `\[` is consumed by the caller, so the first
        // segment is the tail of the marker line and never carries a marker
        // of its own — validate and strip only lines 2…n.
        val lines = latex.split("\n").toMutableList()
        var sawMarker = false
        for (idx in 1 until lines.size) {
            val line = lines[idx]
            var cursor = 0
            while (cursor < line.length && line[cursor] == ' ' && cursor < 3) cursor++
            if (cursor >= line.length) continue // blank line: neutral
            if (line[cursor] != '>') return latex // a bare line — not a quote
            sawMarker = true
            cursor++
            if (cursor < line.length && line[cursor] == ' ') cursor++
            lines[idx] = line.substring(cursor)
        }
        if (!sawMarker) return latex
        return lines.joinToString("\n")
    }

    fun findClose(chars: String, from: Int, close: String): Int? {
        val n = chars.length
        var i = from
        while (i <= n - close.length) {
            var match = true
            for (j in close.indices) {
                if (chars[i + j] != close[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return null
    }

    /**
     * [T-android-latex-code-mask] (issue #117 defect 3, iOS bce7e2ed)
     *
     * The main [extractMath] loop skips fenced blocks and inline code when
     * deciding where a formula may *open*, but the closing-delimiter searches
     * were blind forward scans. So one bare `$$` in prose — a model that
     * forgot to close it, or text merely *explaining* LaTeX — paired with a
     * `$$` inside a later ``` fence and swallowed every paragraph between
     * them, including the fence's own opening line. The renderer then saw the
     * orphaned closing ``` as a NEW fence and turned the remainder into a code
     * block: the "a whole section suddenly disappeared" symptom.
     *
     * This precomputes, once per parse, which indices sit inside fenced blocks
     * or inline code, mirroring the main loop's rules. A closer inside the mask
     * is rejected.
     */
    internal fun buildCodeMask(chars: String): BooleanArray { // internal: MarkdownInlineHtml shares this mask
        val n = chars.length
        val mask = BooleanArray(n)
        var i = 0
        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        fun atLineStart(idx: Int): Boolean =
            idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        while (i < n) {
            if (!inFence && atLineStart(i)) {
                val c = chars[i]
                if (c == '`' || c == '~') {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == c) { fl++; j++ }
                    if (fl >= 3) {
                        inFence = true; fenceChar = c; fenceLen = fl
                        while (i < n && chars[i] != '\n') { mask[i] = true; i++ }
                        if (i < n) { mask[i] = true; i++ }
                        continue
                    }
                }
            }
            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar) {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == fenceChar) { fl++; j++ }
                    if (fl >= fenceLen) {
                        inFence = false
                        while (i < n && chars[i] != '\n') { mask[i] = true; i++ }
                        if (i < n) { mask[i] = true; i++ }
                        continue
                    }
                }
                mask[i] = true; i++
                continue
            }
            // Inline code span: `…` / ``…`` — same run-matching rule the main
            // loop uses when it copies these verbatim.
            if (chars[i] == '`') {
                var run = 0
                var j = i
                while (j < n && chars[j] == '`') { run++; j++ }
                var k = j
                var closed = -1
                while (k < n) {
                    if (chars[k] == '`') {
                        var r2 = 0
                        var m = k
                        while (m < n && chars[m] == '`') { r2++; m++ }
                        if (r2 == run) { closed = m; break }
                        k = m
                    } else k++
                }
                val end = if (closed > 0) closed else j
                for (idx in i until end) mask[idx] = true
                i = end
                continue
            }
            i++
        }
        return mask
    }

    /**
     * [T-android-latex-code-mask] A multi-line `$$` body must still look like a
     * formula, so a genuinely unclosed `$$` cannot eat prose even when the
     * closer is outside any code span. Single-line `$$…$$` is accepted
     * unconditionally, leaving ordinary display math untouched. Mirrors iOS.
     */
    fun isPlausibleDisplayBody(body: String): Boolean {
        if (!body.contains('\n')) return true
        // A blank line means a paragraph break — prose, not one formula.
        if (blankLineRegex.containsMatchIn(body)) return false
        // The conventional block shape puts the closing `$$` alone on its own
        // line, i.e. the body ends with a newline (plus optional indent). That
        // is a strong enough signal on its own — requiring a LaTeX glyph here
        // too would wrongly demote glyph-free but valid math such as
        // "$$\n1 + 2 = 3\n$$" to plain text.
        if (trailingNlRegex.containsMatchIn(body)) return true
        // Otherwise the closer is mid-line, which is the shape a stray
        // delimiter in prose produces — require a LaTeX-ish glyph.
        return body.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }
    }

    fun findDoubleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n - 1) {
            if (chars[i] == '$' && chars[i + 1] == '$' &&
                (codeMask == null || !codeMask[i])
            ) return i
            i++
        }
        return null
    }

    fun findSingleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n) {
            if (chars[i] == '\\' && i + 1 < n) { i += 2; continue }
            if (chars[i] == '$' && (i == 0 || chars[i - 1] != ' ') &&
                (codeMask == null || !codeMask[i])
            ) return i
            if (chars[i] == '\n') return null
            i++
        }
        return null
    }

    /**
     * Currency-skip heuristic: $5 / $10.99 should stay as plain text. Treat
     * the `$ … $` content as math only if it has a math-ish character
     * (`\\^_{}` / common big-symbol unicode) or is longer than 2 chars (so
     * `x+y` qualifies but bare digits like `5` do not). Mirrors iOS.
     */
    fun looksLikeMath(content: String): Boolean {
        if (content.isEmpty()) return false
        // T208 Layer B: previous heuristic rejected single-letter math like
        // `$c$` or `$i$` — `length > 2` returned false and the literal
        // dollar-wrapped text leaked into the rendered output. Any of:
        //   - obvious LaTeX glyphs (`\`, `^`, `_`, `{`, `}`, integrals…)
        //   - short alphanumeric content with no whitespace and no leading
        //     digit (currency like `$5` filtered out by the leading-digit
        //     check; `$5.99` further filtered by the dot rule below)
        // qualifies as math. Mirrors iOS MarkdownMathExtractor.looksLikeMath.
        val mathChars = charArrayOf('\\', '^', '_', '{', '}', '∫', '∑', '∏', '√', '+', '-', '=', '<', '>')
        for (c in content) {
            if (c in mathChars) return true
        }
        // Currency heuristic: `$5`, `$5.99`, `$1,000` look numeric. Skip.
        if (content[0].isDigit()) return false
        // Whitespace at either edge usually means a stray dollar, not math.
        if (content.first().isWhitespace() || content.last().isWhitespace()) return false
        // Short ASCII identifier-ish content (`$c$`, `$x$`, `$pi$`) up to
        // 30 chars is almost certainly a variable reference.
        if (content.length <= 30 && content.all { it.isLetterOrDigit() }) return true
        return content.length > 2
    }

}
