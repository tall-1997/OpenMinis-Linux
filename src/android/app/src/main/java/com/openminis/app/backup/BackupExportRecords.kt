package com.openminis.app.backup

import com.openminis.app.backup.BackupExporter.Companion.iso8601
import com.openminis.app.data.CompactAssets
import com.openminis.app.util.IsoTime
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Wire-record builders for the `.minisbak` chat rows — the cross-platform
 * contract documented on [BackupExporter]: iOS Codable camelCase + ISO-8601,
 * reproduced exactly, plus the JSON parse helpers the message record needs.
 * Moved verbatim from [BackupExporter] (private members became internal
 * top-level functions) so BackupExporter stays inside its
 * architecture-policy line ceiling; pure move, same bodies, same [T-...] tags.
 */

/**
 * The session row as iOS writes it: camelCase + ISO-8601 keys; Android-only
 * columns preserved per §2.2 rule 4, device-local fields absent.
 */
internal fun sessionRecord(s: ChatSessionEntity): JsonElement = buildJsonObject {
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

internal fun messageRecord(m: MessageEntity): JsonElement = buildJsonObject {
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
    // [T-token-attribution-snapshot] Per-message model attribution,
    // camelCase to match iOS Codable's wire keys (snake_case would not
    // decode there).
    m.modelId?.let { put("modelId", JsonPrimitive(it)) }
    m.modelDisplayName?.let { put("modelDisplayName", JsonPrimitive(it)) }
    m.providerType?.let { put("providerType", JsonPrimitive(it)) }
    m.providerInstanceId?.let { put("providerInstanceId", JsonPrimitive(it)) }
}

internal fun markerRecord(c: CompactMarkerEntity): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(c.id))
    put("sessionId", JsonPrimitive(c.sessionId))
    put("summary", JsonPrimitive(c.summary))
    put("firstKeptSortOrder", JsonPrimitive(c.firstKeptSortOrder))
    put("compactedCount", JsonPrimitive(c.compactedCount))
    // Keep the iOS date field; Android also needs exact ordering for undo.
    put("createdAt", JsonPrimitive(iso8601(c.createdAt)))
    put("createdAtMillis", JsonPrimitive(c.createdAt))
    put("uiBoundarySortOrder", c.uiBoundarySortOrder?.let(::JsonPrimitive) ?: JsonNull)
    put("boundaryMessageId", c.boundaryMessageId?.let(::JsonPrimitive) ?: JsonNull)
    put("firstKeptMessageId", c.firstKeptMessageId?.let(::JsonPrimitive) ?: JsonNull)
    put("lastCompactedMessageId", c.lastCompactedMessageId?.let(::JsonPrimitive) ?: JsonNull)
    put("version", JsonPrimitive(c.version))
    c.summaryChunks?.let { put("summaryChunks", JsonPrimitive(it)) }
    CompactAssets.decode(c.assetsJson).encode()?.let { put("assetsJson", JsonPrimitive(it)) }
}

/** Missing or malformed Android-only assets never block an otherwise valid legacy marker. */
internal fun compactMarkerFromRecord(record: JsonObject): CompactMarkerEntity? {
    fun value(key: String) = (record[key] as? JsonPrimitive)?.contentOrNull
    val createdAt = value("createdAt")
    return CompactMarkerEntity(
        id = value("id") ?: return null,
        sessionId = value("sessionId") ?: return null,
        summary = value("summary").orEmpty(),
        firstKeptSortOrder = value("firstKeptSortOrder")?.toIntOrNull() ?: 0,
        compactedCount = value("compactedCount")?.toIntOrNull() ?: 0,
        createdAt = value("createdAtMillis")?.toLongOrNull()
            ?: createdAt?.let { it.toLongOrNull() ?: IsoTime.parseFlexible(it) } ?: 0L,
        uiBoundarySortOrder = value("uiBoundarySortOrder")?.toIntOrNull(),
        boundaryMessageId = value("boundaryMessageId"),
        firstKeptMessageId = value("firstKeptMessageId"),
        lastCompactedMessageId = value("lastCompactedMessageId"),
        // [P3-2] Only an explicit version is trusted. Pre-version packages
        // (iOS, older Android) had no `version` field; the v1 resolution path
        // already handles a lastCompactedMessageId-only row, so inferring v2
        // from its presence changed how the restored marker anchored.
        version = value("version")?.toIntOrNull() ?: 1,
        summaryChunks = value("summaryChunks"),
        assetsJson = CompactAssets.decode(value("assetsJson")).encode(),
    )
}

internal fun folderRecord(f: FolderEntity): JsonElement = buildJsonObject {
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

internal fun parseParts(partsJson: String): JsonElement =
    parseJsonOrNull(partsJson) ?: BackupFormat.json.parseToJsonElement("[]")

internal fun parseJsonOrNull(raw: String): JsonElement? =
    runCatching { BackupFormat.json.parseToJsonElement(raw) }.getOrNull()
