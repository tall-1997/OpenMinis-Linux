package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.HistoryDigest

/** 历史窗口边界的 DB 权威刷新（拆分自 ChatViewModel，架构行数上限）。 */
    internal suspend fun ChatViewModel.refreshHistoryEdges(scheduleTail: Boolean = true) {
        val oldest = timeline.oldestSortOrder
        val newest = timeline.newestSortOrder
        if (oldest == null || newest == null) {
            timeline.reset()
            _hasOlderMessages.value = false
            _hasNewerMessages.value = false
            return
        }
        val before = chatRepository.dao.countMessagesBeforeSort(sessionId, oldest)
        val after = chatRepository.dao.countMessagesAfterSort(sessionId, newest)
        val loadedDbRows = if (newest == Int.MAX_VALUE) {
            // Avoid overflowing the half-open upper bound at the integer edge.
            chatRepository.dao.messageCountForSession(sessionId) - before - after
        } else {
            chatRepository.dao.countMessagesInSortRange(
                sessionId = sessionId,
                startInclusive = oldest,
                endExclusive = newest + 1,
            )
        }
        // Derive the counters from DB sequence ranges, never from the number
        // of painted rows. One ChatMessage can represent several DB rows.
        // [T-android-timeline-ledger] The edge booleans are DB-authoritative:
        // nothing else in the app may write them (the old offset-based
        // refreshHasOlderMessages double ledger is gone).
        timeline.seedCounters(offset = before, total = before + loadedDbRows + after)
        _hasOlderMessages.value = before > 0
        _hasNewerMessages.value = after > 0
        if (after == 0) _sessionTailReady.value = true
        // A newer gap means the painted window is not the session tail.
        // Attach it. Do not wait for a second control.
        if (scheduleTail && after > 0) ensureSessionTailLoaded()
    }


    internal fun ChatViewModel.rememberDigestLines(lines: List<HistoryDigest.Line>) {
        if (lines.isEmpty()) return
        llmDigestLines.addAll(lines)
        val retained = HistoryDigest.retainNewest(llmDigestLines)
        llmDigestOmitted += (llmDigestLines.size - retained.size).coerceAtLeast(0)
        llmDigestLines.clear()
        llmDigestLines.addAll(retained)
    }

