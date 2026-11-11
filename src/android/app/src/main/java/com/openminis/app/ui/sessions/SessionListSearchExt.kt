package com.openminis.app.ui.sessions

/**
 * [T-android-search-jump] Row projection of a chat session down to the two
 * fields the content-snippet search actually reads. Kept next to the snippet
 * pipeline so it never needs the full Room entity.
 */
internal data class SessionSearchRow(
    val id: String,
    val title: String?,
)

/**
 * [T-android-search-jump] One `searchDao().firstSnippetPerSession` row,
 * narrowed to the three fields the snippet builder reads.
 */
internal data class SessionSnippetRow(
    val id: String,
    val sessionId: String,
    val headText: String,
)

/**
 * [T-android-search-jump] Snippet + message anchor for every session whose
 * title does NOT contain the query (case-insensitive): scan its messages to
 * find the first hit in extracted text content and build a ~100-char snippet
 * around it. Sessions with no content hit are omitted — the row falls back to
 * its existing lastMessage preview.
 *
 * The matched row's id rides along as the anchor: `firstSnippetPerSession`
 * already selects it, so the anchor costs nothing here and saves ChatScreen
 * a re-query when the tap lands.
 */
internal data class ContentHits(
    val snippets: Map<String, String>,
    val anchors: Map<String, String>,
)

/**
 * Sessions whose title already matches the query don't need a content
 * snippet — prune them before the DAO walk.
 *
 * [T-#26-session-search-and] "Matches" now follows the same predicate the DAO
 * used to select the session: single-word queries keep the exact
 * whole-string `contains` check; whitespace queries prune a title only when it
 * contains EVERY word (mirroring `searchSessionsMulti`'s AND-of-words title
 * conjunct), so a title hit for `词1 词2` isn't pushed through the snippet
 * walk just because the words aren't adjacent in it. When no word survives
 * tokenization (all 1-char tokens) the DAO itself fell back to the whole
 * string, so the prune falls back with it.
 */
internal fun pruneSessionsNeedingSnippet(
    sessions: List<SessionSearchRow>,
    queryLower: String,
): List<SessionSearchRow> {
    val words = queryLower
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
    if (words.isEmpty()) {
        return sessions.filterNot { it.title.orEmpty().lowercase().contains(queryLower) }
    }
    return sessions.filterNot { session ->
        val title = session.title.orEmpty().lowercase()
        words.all { title.contains(it) }
    }
}

/** Pure rows → maps step of [ContentHits] building; no IO, JVM-testable. */
internal fun buildSnippetMaps(rows: List<SessionSnippetRow>): ContentHits {
    val snippets = HashMap<String, String>(rows.size)
    val anchors = HashMap<String, String>(rows.size)
    for (row in rows) {
        val body = row.headText.replace('\n', ' ').replace('\r', ' ')
        if (body.isBlank()) continue
        snippets[row.sessionId] = "…$body…"
        if (row.id.isNotBlank()) anchors[row.sessionId] = row.id
    }
    return ContentHits(snippets, anchors)
}
