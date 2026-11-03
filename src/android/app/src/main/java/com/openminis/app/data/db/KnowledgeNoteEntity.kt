package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [T-m8-knowledge-workbench] User-visible long-term note in the knowledge
 * workbench. Status lifecycle: active -> trashed -> purged. `links_json`
 * stores the wiki-link targets parsed from the body (graph edges);
 * `search_text` is the folded LIKE-searchable text (title + body, capped);
 * `dedupe_hash` is the import dedupe key ([KnowledgeNoteGraph.dedupeHash]),
 * scoped to active rows only so a trashed note never blocks re-importing the
 * same content after the trash is purged.
 */
@Entity(
    tableName = "knowledge_notes",
    indices = [
        Index(value = ["status", "updated_at"]),
        Index(value = ["dedupe_hash"]),
    ],
)
data class KnowledgeNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "body_md") val bodyMd: String,
    /** `active` or `trashed`. */
    val status: String,
    /** JSON array of tags; chips filtering reads it via LIKE. */
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    /** JSON array of wiki-link target titles parsed from [bodyMd]. */
    @ColumnInfo(name = "links_json") val linksJson: String,
    @ColumnInfo(name = "search_text") val searchText: String,
    /** Import dedupe key: sha256(title + "\n" + body) first 12 hex chars. */
    @ColumnInfo(name = "dedupe_hash") val dedupeHash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 0 while active; epoch ms of the trash action otherwise. */
    @ColumnInfo(name = "trashed_at") val trashedAt: Long,
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_TRASHED = "trashed"
    }
}
