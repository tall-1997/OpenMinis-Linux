package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

// [T-android-split-chat-historypage] History window paging extracted verbatim
// from ChatViewModel.kt (mechanical split, zero behavior change). Member
// functions became ChatViewModel extensions with identical signatures, so
// every call site keeps its exact syntax. tailAttachJob / tailAttachQueued /
// _hasNewerMessages were promoted private → internal (class members) since
// the tail-attach driver now lives here.

/**
 * Prepend one turn-aligned page in the background (pill / sentinel entry
 * point). See [loadOlderPage] for the single-page contract.
 */
fun ChatViewModel.loadOlderMessages() {
    if (loadingOlderFlag.get()) return
    viewModelScope.launch { loadOlderPage() }
}

/**
 * Prepend one turn-aligned page. The newer side stays on screen — a live
 * turn sits on THAT edge and never touches the old edge this page
 * prepends to, so paging stays available mid-stream. Returns true when
 * rows were added to the painted window.
 *
 * [T-android-timeline-ledger] The whole fetch+splice holds the ledger's
 * mutation mutex so a concurrent tail-attach drain cannot interleave its
 * own splice into the same `_messages` publish. Single-flight: a call
 * while a page is already loading is a no-op (the caller re-resolves on
 * its next tap). Stream-side publishes take no mutex but run on the main
 * dispatcher; this splice re-reads `_messages` only after its last
 * suspension with no suspension before the publish — main-thread
 * serialization closes the lost-update window.
 *
 * [T-android-upbtn-no-scan] The up-button awaits this when its walk
 * reaches the window's oldest edge with history still above — one page
 * per tap keeps each click bounded.
 */
