package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * [T-m8-knowledge-workbench] Note table access. Search follows the
 * [ChatSearchDao] pattern: LIKE over a folded [KnowledgeNoteEntity.searchText]
 * column — no FTS5 (its unicode61 tokenizer cannot match CJK substrings,
 * which is this app's primary search language).
 */
@Dao
interface KnowledgeNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: KnowledgeNoteEntity)

    @Query("SELECT * FROM knowledge_notes WHERE id = :id")
    suspend fun byId(id: String): KnowledgeNoteEntity?

    @Query("SELECT * FROM knowledge_notes WHERE status = 'active' ORDER BY updated_at DESC")
    suspend fun activeNotes(): List<KnowledgeNoteEntity>

    @Query("SELECT * FROM knowledge_notes WHERE status = 'trashed' ORDER BY trashed_at DESC")
    suspend fun trashedNotes(): List<KnowledgeNoteEntity>

    @Query("SELECT * FROM knowledge_notes WHERE status = 'active' AND dedupe_hash = :hash LIMIT 1")
    suspend fun activeByDedupeHash(hash: String): KnowledgeNoteEntity?

    @Query(
        "SELECT * FROM knowledge_notes WHERE status = 'active' AND search_text LIKE :pattern " +
            "ORDER BY updated_at DESC LIMIT 200"
    )
    suspend fun searchActive(pattern: String): List<KnowledgeNoteEntity>

    @Query(
        "SELECT * FROM knowledge_notes WHERE status = 'active' AND tags_json LIKE :pattern " +
            "ORDER BY updated_at DESC LIMIT 200"
    )
    suspend fun byTag(pattern: String): List<KnowledgeNoteEntity>

    @Query("DELETE FROM knowledge_notes WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Trashed notes whose retention window expired; purged by the repository. */
    @Query("SELECT * FROM knowledge_notes WHERE status = 'trashed' AND trashed_at < :cutoffMs")
    suspend fun trashedOlderThan(cutoffMs: Long): List<KnowledgeNoteEntity>

    /** Every active note, id-ordered, for deterministic JSON Lines export. */
    @Query("SELECT * FROM knowledge_notes WHERE status = 'active' ORDER BY id")
    suspend fun allOrderedActive(): List<KnowledgeNoteEntity>

    /** Cascade the note and its version archive in one transaction. */
    @Query("DELETE FROM knowledge_note_versions WHERE note_id = :id")
    suspend fun deleteVersionsOf(id: String)

    @Transaction
    suspend fun purgeCascade(id: String) {
        deleteById(id)
        deleteVersionsOf(id)
    }

    /** Every active note with non-empty links, for the graph page. */
    @Query("SELECT id, title, links_json FROM knowledge_notes WHERE status = 'active'")
    suspend fun graphRows(): List<KnowledgeNoteGraphRow>

    /** Lightweight projection consumed by [KnowledgeNoteGraph.edgesOf]. */
    data class KnowledgeNoteGraphRow(
        val id: String,
        val title: String,
        @ColumnInfo(name = "links_json") val linksJson: String,
    )
}
