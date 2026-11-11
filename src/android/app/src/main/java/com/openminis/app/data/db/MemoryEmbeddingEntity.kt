package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [T-memory-embedding-recall] One embedded memory LINE (#27) — the recall
 * unit of [com.openminis.app.data.repository.MemoryRecallEngine], not the
 * whole diary file: memory is stored as daily `.md` logs whose lines are
 * what recall scores and injects, so the vector index mirrors that unit.
 *
 * Columns:
 *  - `memory_id` (PK): sha-256 hex of `"<scope>|<dayKey>|<trimmed line>"` —
 *    stable for an unchanged line, so re-indexing a file only re-embeds
 *    what actually changed. The day key matches the engine's recall-count
 *    key (`"2026-11-12"` or `"global"`).
 *  - `scope`: canonical host directory of the memory file (session diary vs
 *    `minis-global/memory`), so a session's vectors can be dropped with one
 *    DELETE when its tree is deleted.
 *  - `source` / `line`: the identity a [com.openminis.app.data.repository.
 *    MemoryRecallEngine.RecallHit] needs — recall reconstructs hits without
 *    re-reading the files.
 *  - `vector`: little-endian float blob ([com.openminis.app.data.memory.
 *    FloatVectorBlob]).
 *  - `model` / `dim`: which model produced the vector and its width —
 *    cosine against a query embedded by a different model is meaningless,
 *    so recall filters on `model` and re-indexing replaces rows whose model
 *    no longer matches.
 *  - `updated_at`: last (re)write time, for diagnostics.
 *
 * Pure additive table (v33); nothing in the app touches it unless the
 * embedding prefs switch is on (default OFF). Column names are snake_case to
 * match the hand-written MIGRATION_32_33 DDL and the project's SQL style.
 */
@Entity(tableName = "memory_embeddings")
class MemoryEmbeddingEntity(
    @PrimaryKey
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    @ColumnInfo(name = "scope")
    val scope: String,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "line")
    val line: String,
    @ColumnInfo(name = "vector")
    val vector: ByteArray,
    @ColumnInfo(name = "model")
    val model: String,
    @ColumnInfo(name = "dim")
    val dim: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    // ByteArray identity is value-based for Room's purposes via the PK; the
    // generated equals/hashCode would be reference-based, which nothing in
    // this codebase relies on. Lint's array-property warning is accepted.
    override fun equals(other: Any?): Boolean =
        other is MemoryEmbeddingEntity && other.memoryId == memoryId

    override fun hashCode(): Int = memoryId.hashCode()
}