internal suspend fun ChatViewModel.loadOlderPage(): Boolean {
    if (!claimOlderLoad()) return false
    _isLoadingHistory.value = true
    try {
        var added = false
        timeline.mutationMutex.withLock {
            val before = timeline.oldestSortOrder
            if (before == null) return@withLock
            val hardPageLimit = ChatHistoryWindow.PAGE_ROW_LIMIT
            val oldestAnchor = withContext(Dispatchers.IO) {
                chatRepository.dao.loadOlderSortAnchors(sessionId, before, hardPageLimit)
                    .lastOrNull()?.sortOrder
            }
            val start = withContext(Dispatchers.IO) {
                olderTurnStart(before, ChatHistoryWindow.TURN_PAGE_SIZE)
            }
            // [T-load-older-fallback] Turn-aligned paging targets whole
            // user turns. When the remaining history before the window has
            // fewer than TURN_PAGE_SIZE user turns (or none at all),
            // olderTurnStart returns null even though countMessagesBeforeSort
            // still reports rows — mostly assistant-only trailing messages.
            // Without a fallback, the pill stays lit but every click just
            // calls refreshHistoryEdges and returns: visible button, zero
            // effect. Fall back to a non-turn-aligned load of all remaining
            // rows instead of freezing at the boundary.
            val boundedStart = ChatHistoryWindow.boundedOlderStart(start, oldestAnchor, before)
            val loadStart = if (boundedStart != null) {
                boundedStart
            } else {
                // Turn alignment failed. Do NOT walk all the way to the
                // session start: that is how one tap re-materialised the
                // whole transcript the 500-row cold-open cap exists to
                // keep out of the render window. Take one capped page;
                // another tap continues from its new edge.
                val remaining = withContext(Dispatchers.IO) {
                    chatRepository.dao.countMessagesBeforeSort(sessionId, before)
                }
                if (remaining <= 0) {
                    refreshHistoryEdges(scheduleTail = false)
                    return@withLock
                }
                // One probe of the cold-open cap, newest first. The oldest
                // anchor in it is the page start; rows before it stay
                // behind the pill.
                val anchors = withContext(Dispatchers.IO) {
                    chatRepository.dao.loadOlderSortAnchors(
                        sessionId,
                        before,
                        hardPageLimit,
                    )
                }
                anchors.lastOrNull()?.sortOrder
            }
            if (loadStart == null || loadStart >= before) {
                // Fallback exhausted every probe but the earlier count
                // still saw rows. That contradiction almost always means
                // rows were DELETED mid-flight (truncate/retry renumbers
                // the tail); the old code hard-fused
                // `_hasOlderMessages=false` here, permanently hiding the
                // pill even when the DB still had older rows — the
                // "部分消息从会话页消失" bug. The DB recount inside
                // refreshHistoryEdges is the only authority on the edge.
                refreshHistoryEdges(scheduleTail = false)
                return@withLock
            }
            val pageRows = withContext(Dispatchers.IO) {
                chatRepository.hydrateDisplayRows(
                    loadSortRange(
                        SortRange(
                            loadStart,
                            if (before == Int.MAX_VALUE) before else before + 1,
                        ),
                    ),
                )
            }
            val rows = pageRows
            if (rows.isNotEmpty()) {
                val current = _messages.value
                val known = current.flatMapTo(mutableSetOf()) { it.sourceDbIds }
                val older = toChatMessages(rows)
                val updated = current.map { message ->
                    older.firstOrNull { incoming ->
                        incoming.sourceDbIds.any(message.sourceDbIds::contains)
                    } ?: message
                }
                val fresh = older.filter { message ->
                    message.sourceDbIds.isEmpty() || message.sourceDbIds.none(known::contains)
                }
                if (fresh.size < older.size) {
                    // Rows in this page are already painted — the cursor
                    // should have excluded them. Never eat them silently:
                    // log loudly so a double-splice shows up in the trace.
                    AppLogger.warning(
                        ChatViewModel.TAG,
                        "loadOlder: page [$loadStart,$before) returned ${older.size} rows, " +
                            "${older.size - fresh.size} already painted (cursor=${timeline.oldestSortOrder})",
                    )
                }
                if (fresh.isNotEmpty() || updated != current) {
                    _messages.value = fresh + updated
                    added = true
                }
                timeline.noteBounds(rows)
            }
            // [T-load-older-no-tail-attach] 不在这里 scheduleTail：
            // 用户刚点“加载更早”正在向上读历史，此时立刻触发
            // ensureSessionTailLoaded 追加尾部 chunk 会连续两次突变
            // _messages，LazyColumn 锚点被打掉 → 跳屏 + 一段记录
            // 被悄悄加载。尾部 gap 由 isStreaming 翻转收集器与
            // ensureSessionTailLoaded 的 queue 机制兜底，不需要
            // 借翻历史的动作来抢跑。
            refreshHistoryEdges(scheduleTail = false)
        }
        return added
    } finally {
        loadingOlderFlag.set(false)
        _isLoadingHistory.value = false
    }
}

/**
 * The painted window must include the session tail. If rows exist after
 * the newest cursor, append them. A click that only loads five turns
 * is what left the newer transcript off screen.
 */
fun ChatViewModel.ensureSessionTailLoaded() {
    if (sessionId.isEmpty()) return
    _sessionTailReady.value = false
    if (_isStreaming.value || tailAttachJob?.isActive == true) {
        tailAttachQueued = true
        return
    }
    tailAttachQueued = false
    tailAttachJob = viewModelScope.launch {
        _isLoadingHistory.value = true
        try {
            var idlePasses = 0
            while (!_isStreaming.value && idlePasses < 3) {
                val before = timeline.newestSortOrder
                drainMissingTail()
                refreshHistoryEdges(scheduleTail = false)
                val again = tailAttachQueued || _hasNewerMessages.value
                tailAttachQueued = false
                if (!again) break
                if (timeline.newestSortOrder == before) idlePasses++ else idlePasses = 0
            }
            _sessionTailReady.value = !_hasNewerMessages.value
        } finally {
            val restart = tailAttachQueued && !_isStreaming.value
            tailAttachJob = null
            tailAttachQueued = false
            _isLoadingHistory.value = false
            if (!_isStreaming.value && !_hasNewerMessages.value) _sessionTailReady.value = true
            if (restart) ensureSessionTailLoaded()
        }
    }
}

