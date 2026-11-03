package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * [T-m8-knowledge-workbench] Archived snapshot of a knowledge note body,
 * capped at [MAX_VERSIONS] per note (oldest dropped on overflow). The FK
 * cascades so purging a trashed note reclaims its history too — versions are
 * meaningless once the note is gone.
 */
@Entity(
    tableName = "knowledge_note_versions",
    primaryKeys = ["note_id", "version"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class KnowledgeNoteVersionEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    /** 1-based per-note archive ordinal; latest = max. */
    val version: Int,
    /** Epoch ms of the archived body state (the note's updated_at at the time). */
    @ColumnInfo(name = "body_at") val bodyAt: Long,
) {
    companion object {
        const val MAX_VERSIONS = 30
    }
}
