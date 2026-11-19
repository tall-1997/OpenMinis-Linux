package com.openminis.app.data.repository

/**
 * [T-history-retrieval] Single-row history reads, kept OUT of
 * [ChatRepository].
 *
 * WHY a separate file: `ChatRepository.kt` sits on an architecture ceiling
 * (1 383 lines, ratchet) and the batch of history-retrieval work pushed it
 * over. The extraction is mechanical — an extension that reaches the same DAO
 * and the same body store, so the text a caller pages by is byte-identical to
 * [ChatRepository.loadMessagePage].
 *
 * [T-history-read-real-body] `dao.getMessage` goes through the safe projection,
 * which returns the PREVIEW (or a "body kept on disk" placeholder) for any row
 * over 2 KB whose body lives in `BodyStore`. Reading that as the message text
 * made `history_read` answer with a short preview while reporting
 * `truncated: false` — the model then believed the preview WAS the message, and
 * a needle in the second half of a long paste was never found at all. Both
 * callers now hydrate through the display pipeline, the only component that
 * knows how to turn a `body_ref` back into text.
 */
suspend fun ChatRepository.messageText(sessionId: String, messageId: String): String? {
    val row = dao.getMessage(sessionId, messageId) ?: return null
    // One row, hydrated in place: reads BodyStore when the DB holds a stub.
    val hydrated = hydrateDisplayRows(listOf(row)).firstOrNull() ?: row
    return extractTextForOffload(hydrated.partsJson).takeIf { it.isNotBlank() }
}

/**
 * [T-history-marker-scope] Epoch millis of the newest compaction boundary for
 * this session, or null when nothing has been compacted.
 *
 * WHY: `history_search` exists to reach history that compaction folded OUT of
 * the request. Without a cutoff it also returned the messages the model is
 * already looking at, which wastes a slot on something it can read directly and
 * makes "I found it in history" indistinguishable from "it is right above". The
 * contractor is exact: prefer the anchor row's own timestamp (the last message
 * actually folded), fall back to the marker's creation time for markers whose
 * anchor row has since been removed.
 */
suspend fun ChatRepository.compactBoundaryMs(sessionId: String): Long? {
    val marker = dao.latestCompactMarker(sessionId) ?: return null
    val anchorId = marker.lastCompactedMessageId
    if (!anchorId.isNullOrBlank()) {
        dao.getMessage(sessionId, anchorId)?.let { return it.createdAt }
    }
    return marker.createdAt
}
