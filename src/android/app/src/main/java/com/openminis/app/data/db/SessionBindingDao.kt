package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Session model-binding bookkeeping, extracted from [ChatDao] when that file
 * hit its architecture ceiling (same parent-interface pattern as
 * [MessageHeadDao] / [ChatTruncationDao]).
 */
@Dao
interface SessionBindingDao {
    /**
     * Drop session pins that name ANY model entry in [entryIds] — the batch
     * replacement ([Wave3-fix-14]) for the per-id [clearBindingReferencingSingle]
     * loop the provider delete path used to issue (one full-table `instr` scan
     * per removed entry id, straight off the config-lock critical path). The
     * whole batch runs in one transaction, and each statement matches any of up
     * to [CLEAR_BINDINGS_ARITY] ids via an OR chain of the same `instr`
     * needles, so a large removal costs ceil(N/16) scans instead of N.
     *
     * [T-android-binding-clear-anchor] The needle is the quoted `entryId` value
     * — NOT the bare id inside a `%…%` LIKE. Entry ids are `instance/model`, so
     * clearing `inst/gpt-4` must not un-pin sessions bound to `inst/gpt-4o`.
     * The binding column holds compact JSON written as
     * `{"type":"entry","entryId":"<id>"}` (ConfigBuiltins:210,
     * HeadlessChatRunner:107), so matching `"entryId":"<id>"` is exact. `instr`
     * rather than `LIKE` because the id itself is data: `_` and `%` are legal
     * in model ids and LIKE would read them as wildcards, which would re-open
     * the same over-match with a different spelling. NULL slots in the OR chain
     * are skipped by the `:idN IS NOT NULL` guard — a NULL never matches.
     */
    @Transaction
    suspend fun clearBindingsReferencing(
        entryIds: List<String>,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        if (entryIds.isEmpty()) return
        for (chunk in entryIds.chunked(CLEAR_BINDINGS_ARITY)) {
            clearBindingsReferencingChunk(
                id0 = chunk[0],
                id1 = chunk.getOrNull(1),
                id2 = chunk.getOrNull(2),
                id3 = chunk.getOrNull(3),
                id4 = chunk.getOrNull(4),
                id5 = chunk.getOrNull(5),
                id6 = chunk.getOrNull(6),
                id7 = chunk.getOrNull(7),
                id8 = chunk.getOrNull(8),
                id9 = chunk.getOrNull(9),
                id10 = chunk.getOrNull(10),
                id11 = chunk.getOrNull(11),
                id12 = chunk.getOrNull(12),
                id13 = chunk.getOrNull(13),
                id14 = chunk.getOrNull(14),
                id15 = chunk.getOrNull(15),
                updatedAt = updatedAt,
            )
        }
    }

    /**
     * Single-id variant kept for one-off callers; the batch entry point is
     * [clearBindingsReferencing]. Same needle semantics — see its KDoc.
     */
    @Query("UPDATE sessions SET model_binding = NULL, updated_at = :updatedAt WHERE instr(model_binding, '\"entryId\":\"' || :entryId || '\"') > 0")
    suspend fun clearBindingReferencingSingle(entryId: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE sessions SET model_binding = NULL, updated_at = :updatedAt
        WHERE instr(model_binding, '"entryId":"' || :id0 || '"') > 0
           OR (:id1 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id1 || '"') > 0)
           OR (:id2 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id2 || '"') > 0)
           OR (:id3 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id3 || '"') > 0)
           OR (:id4 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id4 || '"') > 0)
           OR (:id5 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id5 || '"') > 0)
           OR (:id6 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id6 || '"') > 0)
           OR (:id7 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id7 || '"') > 0)
           OR (:id8 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id8 || '"') > 0)
           OR (:id9 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id9 || '"') > 0)
           OR (:id10 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id10 || '"') > 0)
           OR (:id11 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id11 || '"') > 0)
           OR (:id12 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id12 || '"') > 0)
           OR (:id13 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id13 || '"') > 0)
           OR (:id14 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id14 || '"') > 0)
           OR (:id15 IS NOT NULL AND instr(model_binding, '"entryId":"' || :id15 || '"') > 0)
        """
    )
    suspend fun clearBindingsReferencingChunk(
        id0: String,
        id1: String?,
        id2: String?,
        id3: String?,
        id4: String?,
        id5: String?,
        id6: String?,
        id7: String?,
        id8: String?,
        id9: String?,
        id10: String?,
        id11: String?,
        id12: String?,
        id13: String?,
        id14: String?,
        id15: String?,
        updatedAt: Long,
    ): Int

    companion object {
        /** Id slots per [clearBindingsReferencingChunk] statement. */
        private const val CLEAR_BINDINGS_ARITY = 16
    }
}
