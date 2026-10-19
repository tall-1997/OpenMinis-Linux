package com.openminis.app.backup

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.util.IsoTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * [T-android-backup-record-json] One database row -> one JSONL record.
 *
 * These builders live apart from [BackupExporter] because they answer a
 * different question: the exporter decides WHAT to write (categories, blobs,
 * the manifest), while each function here owns the wire shape of one record —
 * the part that has to match iOS byte for byte. Keeping the field lists in one
 * place makes that contract reviewable on its own, and keeps table-by-table
 * field lists from drowning the staging logic.
 *
 * Field names are the cross-platform contract (design §2.2): iOS writes its
 * Swift models through the synthesized Codable encoder, so records are
 * camelCase with ISO-8601 dates. The manifest's snake_case (§2.1) applies to
 * the manifest alone — deviating in either direction makes packages mutually
 * unreadable, which §5.4 forbids.
 *
 * Called by [BackupExporter] only; `internal` rather than file-private so the
 * export path can reach them from its own file.
 */
internal object BackupRecordJson {

    /**
     * The session row as iOS writes it.
     *
     * Field names and date format follow iOS's `ChatSession` under the
     * synthesized Codable encoder (camelCase, ISO-8601), plus the two extras
     * iOS keeps alongside it in `SessionRecord`. Android-only columns with no
     * iOS counterpart (`editCount`, `thinkingOverride`) are carried too: §2.2
     * rule 4 says platform-specific data is preserved and ignored by the other
     * side, which is strictly better than dropping a user's per-session
     * thinking override on a same-platform restore.
     *
     * Device-local fields are deliberately absent — see [message].
     */
    fun session(s: ChatSessionEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(s.id))
        put("title", s.title?.let(::JsonPrimitive) ?: JsonNull)
        put("category", s.category?.let(::JsonPrimitive) ?: JsonNull)
        put("modelId", JsonPrimitive(s.modelId))
        put("createdAt", JsonPrimitive(iso8601(s.createdAt)))
        put("updatedAt", JsonPrimitive(iso8601(s.updatedAt)))
        put("lastMessage", s.lastMessage?.let(::JsonPrimitive) ?: JsonNull)
        put("source", s.source?.let(::JsonPrimitive) ?: JsonNull)
        put("pinnedAt", s.pinnedAt?.let { JsonPrimitive(iso8601(it)) } ?: JsonNull)
        put("folderId", s.folderId?.let(::JsonPrimitive) ?: JsonNull)
        // iOS's SessionRecord wrapper fields.
        put("memoryEnabled", JsonPrimitive(s.memoryEnabled != 0))
        put("modelBinding", s.modelBinding?.let(::JsonPrimitive) ?: JsonNull)
        // Android-only, preserved per §2.2 rule 4.
        put("editCount", JsonPrimitive(s.editCount))
        put("thinkingOverride", s.thinkingOverride?.let(::JsonPrimitive) ?: JsonNull)
    }

    /**
     * The message row as iOS writes it.
     *
     * `parts` is spliced in as pre-parsed JSON rather than re-encoded: the
     * column already holds the exact `[ContentPart]` array iOS's ContentPart
     * encoder produces (`{"type":…,"value":…}`), so passing it through keeps
     * MediaRef paths and tool payloads byte-identical. Re-encoding through a
     * Kotlin model would risk reordering or dropping a field the Android model
     * doesn't know about.
     *
     * `errorInfo` is NOT written. §0.2 lists it with `part_flags` as a
     * device-local field the portable record excludes: an error sticker for a
     * failed turn on the old device is meaningless on the new one, and
     * restoring it would resurrect a red error badge against a message that
     * never failed for this install.
     */
    fun message(m: MessageEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(m.id))
        put("sessionId", JsonPrimitive(m.sessionId))
        put("role", JsonPrimitive(m.role))
        put("parts", parseParts(m.partsJson))
        put("createdAt", JsonPrimitive(iso8601(m.createdAt)))
        put("updatedAt", JsonPrimitive(iso8601(m.updatedAt ?: m.createdAt)))
        put("tokenUsage", m.tokenUsage?.let { parseJsonOrNull(it) } ?: JsonNull)
        put("reasoningContent", m.reasoningContent?.let(::JsonPrimitive) ?: JsonNull)
        put("streamInterruptCount", JsonPrimitive(m.streamInterruptCount))
        put("sortOrder", JsonPrimitive(m.sortOrder))
        put("bodyBytes", JsonPrimitive(m.bodyBytes))
        m.bodyRef?.let { put("bodyRef", JsonPrimitive(it)) }
        m.bodySha?.let { put("bodySha", JsonPrimitive(it)) }
        // [T-token-attribution-snapshot] Per-message model attribution. Emitted
        // only when present, so a package from a device with no snapshots keeps
        // its previous shape and older importers see nothing new.
        //
        // camelCase, NOT snake_case: iOS serializes `RawMessage` straight
        // through Codable with no CodingKeys, so its wire keys are the Swift
        // property names — the rest of this record already matches that
        // (`tokenUsage`, `streamInterruptCount`, `sortOrder`). A snake_case key
        // here would simply not decode on iOS, which is the same silent
        // cross-platform drop we hit with the env-var `createdAt` format.
        m.modelId?.let { put("modelId", JsonPrimitive(it)) }
        m.modelDisplayName?.let { put("modelDisplayName", JsonPrimitive(it)) }
        m.providerType?.let { put("providerType", JsonPrimitive(it)) }
        m.providerInstanceId?.let { put("providerInstanceId", JsonPrimitive(it)) }
    }

    fun marker(c: CompactMarkerEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(c.id))
        put("sessionId", JsonPrimitive(c.sessionId))
        put("summary", JsonPrimitive(c.summary))
        put("firstKeptSortOrder", JsonPrimitive(c.firstKeptSortOrder))
        put("compactedCount", JsonPrimitive(c.compactedCount))
        put("createdAt", JsonPrimitive(iso8601(c.createdAt)))
        put("uiBoundarySortOrder", c.uiBoundarySortOrder?.let(::JsonPrimitive) ?: JsonNull)
        put("boundaryMessageId", c.boundaryMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("firstKeptMessageId", c.firstKeptMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("lastCompactedMessageId", c.lastCompactedMessageId?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun folder(f: FolderEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(f.id))
        put("name", JsonPrimitive(f.name))
        put("icon", f.icon?.let(::JsonPrimitive) ?: JsonNull)
        put("color", f.color?.let(::JsonPrimitive) ?: JsonNull)
        put("origin", JsonPrimitive(f.origin))
        put("sortIndex", JsonPrimitive(f.sortIndex))
        put("pinnedAt", f.pinnedAt?.let { JsonPrimitive(iso8601(it)) } ?: JsonNull)
        put("description", f.description?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAt", JsonPrimitive(iso8601(f.createdAt)))
        // Record edits only (rename / pin / icon) — a session moving in or out
        // of the group must not touch it, so this is a stable merge key.
        put("updatedAt", JsonPrimitive(iso8601(f.updatedAt)))
    }

    private fun iso8601(millis: Long): String = IsoTime.formatUtcSeconds(millis)

    private fun parseParts(partsJson: String): JsonElement =
        parseJsonOrNull(partsJson) ?: BackupFormat.json.parseToJsonElement("[]")

    private fun parseJsonOrNull(raw: String): JsonElement? =
        runCatching { BackupFormat.json.parseToJsonElement(raw) }.getOrNull()
}
