package com.openminis.app.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.openminis.app.agent.ToolResultProjection
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.display.DisplayParts
import com.openminis.app.tools.TOOL_RECEIPT_NOTICE
import com.openminis.app.tools.ToolOutputSpill
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONTokener

internal sealed interface HistoryScope {
    data object WholeSession : HistoryScope
    data object Unavailable : HistoryScope
    data class Folded(val beforeSortOrder: Long) : HistoryScope
}

/** Resolve the stored marker against this session only. Timestamps are not row order. */
internal suspend fun resolveHistoryScope(
    sessionId: String,
    marker: CompactMarkerEntity?,
    orderOf: suspend (String, String) -> Int?,
): HistoryScope {
    if (sessionId.isBlank()) return HistoryScope.Unavailable
    if (marker == null) return HistoryScope.WholeSession
    if (marker.sessionId != sessionId) return HistoryScope.Unavailable
    val firstKept = if (marker.version < 2) {
        marker.firstKeptMessageId?.takeIf(String::isNotBlank)
            ?: marker.boundaryMessageId?.takeIf(String::isNotBlank)
    } else null
    if (firstKept != null) {
        return orderOf(sessionId, firstKept)?.let { HistoryScope.Folded(it.toLong()) } ?: HistoryScope.Unavailable
    }
    val lastFolded = marker.lastCompactedMessageId?.takeIf(String::isNotBlank)
    if (lastFolded != null) {
        return orderOf(sessionId, lastFolded)?.let { HistoryScope.Folded(it.toLong() + 1) } ?: HistoryScope.Unavailable
    }
    // An ID that did not resolve does not justify a broader numeric fallback.
    return if (marker.version < 2 && marker.firstKeptSortOrder in 0 until Int.MAX_VALUE) {
        HistoryScope.Folded(marker.firstKeptSortOrder.toLong())
    } else HistoryScope.Unavailable
}

internal sealed interface HistoryBody {
    data class Text(val messageId: String, val role: String, val text: String, val previewOnly: Boolean) : HistoryBody
    data object NoText : HistoryBody
    data object Unavailable : HistoryBody
}

/** The selected columns are metadata only. No preview, reasoning, or body crosses the cursor here. */
private const val HISTORY_ROWS = """
    SELECT id, session_id, role, '' AS parts_json, created_at, NULL AS token_usage, sort_order,
        NULL AS reasoning_content, 0 AS stream_interrupt_count, updated_at, NULL AS error_info,
        NULL AS model_id, NULL AS model_display_name, NULL AS provider_type, NULL AS provider_instance_id,
        body_bytes, body_ref, body_sha, NULL AS preview
    FROM messages
    WHERE session_id = ? AND sort_order < ? AND role IN ('user', 'assistant', 'tool')
"""

/** An instance is bound to one session and one persisted boundary, never to the current UI window. */
internal class SessionHistory(private val repo: ChatRepository, private val sessionId: String) {
    suspend fun scope(): HistoryScope = resolveHistoryScope(sessionId, repo.dao.latestCompactMarker(sessionId), repo.dao::compactOrderForId)

    suspend fun rows(scope: HistoryScope, before: Long, role: String?, limit: Int): List<MessageEntity> {
        val bound = minOf(before, scope.beforeOrder() ?: return emptyList())
        val roleClause = when (role) {
            "assistant" -> " AND role = 'assistant'"
            "user" -> " AND role = 'user'"
            // Tool results may be stored as user-role in the provider transcript.
            "tool" -> " AND role IN ('user', 'tool')"
            else -> ""
        }
        return repo.dao.runMessagesQuery(SimpleSQLiteQuery(
            HISTORY_ROWS + roleClause + " ORDER BY sort_order DESC LIMIT ?",
            arrayOf<Any>(sessionId, bound, limit),
        )).takeWhile { it.sessionId == sessionId && it.sortOrder.toLong() < bound }
    }

    suspend fun row(scope: HistoryScope, id: String): MessageEntity? {
        val bound = scope.beforeOrder() ?: return null
        return repo.dao.runMessagesQuery(SimpleSQLiteQuery(
            HISTORY_ROWS + " AND id = ? LIMIT 1", arrayOf<Any>(sessionId, bound, id),
        )).singleOrNull()?.takeIf { it.id == id && it.sessionId == sessionId && it.sortOrder.toLong() < bound }
    }

