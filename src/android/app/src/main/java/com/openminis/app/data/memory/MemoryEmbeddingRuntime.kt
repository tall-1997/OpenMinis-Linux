package com.openminis.app.data.memory

import java.io.File

/**
 * [T-memory-embedding-recall] Process-wide holder for the #27 semantic-memory
 * runtime. Deliberately free of Android imports: the composition root (AppGraph)
 * builds the indexer from prefs + Room + app scopes and attaches it here;
 * everything downstream reads through the two nullable accessors.
 *
 * Default state is `null` everywhere — the feature is OFF until
 * `EmbeddingPrefs` is flipped on AND the process restarts (attach happens in
 * Application.onCreate; the snapshot is read once). A null runtime costs one
 * volatile read per write hook and nothing on the recall path: the engine
 * constructed with a null [MemoryEmbeddingSupport] runs the keyword FTS code
 * path byte-identically to the pre-#27 engine.
 */
object MemoryEmbeddingRuntime {

    @Volatile
    private var indexer: MemoryEmbeddingIndexer? = null

    /** Called once from the composition root. No-op when prefs are off. */
    fun attach(newIndexer: MemoryEmbeddingIndexer?) {
        indexer = newIndexer
    }

    /** Test seam. */
    fun detach() {
        indexer = null
    }

    /**
     * The write-side hook, or null when the feature is off. Memory write
     * paths call `indexerOrNull()?.indexFileAsync(file)` — fire-and-forget,
     * silent on absence.
     */
    fun indexerOrNull(): MemoryEmbeddingIndexer? = indexer

    /**
     * The recall-side seam for the engine, or null when the feature is off.
     */
    fun recallSupportOrNull(): MemoryEmbeddingSupport? = indexer?.let { idx ->
        object : MemoryEmbeddingSupport {
            override suspend fun semanticHits(query: String, limit: Int): List<MemoryEmbeddingSupport.SemanticHit> =
                idx.semanticHits(query, limit)

            override fun backfillAsync(dirs: List<File>) = idx.backfillAsync(dirs)
        }
    }
}
