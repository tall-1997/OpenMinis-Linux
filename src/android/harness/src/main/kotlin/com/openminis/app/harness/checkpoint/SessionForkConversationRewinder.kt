package com.openminis.app.harness.checkpoint

import java.util.UUID

/**
 * Adapted from taixu SessionForkConversationRewinder (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * [ConversationRewinder] 的通用实现：在目标轮的用户消息边界处派生新会话。
 *
 * - 由 [CheckpointStore.anchorMessageIdOf] 拿到该轮用户消息 id（锚点）；
 * - 新会话复制当前分支上锚点之前的全部消息（id 重映射、parentId 链重建），
 *   即「撤回该轮及之后对话」——原会话保持不动（会话树本就不可变，丢弃的分支仍留存）；
 * - 新会话继承原会话的模型/工作区/工程类型/审批模式，用户可直接续聊。
 *
 * 存储差异由 [SessionForkPort] 吸收，本类只负责 fork 语义。
 */
class SessionForkConversationRewinder(
    private val port: SessionForkPort,
    private val checkpointStore: CheckpointStore,
) : ConversationRewinder {

    override suspend fun rewindConversation(sessionId: String, turn: Int): String? {
        val source = port.findSession(sessionId) ?: return null
        val anchorMessageId = checkpointStore.anchorMessageIdOf(sessionId, turn) ?: return null
        val entries = port.branchMessages(sessionId)
        val anchorIndex = entries.indexOfFirst { it.id == anchorMessageId }
        // 锚点必须是当前分支上的消息；锚点前无内容则无可回退
        //（锚点即分支首条消息时，派生会话会是空对话，不如让用户直接新建）。
        if (anchorIndex <= 0) return null
        val keep = entries.subList(0, anchorIndex)

        val now = System.currentTimeMillis()
        val title = "${source.title?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE} · 回退分支"
        val forkedSessionId = port.createForkedSession(source, title, now)

        // 复制锚点前的分支前缀：id 全局唯一 → 全部换新并重映射父链。
        // 顺序遍历保证父先于子进入映射表（端口契约：升序返回）。
        val idRemap = HashMap<String, String>(keep.size * 2)
        val forked = keep.map { entry ->
            val newEntryId = UUID.randomUUID().toString()
            idRemap[entry.id] = newEntryId
            entry.copy(id = newEntryId, parentId = entry.parentId?.let { idRemap[it] })
        }
        port.appendMessages(forkedSessionId, forked)
        return forkedSessionId
    }

    private companion object {
        const val DEFAULT_TITLE = "会话"
    }
}
