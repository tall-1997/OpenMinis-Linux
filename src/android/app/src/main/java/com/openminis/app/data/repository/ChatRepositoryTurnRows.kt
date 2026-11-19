package com.openminis.app.data.repository

import androidx.room.withTransaction
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.ModelAttributionSnapshot
import java.util.UUID

/** One row of a logical turn; [appendTurnRows] commits all of them atomically. */
data class PendingTurnRow(
    val role: String,
    val partsJson: String,
    val tokenUsage: String? = null,
    val reasoningContent: String? = null,
    val modelSnapshot: ModelAttributionSnapshot? = null,
)

/**
 * [T-atomic-tool-turn] The assistant(tool_use) row and the user(tool_result)
 * row are ONE logical turn. Two independent appendMessage calls leave a crash
 * window where the tool_use lands without its result: compact pairing can no
 * longer close a prefix through that turn and receipt reconciliation finds a
 * dispatched call with no result row to settle. Both rows commit here in ONE
 * transaction or neither does. Body offload (disk I/O) happens BEFORE the
 * transaction opens; inside it only inserts and the same per-row preview
 * bookkeeping appendMessage performs are allowed.
 */
suspend fun ChatRepository.appendTurnRows(sessionId: String, rows: List<PendingTurnRow>): List<MessageEntity> {
    if (rows.isEmpty()) return emptyList()
    val stored = rows.map { row -> row to storeBody(row.partsJson) }
    val now = System.currentTimeMillis()
    val dbRef = db
    if (dbRef == null) {
        // Sequential fallback for repositories built without a database
        // handle (JVM tests); production always passes one.
        val inserted = stored.map { (row, body) -> insertTurnRow(sessionId, row, body, now) }
        updateSessionPreviewAfterTurn(sessionId, stored, now)
        return inserted
    }
    return dbRef.withTransaction {
        val inserted = stored.map { (row, body) -> insertTurnRow(sessionId, row, body, now) }
        updateSessionPreviewAfterTurn(sessionId, stored, now)
        inserted
    }
}

private suspend fun ChatRepository.updateSessionPreviewAfterTurn(
    sessionId: String,
    stored: List<Pair<PendingTurnRow, ChatRepository.StoredBody>>,
    now: Long,
) {
    for ((_, body) in stored) {
        val preview = PreviewText.sessionListPreview(body.inline, body.preview)
        if (preview != null) dao.updateLastMessage(sessionId, preview, now)
        else dao.touchSession(sessionId, now)
    }
}

private suspend fun ChatRepository.insertTurnRow(
    sessionId: String,
    row: PendingTurnRow,
    body: ChatRepository.StoredBody,
    now: Long,
): MessageEntity = dao.appendMessage(MessageEntity(
    id = UUID.randomUUID().toString(),
    sessionId = sessionId,
    role = row.role,
    partsJson = body.inline,
    errorInfo = null,
    bodyBytes = body.bodyBytes,
    bodyRef = body.ref,
    bodySha = body.sha,
    preview = body.preview,
    createdAt = now,
    tokenUsage = row.tokenUsage,
    sortOrder = 0,
    reasoningContent = row.reasoningContent,
    modelId = row.modelSnapshot?.modelId,
    modelDisplayName = row.modelSnapshot?.displayName,
    providerType = row.modelSnapshot?.providerTypeRaw,
    providerInstanceId = row.modelSnapshot?.providerInstanceId,
))

/**
 * UI-safe projection of a hydrated session tail: the identifiers and the raw
 * parts JSON the chat UI needs, without exposing Room entities to the UI layer.
 */
data class SessionTailRow(
    val id: String,
    val role: String,
    val partsJson: String,
    val createdAt: Long,
    val sortOrder: Int,
)

suspend fun ChatRepository.sessionTailDisplayRows(sessionId: String, limit: Int): List<SessionTailRow> =
    hydrateDisplayRows(loadMessagesTail(sessionId, limit)).map { row ->
        SessionTailRow(row.id, row.role, row.partsJson, row.createdAt, row.sortOrder)
    }
