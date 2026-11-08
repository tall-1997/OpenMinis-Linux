package com.openminis.app.cottranslate

/**
 * [T-cot-translate] Split reasoning into completed paragraphs and
 * request-sized fragments. Port of DSH `reasoningFragments` — whitespace
 * preserved verbatim so translated output can be spliced back without
 * losing the original layout.
 *
 * @param text original reasoning, including its exact whitespace.
 * @param running whether the final paragraph may still grow (streaming tail).
 * @param maxTextChars translator's UTF-16 request limit.
 * @return fragments; the LAST one has `complete = false` while [running].
 */
data class Fragment(val text: String, val suffix: String, val complete: Boolean)

fun reasoningFragments(text: String, running: Boolean, maxTextChars: Int = MAX_TEXT_CHARS): List<Fragment> {
    val result = mutableListOf<Fragment>()
    val separator = Regex("\r?\n(?:[\t ]*\r?\n)+")
    var start = 0
    while (true) {
        val match = separator.find(text, start)
        var remaining = text.substring(start, match?.range?.first ?: text.length)
        while (remaining.length > maxTextChars) {
            var cut = maxTextChars
            // Never split a UTF-16 surrogate pair.
            if (remaining[cut - 1].isHighSurrogate()) cut -= 1
            // Prefer a whitespace boundary so words are not cut in half.
            val whitespace = remaining.substring(0, cut).indexOfLast { it.isWhitespace() }
            if (whitespace > 0) cut = whitespace
            val source = remaining.substring(0, cut)
            val rest = remaining.substring(cut)
            val suffix = rest.takeWhile { it.isWhitespace() }
            remaining = rest.drop(suffix.length)
            result.add(Fragment(source, suffix, complete = true))
        }
        result.add(
            Fragment(
                text = remaining,
                suffix = match?.value ?: "",
                complete = match != null || !running,
            ),
        )
        if (match == null) return result
        start = match.range.last + 1
    }
}
