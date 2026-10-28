package com.openminis.app.ui.sessions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * [T-android-preview-reminder-leak] Legacy rows written by the old preview
 * pipeline can hold a *blank* `last_message` (a reminder-only first text
 * part that cleaned to ""). `?:` only catches null, so a blank cell rendered
 * as an empty summary line on the session list. Normalizing blank → null
 * at the display edge keeps every consumer on the absent-path.
 *
 * Takes the raw string, not the entity, so this file stays free of the
 * data.db import the architecture policy forbids for new ui/ debt.
 */
internal fun lastMessageForDisplay(raw: String?): String? = raw?.ifBlank { null }

/**
 * Build an [AnnotatedString] that highlights every case-insensitive
 * occurrence of [query] inside [text] with the theme's tertiaryContainer
 * background. Blank query returns the plain string with no spans.
 *
 * Used by the home session-list search results to point the user at the
 * exact span that matched their query, in both the session title and the
 * extracted message snippet.
 */
@Composable
fun highlightedAnnotatedString(text: String, query: String): AnnotatedString {
    if (query.isBlank() || text.isEmpty()) return AnnotatedString(text)
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer
    val lower = text.lowercase()
    val q = query.lowercase()
    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            val match = lower.indexOf(q, idx)
            if (match < 0) {
                append(text.substring(idx))
                break
            }
            if (match > idx) append(text.substring(idx, match))
            withStyle(SpanStyle(background = highlightBg, color = highlightFg)) {
                append(text.substring(match, match + q.length))
            }
            idx = match + q.length
        }
    }
}
