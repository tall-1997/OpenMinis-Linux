package com.openminis.app.ui.chat

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.compactOrderForId

/** Resolve persisted markers without expanding coverage based on the UI window or clock. */
@Suppress("UNUSED_PARAMETER") // The load caller still supplies its resident ids; they don't prove coverage.
internal suspend fun ChatViewModel.applyCompactMarkerGraying(
    messages: List<ChatMessage>,
    marker: CompactMarkerEntity,
    rawMessages: List<MessageEntity>,
    historyDbIds: Set<String>,
): List<ChatMessage> {
    val firstKeptId = if (marker.version < 2) {
        marker.firstKeptMessageId?.takeIf(String::isNotBlank)
            ?: marker.boundaryMessageId?.takeIf(String::isNotBlank)
    } else null
    val anchorId = firstKeptId ?: marker.lastCompactedMessageId?.takeIf(String::isNotBlank)
    val order = anchorId?.let { chatRepository.compactOrderForId(marker.sessionId, it) }
    if (order == null && marker.version >= 2) return messages
    val covered = order?.let {
        rawMessages.asSequence()
            .filter { row -> row.sortOrder < order || (firstKeptId == null && row.sortOrder == order) }
            .map { row -> row.id }.toHashSet()
    }.orEmpty()
    return renderCompactDivider(messages, marker, compactDividerIndex(messages, covered))
}

/** A merged bubble may include rows after the anchor; keep it active as a whole. */
internal fun compactDividerIndex(messages: List<ChatMessage>, coveredIds: Set<String>): Int =
    messages.indexOfLast { row ->
        row.role != "system" && if (row.sourceDbIds.isEmpty()) row.id in coveredIds
        else row.sourceDbIds.all { it in coveredIds }
    } + 1

internal fun ChatViewModel.applyNewCompactDivider(marker: CompactMarkerEntity, coveredIds: Set<String>) {
    val messages = _messages.value.filterNot { row ->
        row.role == "system" && row.toolBlocks.firstOrNull()?.toolName == "compact"
    }
    val insertIdx = maxOf(
        compactDividerIndex(messages, coveredIds),
        messages.indexOfLast { it.isCompactedHistory } + 1,
    )
    _messages.value = renderCompactDivider(messages, marker, insertIdx, marker.compactedCount)
}

private fun renderCompactDivider(
    messages: List<ChatMessage>,
    marker: CompactMarkerEntity,
    insertIdx: Int,
    count: Int = marker.compactedCount,
): List<ChatMessage> {
    val out = messages.mapIndexedTo(ArrayList(messages.size + 1)) { index, msg ->
        if (index < insertIdx && msg.role != "system" && !msg.isCompactedHistory) {
            msg.copy(isCompactedHistory = true)
        } else msg
    }
    val divider = ChatMessage(
        id = "compact-divider-msg-${marker.id}",
        role = "system",
        content = "",
        toolBlocks = listOf(AssistantBlock(
            id = "compact-divider-${marker.id}",
            kind = "info",
            content = "$count messages compacted",
            toolName = "compact",
            toolArgs = marker.summary,
        )),
    )
    out.add(insertIdx.coerceIn(0, out.size), divider)
    return out
}
