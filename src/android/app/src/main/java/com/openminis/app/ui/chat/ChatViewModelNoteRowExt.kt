package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-split-chat-historypage] Persisted-row bookkeeping, extracted
 * verbatim from ChatViewModel.kt to keep it under its line ceiling. The six
 * call sites (steering receipts / queue drain / hidden notes / send /
 * stop-run / queue-inject) are unchanged — same receiver, same signature.
 */
internal fun ChatViewModel.notePersistedUiRow(uiMessageId: String?, dbMessageId: String) {
    if (uiMessageId != null) {
        _messages.value = _messages.value.map { message ->
            if (message.id == uiMessageId && dbMessageId !in message.sourceDbIds) {
                message.copy(sourceDbIds = message.sourceDbIds + dbMessageId)
            } else message
        }
    }
    viewModelScope.launch {
        // [T-message-usage-footer-time] Backfill the wall-clock time from
        // the persisted row: the in-memory bubble was built before this DB
        // row existed (createdAt=null), and the usage footer's three-tier
        // time tier needs it — without this a just-finished reply shows
        // tokens but no time until the session is reloaded.
        if (uiMessageId != null) {
            val createdAt = withContext(Dispatchers.IO) {
                chatRepository.dao.createdAtOf(dbMessageId)
            }
            if (createdAt != null) {
                _messages.value = _messages.value.map { message ->
                    if (message.id == uiMessageId && message.createdAt == null) {
                        message.copy(createdAt = createdAt)
                    } else message
                }
            }
        }
        val sort = withContext(Dispatchers.IO) {
            chatRepository.dao.sortOrderOf(dbMessageId)
        }
        val newest = timeline.newestSortOrder
        // count == 1 is this row alone. Anything larger is a gap under it.
        // Jumping the cursor over that gap would hide the missing rows.
        val skipped = newest != null && withContext(Dispatchers.IO) {
            chatRepository.dao.countMessagesAfterSort(sessionId, newest) > 1
        }
        if (!skipped && sort != null && (newest == null || sort > newest)) {
            timeline.extendNewest(sort)
        }
        refreshHistoryEdges()
    }
}
