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