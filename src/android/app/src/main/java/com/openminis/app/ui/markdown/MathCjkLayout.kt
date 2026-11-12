package com.openminis.app.ui.markdown

/**
 * [GH#189][GH#266][GH#276] Client-side LaTeX pre-pass for CJK text in formulas.
 *
 * KaTeX is a Latin math typesetter: its bundled `KaTeX_Main` font has no CJK
 * glyphs, so a raw CJK run in math mode falls back to whatever font the
 * WebView picks per glyph. Measured on device that produced two symptoms the
 * reporters saw: wrong metrics / visibly "downgraded" glyph style (#189), and
 * spanned constructs collapsing because the fallback glyph has no math advance
 * (#266) — e.g. `$中文 + x$` lost the spacing that keeps the formula legible.
 * The upstream fix (and the standard LaTeX answer) is to typeset CJK as text
 * rather than as math symbols: wrap every run of CJK characters in `\text{…}`.
 *
 * This is a pure, allocation-light pre-pass applied at the RENDER boundary
 * (KaTeXView / KatexWebViewPool), never to the stored span's LaTeX: copying a
 * formula or re-parsing the message must still yield exactly what the model
 * wrote.
 *
 * Deliberate limits:
 *  - CJK already inside an explicit `\text{…}` / `\textrm{…}` group is left
 *    alone (no `\text{\text{…}}` nesting).
 *  - A formula without CJK is returned as the SAME instance, so the common
 *    path pays one scan and no allocation.
 *  - Only characters are wrapped; the `\text` body escapes LaTeX specials so a
 *    malformed source can never break out of the group.
 *
 * [GH#276] is the third shape: a single-letter inline formula (`$x$`) must
 * still reach KaTeX. That is decided by the parser heuristics
 * (MarkdownParser.looksLikeMath / StreamingMarkdownText.looksLikeMath), both
 * of which explicitly accept a bare identifier — this pass must not "fix" the
 * problem by dropping short formulas.
 */
internal object MathCjkLayout {

    /** Commands whose brace group is already display text — never re-wrap. */
    private val TEXT_COMMANDS = setOf(
        "text", "textrm", "textnormal", "textup", "textsf", "texttt", "mbox", "hbox",
    )

    /** LaTeX specials that must be escaped inside the synthesized `\text{}`. */
    private val TEXT_SPECIALS = setOf('\\', '{', '}', '$', '&', '#', '%', '_', '^', '~')

    fun normalize(latex: String): String {
        if (latex.isEmpty()) return latex
        if (!containsCjk(latex)) return latex

        val out = StringBuilder(latex.length + 16)
        var i = 0
        var depth = 0
        // Brace depth at which the enclosing `\text{…}` group opened; -1 = none.
        var textDepth = -1
        // True right after a text command, before its opening brace arrives.
        var pendingTextGroup = false

        while (i < latex.length) {
            val c = latex[i]
            when {
                // Control sequence: copy verbatim, remember \text-family.
                c == '\\' && i + 1 < latex.length && latex[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < latex.length && latex[j].isLetter()) j++
                    val name = latex.substring(i + 1, j)
                    out.append(latex, i, j)
                    pendingTextGroup = name in TEXT_COMMANDS
                    i = j
                }
                // Single-char escape (`\{`, `\%`, `\\`, …): copy the pair.
                c == '\\' -> {
                    val end = (i + 2).coerceAtMost(latex.length)
                    out.append(latex, i, end)
                    pendingTextGroup = false
                    i = end
                }
                c == '{' -> {
                    depth++
                    if (pendingTextGroup && textDepth < 0) textDepth = depth
                    pendingTextGroup = false
                    out.append(c)
                    i++
                }
                c == '}' -> {
                    if (textDepth == depth) textDepth = -1
                    depth--
                    out.append(c)
                    i++
                }
                isCjk(c) -> {
                    if (textDepth >= 0) {
                        // Already inside a text group — leave the run in place.
                        out.append(c)
                        i++
                    } else {
                        val start = i
                        while (i < latex.length && isCjk(latex[i])) i++
                        out.append("\\text{")
                        appendEscaped(out, latex, start, i)
                        out.append('}')
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** True when [text] carries at least one CJK-range character. */
    fun containsCjk(text: String): Boolean = text.any { isCjk(it) }

    /**
     * CJK ideographs + the punctuation/kana/hangul blocks that share their
     * font-fallback problem. Fullwidth forms are included because fullwidth
     * brackets and commas are what models actually emit inside Chinese math.
     */
    fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x2E80..0x2EFF) ||   // CJK radicals supplement
            (code in 0x3000..0x303F) ||      // CJK symbols and punctuation
            (code in 0x3040..0x30FF) ||      // Hiragana + Katakana
            (code in 0x3100..0x312F) ||      // Bopomofo
            (code in 0x31C0..0x31EF) ||      // CJK strokes
            (code in 0x3400..0x4DBF) ||      // CJK ext A
            (code in 0x4E00..0x9FFF) ||      // CJK unified ideographs
            (code in 0xAC00..0xD7AF) ||      // Hangul syllables
            (code in 0xF900..0xFAFF) ||      // CJK compatibility ideographs
            (code in 0xFF00..0xFFEF)         // Halfwidth/fullwidth forms
    }

    private fun appendEscaped(out: StringBuilder, latex: String, from: Int, to: Int) {
        for (k in from until to) {
            val c = latex[k]
            if (c in TEXT_SPECIALS) out.append('\\')
            out.append(c)
        }
    }
}
