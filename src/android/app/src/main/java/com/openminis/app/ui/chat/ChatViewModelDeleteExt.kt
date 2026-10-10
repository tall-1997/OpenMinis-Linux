package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch

/**
 * [T-android-delete-from-here] Delete [messageId] and every message after
 * it, leaving the conversation as it stood immediately before that turn.
 * Mirrors iOS `6717c0ab0`.
 *
 * Deliberately close to — but not the same as — [retryFromMessage]. Both
 * rewind the conversation to a point, so this reuses that method's DB
 * anchoring: walk the persisted rows counting only user messages that have
 * VISIBLE text, because the agent loop also persists synthetic
 * `<system-reminder>` user rows that never rendered a bubble. Counting
 * those would anchor the cut one turn too early and silently take an extra
 * exchange with it (the iOS retry-anchor fix, ported here already).
 *
 * The one deliberate difference is the cut point: retry keeps the target
 * user message and re-sends it, so it cuts at `sortOrder + 1`. Delete From
 * Here removes the target too, so it cuts at `sortOrder`. Off-by-one in
 * either direction is silent and destructive — one strands the message the
 * user asked to delete, the other eats the preceding turn.
 *
 * No stream is started and `_isStreaming` is never claimed: this is a pure
 * truncation. It is still refused while a turn is in flight, because
 * deleting rows out from under a live agent loop would leave
 * [agentHistory] describing messages that no longer exist.
 */
fun ChatViewModel.deleteFromMessage(messageId: String) {
    if (_isStreaming.value) return
    _canResume.value = false
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return

    // Snapshot what's about to go so any memory_write tool blocks inside
    // can be revoked — otherwise the on-disk daily log keeps entries from
    // messages the user just deleted.
    val deletedMessages = messages.subList(index, messages.size).toList()

    // Truncate the UI to everything BEFORE the target message, and drop
    // any queued prompts that belonged to the deleted range so a later
    // auto-drain can't resurrect them.
    val retainedHead = messages.subList(0, index)
    for (m in deletedMessages) {
        // [T-queue-disk-persistence] forgetQueuedPrompt 连带撤掉磁盘镜像，
        // 否则重启会给这批已删除的消息还原出幽灵排队气泡。
        m.queuedPromptId?.let { pid -> forgetQueuedPrompt(pid) }
    }
    _messages.value = retainedHead

    // Scrub stream deltas pointing at truncated messages so they can't
    // resurface into a row that no longer exists.
    val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
    retainStreamFlushStates(keptIds)
    if (_streamingById.value.isNotEmpty()) {
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }

    revokeMemoryWritesInDeletedMessages(deletedMessages)

    val sid = activeSessionId ?: return
    viewModelScope.launch {
        val target = messages[index]
        val cutoffSortOrder = resolveDeleteCutoffSortOrder(sid, messages, index, target)
        if (cutoffSortOrder >= 0) {
            chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
        }

        // Rebuild agentHistory from what survived, so the next turn is
        // built on the truncated conversation rather than a stale list.
        val tail = awaitBoundedHistoryRebuild(sid)
        reconcileCompactMarkerAfterTruncation()
        // Refresh the session's last-message preview; otherwise the
        // session list keeps quoting a message that no longer exists.
        // An empty remainder clears it rather than leaving the stale text.
        runCatching {
            chatRepository.updateSessionPreview(sid, tail.lastOrNull()?.partsJson ?: "[]")
        }
        AppLogger.info(
            ChatViewModel.TAG,
            "deleteFromMessage: cut at sortOrder=$cutoffSortOrder, " +
                "${deletedMessages.size} message(s) removed, ${tail.size} remain",
        )
    }
}

/**
 * [T-p1-context-marker-reconcile] 长按截断类操作（重试 / 从此处删除 / 撤回到
 * 此轮）之后的压缩标记对账。
 *
 * 三种操作都把历史截断到某个点，但**压缩标记不随之对账**：标记的摘要描述的是
 * 被截断掉的轮次（「已完成了 X/Y/Z」），而那些轮次刚刚被用户删除/回滚——
 * effectiveAgentHistory 的投影仍然把摘要注入模型上下文，模型以为做了已经不
 * 存在的工作 → 「接入模型的上下文与实际上下文不对」。
 *
 * 规则：标记的三个锚（boundary / firstKept / lastCompacted）在**活历史**里
 * 一个都解析不到 → 摘要描述的会话已不存在 → 整体丢弃压缩状态（删 DB 标记行 +
 * 清内存缓存 + 清 UI 分隔行），全量历史重新流动。任一锚仍在 → 标记有效保留
 * （detached-anchor 的 _compactMarkerDetached 是窗口外不是不存在，不在此列）。
 */
internal suspend fun ChatViewModel.reconcileCompactMarkerAfterTruncation() {
    val marker = _cachedLatestMarker ?: return
    val history = agentHistory.toList()
    val anchors = listOfNotNull(
        marker.boundaryMessageId,
        marker.firstKeptMessageId,
        marker.lastCompactedMessageId,
    )
    if (anchors.isEmpty()) return
    val anchorIds = anchors.toSet()
    val anyAnchorAlive = history.any { it.dbMessageId != null && it.dbMessageId in anchorIds }
    if (anyAnchorAlive) return
    val sid = realSessionId.ifEmpty { sessionId }
    AppLogger.warning(
        ChatViewModel.TAG,
        "[Compact] truncation removed every marker anchor (${anchors.size}) — " +
            "dropping compact state; summary described turns that no longer exist",
    )
    runCatching { chatRepository.dao.deleteCompactMarker(marker.id) }
    _cachedLatestMarker = null
    _compactSummary.value = null
    // UI 分隔行（compact 系统行）随标记消失；真实消息不动。
    _messages.value = _messages.value.filterNot { msg ->
        msg.role == "system" && msg.toolBlocks.firstOrNull()?.toolName == "compact"
    }
}
