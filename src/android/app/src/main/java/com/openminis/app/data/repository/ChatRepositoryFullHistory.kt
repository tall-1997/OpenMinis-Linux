package com.openminis.app.data.repository

import com.openminis.app.data.db.MessageEntity

/** T188: a single message in the paginated transcript returned by `minis-sessions-cli messages`. */
data class MessagePageItem(
 val messageId: String,
 val role: String,
 val createdAt: Long,
 val text: String,
 // [T-android-sessions-cli-full] True when the stored text exceeded the requested cap.
 val truncated: Boolean = false,
 /** True when text is only the `body stored` projection; use message_id for full text. */
 val bodyOffloaded: Boolean = false,
)


/** Full-body transcript reads for read_session; offloaded rows resolve through BodyStore. */
suspend fun ChatRepository.loadMessagePageFull(
    sessionId: String,
    offset: Int,
    limit: Int,
    maxChars: Int = ChatRepository.MESSAGE_TEXT_MAX,
    startMs: Long? = null,
    endMs: Long? = null,
): List<MessagePageItem> {
    val rows = if (startMs == null && endMs == null) {
        dao.loadMessagesPage(sessionId, offset, limit)
    } else {
        dao.loadMessagesPageInRange(sessionId, offset, limit, startMs, endMs)
    }
    return rows.mapNotNull { row ->
        val text = extractTextForOffload(fullBodyForCopy(row))
        if (text.isBlank()) return@mapNotNull null
        MessagePageItem(row.id, row.role, row.createdAt, text.take(maxChars), text.length > maxChars)
    }
}

/** One exact persisted message, hydrated for offloaded rows. */
suspend fun ChatRepository.readFullMessageText(sessionId: String, messageId: String): MessagePageItem? {
    val row = dao.messageById(messageId)?.takeIf { it.sessionId == sessionId } ?: return null
    val text = extractTextForOffload(fullBodyForCopy(row))
    if (text.isBlank()) return null
    return MessagePageItem(row.id, row.role, row.createdAt, text, false)
}
