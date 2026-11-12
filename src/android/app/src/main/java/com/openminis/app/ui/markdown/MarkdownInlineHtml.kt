package com.openminis.app.ui.markdown

/**
 * [T-markdown-inline-html] Simple inline-HTML normalization, run before block
 * parsing (MarkdownParser.parseWithMath). Model output frequently carries
 * `<br>` and `<b>/<strong>/<i>/<em>/<code>` spans; the renderer has no
 * WebView, so the trivial subset maps onto the Markdown spans the inline pass
 * already handles and EVERYTHING else passes through verbatim — an unknown
 * tag must never swallow its content.
 *
 * Conversions are skipped inside fenced code blocks and inline code spans
 * (via MarkdownParser.buildCodeMask, the same mask extractMath uses) so code
 * samples keep their literal `<br>`. Table rows are also protected: a raw
 * newline inside a cell would split the row and break the table, so a cell
 * `<br>` degrades to a space instead.
 */
internal object MarkdownInlineHtml {

    private val htmlBrRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    // Optional-attribute form (e.g. <b class="x">): converting a bare closer
    // while its attributed opener passed through would bold the content yet
    // still print a literal tag, so known tags match with attributes.
    private val htmlInlineTagRegex = Regex(
        """</?\s*(?:b|strong|i|em|code)(?:\s[^>]*)?>""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Map the supported inline-HTML tags onto Markdown syntax; every other
     * input (unknown tags, already-Markdown text) comes back unchanged.
     */
    fun normalize(markdown: String): String {
        if (!markdown.contains('<')) return markdown
        val n = markdown.length
        val codeMask = MarkdownMathHelpers.buildCodeMask(markdown)
        // One flag per char: does its line look like a table row (leading
        // pipe after indent)? Only the <br>→newline conversion honours this —
        // span conversions are safe inside a cell.
        val tableRow = BooleanArray(n)
        var lineStart = 0
        for (idx in 0..n) {
            if (idx == n || markdown[idx] == '\n') {
                if (markdown.substring(lineStart, idx).trimStart().startsWith("|")) {
                    for (j in lineStart until idx) tableRow[j] = true
                }
                lineStart = idx + 1
            }
        }

        val out = StringBuilder(n)
        var i = 0
        while (i < n) {
            if (markdown[i] == '<' && !codeMask[i]) {
                val br = htmlBrRegex.matchAt(markdown, i)
                if (br != null) {
                    out.append(if (tableRow[i]) ' ' else '\n')
                    i = br.range.last + 1
                    continue
                }
                val tag = htmlInlineTagRegex.matchAt(markdown, i)
                if (tag != null) {
                    out.append(inlineTagReplacement(tag.value))
                    i = tag.range.last + 1
                    continue
                }
                // Unknown tag: fall through, copied verbatim below.
            }
            out.append(markdown[i])
            i++
        }
        return out.toString()
    }

    /**
     * `<b>`/`<strong>` → `**`, `<i>`/`<em>` → `*`, `<code>` → `` ` ``. The
     * markdown markers are symmetric, so open and close tags map to the same
     * replacement and pairing is left to the inline parser (an unpaired tag
     * degrades to literal asterisks/backtick, same as unpaired markdown).
     */
    private fun inlineTagReplacement(tag: String): String {
        val name = tag.trim('<', '>', '/', ' ').takeWhile { it.isLetter() }.lowercase()
        return when (name) {
            "b", "strong" -> "**"
            "i", "em" -> "*"
            "code" -> "`"
            else -> tag // unreachable today; keeps unknown names verbatim
        }
    }
}
