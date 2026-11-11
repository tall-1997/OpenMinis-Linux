package com.openminis.app.data.memory

/**
 * [T-memory-embedding-recall] The seam [com.openminis.app.data.repository.
 * MemoryRecallEngine] uses for semantic recall (#27). It isolates the engine
 * from Room, the provider and the prefs: the engine only ever sees this
 * interface, and a null support means "feature off" — the default, in which
 * case recall is byte-identical to the pre-#27 keyword-only engine.
 *
 * Implementations must tolerate being called from anywhere; all heavy work
 * (HTTP, Room I/O) happens on their own dispatchers.
 */
interface MemoryEmbeddingSupport {

    class SemanticHit(
        /** Memory source file name, e.g. `2026-11-12.md` — same identity as a keyword hit's `source`. */
        val source: String,
        /** The stored line, trimmed — same identity as a keyword hit's `line`. */
        val line: String,
        /** Cosine similarity in [-1, 1]. */
        val score: Float,
    )

    /**
     * Cosine top-K of the stored memory vectors against [query]. Returns an
     * empty list (never throws) when the provider is unavailable, the query
     * cannot be embedded, or nothing is indexed yet.
     */
    suspend fun semanticHits(query: String, limit: Int): List<SemanticHit>

    /**
     * Fire-and-forget backfill: vectorize the memory lines under [dirs] that
     * have no vector yet (or were written under a different model). The
     * caller passes its own recall scopes (session diary + shared dir), so a
     * backfill only ever touches what this engine recalls. Must be silent on
     * every failure — "下次检索触发补算" — and must no-op when the provider
     * is unavailable.
     */
    fun backfillAsync(dirs: List<java.io.File>)
}
