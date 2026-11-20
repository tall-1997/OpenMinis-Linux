package com.openminis.app.data.repository

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.display.DisplayParts

/** Receipt recovery sees original rows, not rendered bubbles or provider history. */
internal class ReceiptHistoryRow(internal val source: MessageEntity) {
    val id get() = source.id
    val sessionId get() = source.sessionId
    val role get() = source.role
    val sortOrder get() = source.sortOrder
    val projectedSize get() = maxOf(source.bodyBytes, source.partsJson.length.toLong())
}

internal fun List<MessageEntity>.toReceiptHistoryRows(): List<ReceiptHistoryRow> = map(::ReceiptHistoryRow)

internal suspend fun ChatRepository.receiptHistoryContext(
    sessionId: String,
    page: List<ReceiptHistoryRow>,
    lookback: Int,
): List<ReceiptHistoryRow> {
    val first = page.first()
    val last = page.last()
    val before = if (first.sortOrder > 0) {
        dao.loadOlderSortAnchors(sessionId, first.sortOrder, lookback).lastOrNull()?.sortOrder?.let { start ->
            loadMessagesInSortRange(sessionId, start, first.sortOrder).toReceiptHistoryRows()
        }.orEmpty()
    } else emptyList()
    val after = if (last.sortOrder < Int.MAX_VALUE && last.role == "assistant") {
        dao.loadMessagesFromSortOrder(sessionId, last.sortOrder + 1, Int.MAX_VALUE, limit = 1)
            .toReceiptHistoryRows()
    } else emptyList()
    return before + page + after
}

/** A truncated or unreadable body cannot prove delivery or provide a user anchor. */
internal suspend fun ChatRepository.receiptPartsJson(row: ReceiptHistoryRow): String? {
    val source = row.source
    if (!DisplayParts.needsHydration(source.bodyRef, source.bodyBytes, source.partsJson)) return source.partsJson
    return readDisplayBody(source, bodyStore())
}
