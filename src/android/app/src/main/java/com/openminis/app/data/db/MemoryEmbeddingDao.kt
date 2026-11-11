package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * [T-memory-embedding-recall] Room access for `memory_embeddings` (#27).
 * Queries filter on `model` wherever vectors are READ: a cosine against a
 * vector from a different embedding model is noise, and a model switch must
 * trigger re-embedding (the indexer sees existing ids only under the CURRENT
 * model) rather than mixing geometries.
 *
 * In-variable safety: every caller of [findExisting] and [deleteByIds] must
 * chunk its id list ([IN_CHUNK]) — SQLite's default host-parameter budget is
 * 999 and a rewritten file could carry more lines than that.
 */
@Dao
interface MemoryEmbeddingDao {

    @Upsert
    suspend fun upsertAll(items: List<MemoryEmbeddingEntity>)

    /** Vectors usable for recall under [model], capped — the table is small. */
    @Query("SELECT * FROM memory_embeddings WHERE model = :model LIMIT :limit")
    suspend fun loadForModel(model: String, limit: Int): List<MemoryEmbeddingEntity>

    /**
     * Which of [ids] already carry a vector under [model] — everything else
     * is a backfill candidate ("失败静默，下次检索触发补算"). Chunk [ids].
     */
    @Query("SELECT memory_id FROM memory_embeddings WHERE memory_id IN (:ids) AND model = :model")
    suspend fun findExisting(ids: List<String>, model: String): List<String>

    /** Every id recorded for one source file, regardless of model. */
    @Query("SELECT memory_id FROM memory_embeddings WHERE scope = :scope AND source = :source")
    suspend fun listIdsForSource(scope: String, source: String): List<String>

    @Query("DELETE FROM memory_embeddings WHERE scope = :scope")
    suspend fun deleteForScope(scope: String)

    /** Chunked delete — callers must pass at most [IN_CHUNK] ids per call. */
    @Query("DELETE FROM memory_embeddings WHERE memory_id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM memory_embeddings")
    suspend fun count(): Int

    companion object {
        /** Max host parameters per IN(...) query. */
        const val IN_CHUNK = 500
    }
}