private suspend fun ChatViewModel.drainMissingTail() {
    var chunks = 0
    while (!_isStreaming.value && chunks < 10_000) {
        val endExclusive = withContext(Dispatchers.IO) {
            chatRepository.dao.nextSortOrder(sessionId)
        }
        val missing = ChatHistoryWindow.missingTailRange(
            timeline.newestSortOrder,
            endExclusive,
        ) ?: return
        val chunkEnd = minOf(
            missing.endExclusive.toLong(),
            missing.startInclusive.toLong() + ChatHistoryWindow.TAIL_ATTACH_CHUNK.toLong(),
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (chunkEnd <= missing.startInclusive) return
        // One chunk = one critical section: fetch + splice + cursor under
        // the same lock so a concurrent load-older page cannot splice in
        // between (that race produced duplicated and reordered rows).
        timeline.mutationMutex.withLock {
            val rows = withContext(Dispatchers.IO) {
                chatRepository.hydrateDisplayRows(
                    // Include the already-painted boundary row so a
                    // toolUse immediately before this chunk can be paired
                    // with its toolResult inside toChatMessages(). The
                    // overlap is removed by sourceDbIds below.
                    loadSortRange(
                        SortRange(
                            if (missing.startInclusive > Int.MIN_VALUE) missing.startInclusive - 1 else missing.startInclusive,
                            chunkEnd,
                        ),
                    ),
                )
            }
            if (rows.isEmpty()) {
                // [T-android-timeline-ledger] A hydrate can legitimately
                // return nothing (body-store admission pressure) while the
                // DB still HAS rows in this range. The old code pushed the
                // cursor past the gap on empty rows — silently consuming
                // rows that were never painted. Verify against the DB:
                // consume only a genuinely empty range; otherwise leave
                // the cursor alone and stop this pass (a later attach
                // retries instead of spinning).
                val dbCount = withContext(Dispatchers.IO) {
                    chatRepository.dao.countMessagesInSortRange(
                        sessionId,
                        missing.startInclusive,
                        chunkEnd,
                    )
                }
                if (dbCount > 0) {
                    AppLogger.warning(
                        ChatViewModel.TAG,
                        "tail drain: range [${missing.startInclusive},$chunkEnd) has $dbCount rows " +
                            "but hydrate returned none — leaving gap unconsumed",
                    )
                    return
                }
                timeline.forceNewestAtLeast(chunkEnd - 1)
                chunks++
                return@withLock
            }
            val missingMessages = toChatMessages(rows)
            val current = _messages.value
            val present = current.flatMapTo(mutableSetOf()) { it.sourceDbIds }
            // The first row may be an overlap from the already-painted
            // window. Replace that existing bubble as well: this is what
            // makes a toolResult that arrived in the next chunk update the
            // previously painted toolUse card instead of being silently
            // discarded as a duplicate.
            val updated = current.map { message ->
                missingMessages.firstOrNull { incoming ->
                    incoming.sourceDbIds.any(message.sourceDbIds::contains)
                } ?: message
            }
            val freshStart = missingMessages.indexOfFirst { message ->
                message.sourceDbIds.none(present::contains)
            }
            if (freshStart >= 0) {
                val fresh = missingMessages.filter { message ->
                    message.sourceDbIds.none(present::contains)
                }
                val insertAt = ChatHistoryWindow.missingTailInsertIndex(
                    currentSourceIds = current.map { it.sourceDbIds },
                    chunkSourceIds = missingMessages.map { it.sourceDbIds },
                    freshStartIndex = freshStart,
                )
                _messages.value = if (insertAt < 0) {
                    updated + fresh
                } else {
                    updated.take(insertAt) + fresh + updated.drop(insertAt)
                }
            } else if (updated != current) {
                _messages.value = updated
            }
            timeline.noteBounds(rows)
        }
        chunks++
        // [T-android-entry-frame-parse-isolation] Publish at most one chunk
        // per frame. Back-to-back `_messages` mutations made every frame
        // re-run the whole-transcript rebuild while the frame's own row
        // parses starved on the shared pool — the 2320-message session sat
        // blank ~46s (coldPrewarm.done elapsedMs=45081). One 16ms yield per
        // chunk keeps the drain running (50 rows/frame still finishes 2k+
        // rows in well under a second of wall time) while giving composition
        // a real frame to paint each slice.
        delay(16)
    }
}
