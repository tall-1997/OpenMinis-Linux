package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * [T-m8-knowledge-workbench] Per-note body version archive. The cap of
 * [KnowledgeNoteVersionEntity.MAX_VERSIONS] is enforced by
 * [pruneOlderThanCap] right after every [archive]: newest 30 survive, the
 * rest are dropped in the same statement.
 */
@Dao
interface KnowledgeNoteVersionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun archive(version: KnowledgeNoteVersionEntity)

    /** Next ordinal for a note, starting at 1. */
    @Query("SELECT COALESCE(MAX(version), 0) + 1 FROM knowledge_note_versions WHERE note_id = :noteId")
    suspend fun nextVersion(noteId: String): Int

    @Query("SELECT * FROM knowledge_note_versions WHERE note_id = :noteId ORDER BY version DESC")
    suspend fun versionsFor(noteId: String): List<KnowledgeNoteVersionEntity>

    @Query("SELECT COUNT(*) FROM knowledge_note_versions WHERE note_id = :noteId")
    suspend fun countFor(noteId: String): Int

    /** One statement per version row, batched; must be in one transaction. */
    @Query("DELETE FROM knowledge_note_versions WHERE note_id = :noteId")
    suspend fun deleteForNote(noteId: String): Int

    /**
     * Keep only the newest [cap] rows for the note. Returns the number of
     * dropped rows, so the caller can report the trim. Callers pass
     * [KnowledgeNoteVersionEntity.MAX_VERSIONS].
     */
    @Query(
        "DELETE FROM knowledge_note_versions WHERE note_id = :noteId AND version NOT IN (" +
            "SELECT version FROM knowledge_note_versions WHERE note_id = :noteId " +
            "ORDER BY version DESC LIMIT :cap)"
    )
    suspend fun pruneOlderThanCap(noteId: String, cap: Int): Int
}
