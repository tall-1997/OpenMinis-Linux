package com.openminis.app.ui.chat

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

internal fun parseInline(text: String, colors: MdColors): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // [T-latex-inline] `\( … \)` inline math must be matched BEFORE the
                // generic `\`-escape branch below — otherwise `\(` is consumed as
                // an escaped `(` and the math delimiter never fires (latent dead
                // code before this reorder). `\[ … \]` display math stays block-
                // level; here we only handle the inline `\( … \)` form.
                text.startsWith("\\(", i) -> {
                    val end = text.indexOf("\\)", i + 2)
                    if (end != -1) {
                        appendInlineContent(katexInlineTagFor(text.substring(i + 2, end)), text.substring(i + 2, end))
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Escape: \* \_ \` etc.
                text[i] == '\\' && i + 1 < text.length -> {
                    append(text[i + 1]); i += 2
                }
                // Image: ![alt](url) — [alt] link fallback. Only reachable for
                // paragraphs the splitter guard rejected (over the ICU window);
                // normal inline images are promoted to MdBlock.Image blocks at
                // parse time (splitParagraphOnInlineMedia).
                text.startsWith("![", i) -> {
                    val cb = text.indexOf(']', i + 2)
                    if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                        val cp = text.indexOf(')', cb + 2)
                        if (cp != -1) {
                            val alt = text.substring(i + 2, cb).ifEmpty { "image" }
                            withStyle(SpanStyle(color = colors.link)) { append("[$alt]") }
                            i = cp + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                // Bold + italic: ***text***
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            appendRecursive(text.substring(i + 3, end), colors)
                        }
                        i = end + 3
                    } else { append(text[i]); i++ }
                }
                // Bold: **text** or __text__
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Strikethrough: ~~text~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                // Triple backtick (fenced code fence leaked into inline) — skip as literal
                text.startsWith("```", i) -> {
                    append("```"); i += 3
                }
                // T155: inline math $ ... $ (skip $$ which is display-math, handled at block level)
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
                // Inline code: `text`
                text[i] == '`' -> {
                    val end = findInlineCodeClose(text, i + 1)
                    if (end != -1) {
                        val codeStyle = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = colors.inlineCodeText,
                        )
                        withStyle(codeStyle) { append("\u2006") }
                        // Annotation excludes the U+2006 pads on either side.
                        // Compose treats U+2006 as a break opportunity, so on
                        // wrap the leading pad sits at the prior line's tail \u2014
                        // including it in the annotation made drawBehind paint
                        // background back onto that prior line (T223).
                        val annStart = length
                        withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                        val annEnd = length
                        withStyle(codeStyle) { append("\u2006") }
                        addStringAnnotation("inline_code", "", annStart, annEnd)
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                // Link: [text](url)
                text[i] == '[' && !text.startsWith("![", i - 1.coerceAtLeast(0)) -> {
                    val cb = text.indexOf(']', i + 1)
                    if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                        val cp = text.indexOf(')', cb + 2)
                        if (cp != -1) {
                            val url = text.substring(cb + 2, cp).trim()
                            val linkStart = length
                            withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) {
                                append(text.substring(i + 1, cb))
                            }
                            addStringAnnotation("url", url, linkStart, length)
                            i = cp + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                // [T-chatlink-bare-url-autolink] Bare URL autolink: `minis://…`,
                // `https://…`, `http://…` appearing as plain text (no [text](url)
                // wrapper). Field evidence (screenshot 368.jpg): GLM-5.3 emitted
                // the file link as `**minis://attachments/uploads/susu…txt**` —
                // the bold branch consumed it, painted bold dark text with NO
                // "url" annotation, and the tap died before ChatLinkResolver ever
                // ran. The same text in `[label](url)` form always worked, which
                // is why older chats looked fine. GFM autolink semantics, adapted:
                // the URL run starts at a known scheme and ends at the first
                // whitespace / CJK punctuation / closing bracket that is not part
                // of balanced parens. Matched AFTER code/bold so inline code and
                // emphasis keep their own styling; inside bold it still links
                // (appendRecursive re-enters this parser with the emphasis
                // stripped, so **<url>** renders a bold link — the field case).
                text.startsWith("minis://", i) || text.startsWith("https://", i) || text.startsWith("http://", i) -> {
                    val end = bareUrlEnd(text, i)
                    if (end > i) {
                        val url = text.substring(i, end)
                        val linkStart = length
                        withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) {
                            append(url)
                        }
                        addStringAnnotation("url", url, linkStart, length)
                        i = end
                    } else { append(text[i]); i++ }
                }
                // Italic: *text* or _text_ (single delimiter, not followed by same)
                (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != text[i] && text[i + 1] != ' ' -> {
                    val delim = text[i]
                    val end = text.indexOf(delim, i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendRecursive(text.substring(i + 1, end), colors)
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                // Line break: two trailing spaces or \n
                text[i] == '\n' -> {
                    append('\n'); i++
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

/**
 * [T-chatlink-bare-url-autolink] End offset of a bare URL run starting at
 * [start] (which is verified to begin with a scheme by the caller). GFM-style
 * termination: whitespace ends the run; a `)` only ends it when the URL's
 * paren count is balanced (so Wikipedia-style `/Path_(disambiguation)` stays
 * intact); CJK full-width punctuation and common trailing markdown punctuation
 * (`。，；：、！？）」』"'"…`, `.,;:!?]}`) are trimmed off the tail after the
 * scan, so a sentence like "见 https://x/a.txt。" links only the URL.
 */
internal fun bareUrlEnd(text: String, start: Int): Int {
    var i = start
    var parenDepth = 0
    var lastSafe = start
    while (i < text.length) {
        val c = text[i]
        when {
            // Whitespace and CJK full-width space end the run: a URL never
            // contains them, and GLM regularly follows one with "。然后…" on
            // the same line.
            c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000' -> break
            c == '(' -> { parenDepth++; i++ }
            c == ')' ->
                if (parenDepth > 0) { parenDepth--; i++ } else break
            // Any other non-ASCII character outside the URL-safe set ends the
            // run: CJK full-width punctuation terminates, but CJK ideographs /
            // letters continue (relay filenames are commonly Chinese).
            c.code >= 0x80 && !isUrlSafeNonAscii(c) -> break
            else -> i++
        }
        lastSafe = i
    }
    // Trim ASCII trailing punctuation that grammatically closes a sentence
    // (the CJK cases were already excluded by the non-ASCII gate above).
    val trailing = ".,;:!?]}>\"'"
    var end = lastSafe
    while (end > start && text[end - 1] in trailing) end--
    return end
}

/** Non-ASCII characters that may legitimately appear inside a URL path. */
private fun isUrlSafeNonAscii(c: Char): Boolean = !isCjkPunct(c)

/** CJK full-width punctuation that grammatically terminates a URL run. */
private fun isCjkPunct(c: Char): Boolean = when (c) {
    '。', '，', '；', '：', '、', '！', '？', '（', '）', '「', '」', '『', '』',
    '《', '》', '“', '”', '‘', '’', '…', '～', '—', '–', '·', '\u3000',
    '【', '】', '〔', '〕', '〈', '〉',
    -> true
    else -> false
}

