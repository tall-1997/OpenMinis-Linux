package com.openminis.app.knowledge

import com.openminis.app.data.db.KnowledgeNoteEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * [T-m8-knowledge-workbench] JSON Lines import/export for knowledge notes.
 * One note per line, all fields verbatim — the file is both the export format
 * and the import format, so a full export always round-trips.
 *
 * Line shape:
 * ```
 * {"id":"…","title":"…","body_md":"…","tags":["a"],"links":["b"],
 *  "created_at":1,"updated_at":2}
 * ```
 */
object KnowledgeNoteTransfer {

    @Serializable
    data class Line(
        val id: String,
        val title: String,
        val body_md: String,
        val tags: List<String> = emptyList(),
        val links: List<String> = emptyList(),
        val created_at: Long = 0L,
        val updated_at: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One JSON object per line, in stable (id) order for deterministic diffs. */
    fun exportLines(notes: List<KnowledgeNoteEntity>): String =
        notes.sortedBy { it.id }.joinToString("\n") { note ->
            json.encodeToString(
                Line.serializer(),
                Line(
                    id = note.id,
                    title = note.title,
                    body_md = note.bodyMd,
                    tags = parseTags(note.tagsJson),
                    links = parseLinks(note.linksJson),
                    created_at = note.createdAt,
                    updated_at = note.updatedAt,
                )
            )
        }

    /**
     * Parse one exported note. Returns null when the line is blank or a
     * comment (`# …`), throws when the JSON itself is malformed — the caller
     * counts both against its import report.
     */
    fun parseLine(raw: String): Line? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        return json.decodeFromString(Line.serializer(), trimmed)
    }

    /** Decode the tags_json column; empty list when the JSON is unreadable. */
    fun parseTags(tagsJson: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), tagsJson) }
            .getOrDefault(emptyList())

    /** Decode the links_json column; empty list when the JSON is unreadable. */
    fun parseLinks(linksJson: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), linksJson) }
            .getOrDefault(emptyList())
}
