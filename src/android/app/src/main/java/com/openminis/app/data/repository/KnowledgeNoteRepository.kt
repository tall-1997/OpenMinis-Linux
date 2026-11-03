package com.openminis.app.data.repository

import com.openminis.app.data.db.KnowledgeNoteDao
import com.openminis.app.data.db.KnowledgeNoteEntity
import com.openminis.app.data.db.KnowledgeNoteVersionDao
import com.openminis.app.data.db.KnowledgeNoteVersionEntity
import com.openminis.app.knowledge.KnowledgeNoteGraph
import com.openminis.app.knowledge.KnowledgeNoteTransfer
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * [T-m8-knowledge-workbench] CRUD + version archive + import/export for the
 * knowledge workbench. Derived columns (tags_json / links_json / search_text /
 * dedupe_hash) are always computed here from the user-visible fields, never
 * taken from callers, so the DB can never hold a stale derived value.
 */
class KnowledgeNoteRepository(
    private val noteDao: KnowledgeNoteDao,
    private val versionDao: KnowledgeNoteVersionDao,
) {

    companion object {
        /** Trashed notes older than this are purged by [purgeTrashedBefore]. */
        const val TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    }

    private val json = Json { encodeDefaults = false }

    suspend fun createNote(title: String, bodyMd: String, tags: List<String>, nowMs: Long): KnowledgeNoteItem {
        val cleanTitle = title.trim()
        val cleanBody = bodyMd.trim()
        val entity = KnowledgeNoteEntity(
            id = UUID.randomUUID().toString(),
            title = cleanTitle,
            bodyMd = cleanBody,
            status = KnowledgeNoteEntity.STATUS_ACTIVE,
            tagsJson = encodeStrings(tags.distinct().sorted()),
            linksJson = encodeStrings(KnowledgeNoteGraph.parseWikiLinks(cleanBody).distinct().sorted()),
            searchText = KnowledgeNoteGraph.buildSearchText(cleanTitle, cleanBody),
            dedupeHash = KnowledgeNoteGraph.dedupeHash(cleanTitle, cleanBody),
            createdAt = nowMs,
            updatedAt = nowMs,
            trashedAt = 0L,
        )
        noteDao.upsert(entity)
        return KnowledgeNoteItem.from(entity)
    }

    /** Returns the updated entity, or null when the note is gone (purged). */
    suspend fun updateNote(
        id: String,
        title: String,
        bodyMd: String,
        tags: List<String>,
        nowMs: Long,
    ): KnowledgeNoteItem? {
        val current = noteDao.byId(id) ?: return null
        if (current.status == KnowledgeNoteEntity.STATUS_TRASHED) return null
        val cleanTitle = title.trim()
        val cleanBody = bodyMd.trim()
        val updated = current.copy(
            title = cleanTitle,
            bodyMd = cleanBody,
            tagsJson = encodeStrings(tags.distinct().sorted()),
            linksJson = encodeStrings(KnowledgeNoteGraph.parseWikiLinks(cleanBody).distinct().sorted()),
            searchText = KnowledgeNoteGraph.buildSearchText(cleanTitle, cleanBody),
            dedupeHash = KnowledgeNoteGraph.dedupeHash(cleanTitle, cleanBody),
            updatedAt = nowMs,
        )
        noteDao.upsert(updated)
        versionDao.archive(
            KnowledgeNoteVersionEntity(
                noteId = id,
                version = versionDao.nextVersion(id),
                bodyAt = updated.updatedAt,
            )
        )
        versionDao.pruneOlderThanCap(id, KnowledgeNoteVersionEntity.MAX_VERSIONS)
        return KnowledgeNoteItem.from(updated)
    }

    suspend fun trashNote(id: String, nowMs: Long) {
        val current = noteDao.byId(id) ?: return
        if (current.status == KnowledgeNoteEntity.STATUS_TRASHED) return
        noteDao.upsert(current.copy(status = KnowledgeNoteEntity.STATUS_TRASHED, trashedAt = nowMs))
    }

    suspend fun restoreNote(id: String) {
        val current = noteDao.byId(id) ?: return
        if (current.status != KnowledgeNoteEntity.STATUS_TRASHED) return
        noteDao.upsert(current.copy(status = KnowledgeNoteEntity.STATUS_ACTIVE, trashedAt = 0L))
    }

    suspend fun purgeNote(id: String) {
        noteDao.purgeCascade(id)
    }

    suspend fun listActive(): List<KnowledgeNoteItem> =
        noteDao.activeNotes().map { KnowledgeNoteItem.from(it) }

    suspend fun listTrashed(): List<KnowledgeNoteItem> =
        noteDao.trashedNotes().map { KnowledgeNoteItem.from(it) }

    suspend fun search(query: String): List<KnowledgeNoteItem> =
        noteDao.searchActive("%${query.trim()}%").map { KnowledgeNoteItem.from(it) }

    suspend fun byTag(tag: String): List<KnowledgeNoteItem> =
        noteDao.byTag("%\"${tag.trim()}\"%").map { KnowledgeNoteItem.from(it) }

    suspend fun byId(id: String): KnowledgeNoteItem? =
        noteDao.byId(id)?.let { KnowledgeNoteItem.from(it) }

    suspend fun versions(noteId: String): List<KnowledgeNoteVersionItem> =
        versionDao.versionsFor(noteId).map { KnowledgeNoteVersionItem.from(it) }

    /** Deletes trashed notes older than [TRASH_RETENTION_MS]. Returns how many. */
    suspend fun purgeTrashedBefore(nowMs: Long): Int {
        val doomed = noteDao.trashedOlderThan(nowMs - TRASH_RETENTION_MS)
        doomed.forEach { note -> noteDao.purgeCascade(note.id) }
        return doomed.size
    }

    /**
     * Import one JSON Lines document (the [KnowledgeNoteTransfer] format).
     * - Blank lines and `#` comments are skipped.
     * - A line whose dedupe hash matches an existing note is skipped as a
     *   duplicate (created_at is preserved on re-import).
     * - Newer `updated_at` wins; a skipped duplicate is still an update when
     *   the incoming row is newer.
     * - Duplicate lines inside one document collapse onto the first row.
     */
    suspend fun importLines(raw: String, nowMs: Long): ImportReport {
        var imported = 0
        var updated = 0
        var duplicates = 0
        var malformed = 0
        val knownByHash = HashMap<String, KnowledgeNoteEntity>()
        noteDao.activeNotes().forEach { knownByHash[it.dedupeHash] = it }
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parsed = runCatching { KnowledgeNoteTransfer.parseLine(line) }.getOrNull()
            if (parsed == null) {
                malformed++
                return@forEach
            }
            val hash = KnowledgeNoteGraph.dedupeHash(parsed.title, parsed.body_md)
            val existing = knownByHash[hash]
            if (existing != null) {
                if (parsed.updated_at > existing.updatedAt) {
                    val newer = updateNote(existing.id, parsed.title, parsed.body_md, parsed.tags, parsed.updated_at)
                    if (newer != null) {
                        // knownByHash only feeds dedupe + freshness checks below;
                        // both fields are exact after the update.
                        knownByHash[hash] = existing.copy(updatedAt = parsed.updated_at)
                        updated++
                        updated++
                    } else {
                        duplicates++
                    }
                } else {
                    duplicates++
                }
            } else {
                val entity = KnowledgeNoteEntity(
                    id = parsed.id,
                    title = parsed.title,
                    bodyMd = parsed.body_md,
                    status = KnowledgeNoteEntity.STATUS_ACTIVE,
                    tagsJson = encodeStrings(parsed.tags.distinct().sorted()),
                    linksJson = encodeStrings(KnowledgeNoteGraph.parseWikiLinks(parsed.body_md).distinct().sorted()),
                    searchText = KnowledgeNoteGraph.buildSearchText(parsed.title, parsed.body_md),
                    dedupeHash = hash,
                    createdAt = parsed.created_at.takeIf { it > 0L } ?: nowMs,
                    updatedAt = parsed.updated_at.takeIf { it > 0L } ?: nowMs,
                    trashedAt = 0L,
                )
                noteDao.upsert(entity)
                knownByHash[hash] = entity
                imported++
            }
        }
        return ImportReport(imported = imported, updated = updated, duplicates = duplicates, malformed = malformed)
    }

    data class ImportReport(
        val imported: Int,
        val updated: Int,
        val duplicates: Int,
        val malformed: Int,
    )

    suspend fun exportLines(): String = KnowledgeNoteTransfer.exportLines(noteDao.allOrderedActive())

    private fun encodeStrings(values: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), values)
}