    suspend fun body(
        scope: HistoryScope,
        row: MessageEntity,
        reserveBytes: (Long) -> Boolean = { true },
    ): HistoryBody {
        if (row.sessionId != sessionId) return HistoryBody.Unavailable
        val db = repo.db
        // Keep a legacy inline read consistent across multiple bounded cells. The
        // immutable offloaded body can be opened outside of the DB transaction.
        val verified = (if (db != null) db.withTransaction { source(scope, row, reserveBytes) }
            else source(scope, row, reserveBytes)) ?: return HistoryBody.Unavailable
        val ref = verified.row.bodyRef
        // This is the exact serialized message, not necessarily the exact tool
        // stdout: the output part may itself contain only a persisted preview.
        val raw = (if (ref != null) repo.bodyStore()?.readExact(ref, verified.row.bodyBytes.toInt()) else verified.inline)
            ?: return HistoryBody.Unavailable
        return decodeHistoryText(row.id, row.role, raw)
    }

    private data class BodySource(val row: MessageEntity, val inline: ByteArray? = null)

    private suspend fun source(
        scope: HistoryScope,
        expected: MessageEntity,
        reserveBytes: (Long) -> Boolean,
    ): BodySource? {
        if (scope != HistoryScope.WholeSession && this.scope() != scope) return null
        val row = row(scope, expected.id)?.takeIf { it == expected } ?: return null
        val ref = row.bodyRef
        val supportedSize = 1L..ResourceLimits.MAX_DECLARED_UNCOMPRESSED.toLong()
        if (ref != null) {
            if (row.bodyBytes !in supportedSize || (row.bodySha != null && row.bodySha != ref) || !reserveBytes(row.bodyBytes)) return null
            return BodySource(row)
        }
        val probe = repo.dao.compactInlineProbe(sessionId, row.id) ?: return null
        if (probe.size !in supportedSize || !reserveBytes(probe.size)) return null
        val inline = probe.parts ?: readInline(row.id, probe.size.toInt()) ?: return null
        if (inline.size.toLong() != probe.size) return null
        return BodySource(row, inline)
    }

    private suspend fun readInline(id: String, size: Int): ByteArray? {
        val body = ByteArray(size)
        var offset = 0
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(ResourceLimits.SQL_CELL_BYTES, size - offset)
            val chunk = repo.dao.compactInlineBodyChunk(sessionId, id, offset + 1, count) ?: return null
            if (chunk.size != count) return null
            chunk.copyInto(body, offset)
            offset += count
        }
        return body
    }
}

private fun HistoryScope.beforeOrder(): Long? = when (this) {
    HistoryScope.WholeSession -> Long.MAX_VALUE
    HistoryScope.Unavailable -> null
    is HistoryScope.Folded -> beforeSortOrder
}

private val HOST_FRAMES = Regex("<(system-reminder|user-attached-files|context-summary)>.*?(?:</\\1>|\\z)",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

/** Preserve text and actual tool output, never arguments, proof, reasoning, snapshots, or media references. */
private fun decodeHistoryText(id: String, role: String, bytes: ByteArray): HistoryBody {
    return try {
        val json = bytes.decodeToString(throwOnInvalidSequence = true)
        val input = JSONTokener(json)
        val parts = input.nextValue() as? JSONArray ?: return HistoryBody.Unavailable
        if (input.nextClean() != '\u0000' || DisplayParts.isStub(json)) return HistoryBody.Unavailable
        val texts = mutableListOf<String>()
        val results = mutableListOf<String>()
        var toolResult = false
        var previewOnly = false
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: return HistoryBody.Unavailable
            when (part.opt("type") as? String ?: return HistoryBody.Unavailable) {
                "receiptOrigin" -> if (part.opt("value") == "host") return HistoryBody.NoText
                "text" -> {
                    val text = (part.opt("value") ?: part.opt("text")) as? String ?: return HistoryBody.Unavailable
                    texts.add(text)
                }
                "toolResult" -> {
                    toolResult = true
                    val output = part.optJSONObject("value")?.opt("output") as? String ?: return HistoryBody.Unavailable
                    // A spill reference is not authority to reopen a mutable workspace file.
                    // History only has the stored output part; keep its text searchable.
                    val content = if (output.startsWith("$TOOL_RECEIPT_NOTICE ")) output.substringAfter('\n', "") else output
                    previewOnly = previewOnly || ToolOutputSpill.isOffloadPlaceholder(content) ||
                        ToolResultProjection.isProjected(content)
                    results.add(output)
                }
            }
        }
        val resultRole = if (toolResult && role == "user") "tool" else role
        val visible = when (resultRole) {
            "tool" -> results
            "user", "assistant" -> texts
            else -> return HistoryBody.NoText
        }.map { HOST_FRAMES.replace(it, "").replace(PreviewText.HOST_NOTE_NO_REPLY_HEAD, "") }
            .filter(String::isNotBlank).joinToString("\n")
        val isPreview = resultRole == "tool" && previewOnly
        if (visible.isBlank() && !isPreview) HistoryBody.NoText else HistoryBody.Text(id, resultRole, visible, isPreview)
    } catch (_: Exception) {
        HistoryBody.Unavailable
    } catch (_: StackOverflowError) {
        HistoryBody.Unavailable
    }
}
