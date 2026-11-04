package com.openminis.app.data.repository

/**
 * [T-memory-recall-multisource] In-memory index of the knowledge workbench, so
 * recall can cover more than the `memory/` markdown files.
 *
 * The workbench stores notes in Room, and recall runs inside prompt assembly
 * (synchronous, off the main thread) while Room queries are suspend. Rather than
 * blocking a coroutine inside prompt assembly, notes are mirrored here whenever
 * a session loads and the mirror is older than [TTL_MS]. The trade-off is
 * explicit: a note created and queried within the same TTL window may be one
 * refresh late — recall converges, it does not race.
 *
 * Pure search over a snapshot; no I/O, no Android types.
 */
object KnowledgeNoteIndex {

    /** How long a mirror is trusted before the next session load refreshes it. */
    const val TTL_MS = 5 * 60_000L

    data class NoteSource(
        val title: String,
        val text: String,
    )

    data class Hit(
        val title: String,
        /** Matched excerpt, already clipped for prompt use. */
        val excerpt: String,
        val score: Double,
    )

    /** Excerpt length; keeps one note from dominating the recall fragment. */
    internal const val EXCERPT_CHARS = 200

    @Volatile private var entries: List<NoteSource> = emptyList()
    @Volatile private var loadedAtMs: Long = 0L

    /** Replace the mirror. Cheap: callers pass the already-loaded active notes. */
    fun refresh(notes: List<Pair<String, String>>, nowMs: Long = System.currentTimeMillis()) {
        entries = notes
            .filter { it.first.isNotBlank() || it.second.isNotBlank() }
            .map { NoteSource(it.first, it.second) }
        loadedAtMs = nowMs
    }

    /** True when the mirror has never been filled or has aged past [TTL_MS]. */
    fun isStale(nowMs: Long = System.currentTimeMillis(), ttlMs: Long = TTL_MS): Boolean =
        loadedAtMs == 0L || nowMs - loadedAtMs >= ttlMs

    fun size(): Int = entries.size

    /**
     * Keyword search over the mirror. Title hits weigh double: a note whose title
     * matches is more likely to be what the user means than one that merely
     * mentions the word in a paragraph.
     */
    fun search(keywords: Set<String>, limit: Int = 4): List<Hit> {
        if (keywords.isEmpty() || entries.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>(entries.size)
        for (entry in entries) {
            var titleHits = 0
            var bodyHits = 0
            for (keyword in keywords) {
                if (entry.title.contains(keyword, ignoreCase = true)) titleHits += 1
                if (entry.text.contains(keyword, ignoreCase = true)) bodyHits += 1
            }
            val score = titleHits * 2.0 + bodyHits
            if (score <= 0.0) continue
            hits += Hit(entry.title, excerptFor(entry, keywords), score)
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    private fun excerptFor(entry: NoteSource, keywords: Set<String>): String {
        val body = entry.text.replace('\n', ' ').trim()
        if (body.length <= EXCERPT_CHARS) return body
        // Centre the excerpt on the first keyword occurrence so the snippet shows
        // why the note matched, not just its opening line.
        val at = keywords.mapNotNull { keyword ->
            body.indexOf(keyword, ignoreCase = true).takeIf { it >= 0 }
        }.minOrNull() ?: 0
        val start = (at - EXCERPT_CHARS / 3).coerceIn(0, (body.length - EXCERPT_CHARS).coerceAtLeast(0))
        return body.substring(start, (start + EXCERPT_CHARS).coerceAtMost(body.length)) + "…"
    }

    /** Test seam. */
    internal fun clearForTest() {
        entries = emptyList()
        loadedAtMs = 0L
    }
}
