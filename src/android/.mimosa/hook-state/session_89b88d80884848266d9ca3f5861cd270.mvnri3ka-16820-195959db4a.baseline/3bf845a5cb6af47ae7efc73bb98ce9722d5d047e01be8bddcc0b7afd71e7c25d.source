package com.openminis.app.ui.chat

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** Recursively parse inline markdown within a styled span. */
internal fun AnnotatedString.Builder.appendRecursive(text: String, colors: MdColors) {
    var i = 0
    while (i < text.length) {
        when {
            // [T-latex-inline] Inline math must be handled INSIDE emphasis too —
            // `**$\approx$**`, `*$x$*`, `~~$a$~~` all appear in AI replies. Without
            // these branches emphasis content fell through to the literal `else`
            // and the `$…$` / `\(…\)` rendered as raw dollar text. The KaTeX
            // InlineTextContent slots are pre-registered by collectInlineMathLatex
            // (which scans the whole raw line, emphasis markers included), so
            // emitting the tag here resolves to the same rendered math. Placed
            // before the `\` escape branch so `\(` is treated as math, not an
            // escaped `(`.
            text.startsWith("\\(", i) -> {
                val end = text.indexOf("\\)", i + 2)
                if (end != -1) {
                    appendInlineContent(katexInlineTagFor(text.substring(i + 2, end)), text.substring(i + 2, end))
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ' -> {
                val end = findInlineMathClose(text, i + 1)
                if (end != -1) {
                    val latex = text.substring(i + 1, end)
                    if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                        appendInlineContent(katexInlineTagFor(latex), latex)
                        i = end + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            text[i] == '\\' && i + 1 < text.length -> { append(text[i + 1]); i += 2 }
            text.startsWith("```", i) -> { append("```"); i += 3 }
            text[i] == '`' -> {
                val end = findInlineCodeClose(text, i + 1)
                if (end != -1) {
                    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = colors.inlineCodeText)
                    withStyle(codeStyle) { append("\u2006") }
                    // See T223 in the top-level inline-code branch \u2014 annotation
                    // excludes the U+2006 pads to keep wrap-line background
                    // from overshooting onto the prior line.
                    val annStart = length
                    withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                    val annEnd = length
                    withStyle(codeStyle) { append("\u2006") }
                    addStringAnnotation("inline_code", "", annStart, annEnd)
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val cb = text.indexOf(']', i + 1)
                if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                    val cp = text.indexOf(')', cb + 2)
                    if (cp != -1) {
                        val url = text.substring(cb + 2, cp).trim()
                        val linkStart = length
                        withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) { append(text.substring(i + 1, cb)) }
                        addStringAnnotation("url", url, linkStart, length)
                        i = cp + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

