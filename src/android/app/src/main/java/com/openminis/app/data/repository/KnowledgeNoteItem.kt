package com.openminis.app.data.repository

import com.openminis.app.data.db.KnowledgeNoteEntity
import com.openminis.app.data.db.KnowledgeNoteVersionEntity

/**
 * [T-m8-knowledge-workbench] UI-facing view of a knowledge note.
 * The UI and its ViewModel never touch Room entities directly (architecture
 * policy batch-6 item 21); the repository maps [KnowledgeNoteEntity] rows to
 * this DTO at the boundary, so derived-column shape changes stay inside the
 * data layer.
 */
data class KnowledgeNoteItem(
    val id: String,
    val title: String,
    val bodyMd: String,
    val status: String,
    val tagsJson: String,
    val linksJson: String,
    val searchText: String,
    val dedupeHash: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trashedAt: Long,
) {
    companion object {
        /** Mirrors [KnowledgeNoteEntity.STATUS_ACTIVE] for status checks in the UI. */
        const val STATUS_ACTIVE = "active"

        /** Mirrors [KnowledgeNoteEntity.STATUS_TRASHED] for status checks in the UI. */
        const val STATUS_TRASHED = "trashed"

        fun from(e: KnowledgeNoteEntity) = KnowledgeNoteItem(
            id = e.id,
            title = e.title,
            bodyMd = e.bodyMd,
            status = e.status,
            tagsJson = e.tagsJson,
            linksJson = e.linksJson,
            searchText = e.searchText,
            dedupeHash = e.dedupeHash,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            trashedAt = e.trashedAt,
        )
    }
}

/** [T-m8-knowledge-workbench] UI-facing view of one version-history row. */
data class KnowledgeNoteVersionItem(
    val noteId: String,
    val version: Int,
    val bodyAt: Long,
) {
    companion object {
        fun from(e: KnowledgeNoteVersionEntity) = KnowledgeNoteVersionItem(
            noteId = e.noteId,
            version = e.version,
            bodyAt = e.bodyAt,
        )
    }
}
