package com.openminis.app.checkpoint

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.harness.checkpoint.SessionForkMessage
import com.openminis.app.harness.checkpoint.SessionForkPort
import com.openminis.app.harness.checkpoint.SessionForkSource

/**
 * [T-checkpoint-rewind] [SessionForkPort] 的宿主实现：把 harness 的对话 fork 语义
 * 接到 Room 的扁平消息表（`session_id` + `sort_order`）。
 *
 * 与上游「entry 树 + lane 游标」的差异全部由本类吸收：
 * - 扁平模型没有父链，`parentId` 恒为 null（[SessionForkPort] 契约允许）；
 * - harness 视角的消息不带 role，而宿主每一行都需要 `role` 列——按
 *   [branchMessages] 与 [appendMessages] 的**下标一一对应**关系旁路传递：
 *   rewinder 只重写 id / parentId，不改顺序也不改长度前缀，且一次 fork
 *   在单协程内串行（先 branch 后 append），下标稳定。
 */
internal class AppSessionForkPort(
    private val chatRepository: ChatRepository,
) : SessionForkPort {

    private var branchRoles: List<String> = emptyList()

    override suspend fun findSession(sessionId: String): SessionForkSource? {
        val session = chatRepository.getSession(sessionId) ?: return null
        return SessionForkSource(
            id = session.id,
            title = session.title,
            // harness 不解释这些键，只原样继承；新增平台字段不必改端口。
            inherited = buildMap {
                put("modelId", session.modelId)
                session.modelBinding?.let { put("modelBinding", it) }
                session.permissionMode?.let { put("permissionMode", it) }
                session.category?.let { put("category", it) }
                session.thinkingOverride?.let { put("thinkingOverride", it) }
                put("memoryEnabled", session.memoryEnabled.toString())
            },
        )
    }

    override suspend fun branchMessages(sessionId: String): List<SessionForkMessage> {
        val rows = ArrayList<MessageEntity>()
        var offset = 0
        while (true) {
            val page = chatRepository.dao.loadMessagesPage(sessionId, offset, PAGE)
            rows += page
            if (page.size < PAGE) break
            offset += page.size
        }
        branchRoles = rows.map { it.role }
        return rows.map { row ->
            SessionForkMessage(
                id = row.id,
                parentId = null,
                createdAt = row.createdAt,
                payloadJson = row.partsJson,
            )
        }
    }

    override suspend fun createForkedSession(source: SessionForkSource, title: String, now: Long): String {
        val created = chatRepository.createSession(
            modelId = source.inherited["modelId"] ?: "unknown",
            title = title,
            memoryEnabled = source.inherited["memoryEnabled"] != "0",
            permissionMode = source.inherited["permissionMode"] ?: "ASK",
        )
        source.inherited["category"]?.let {
            chatRepository.updateSessionTitleAndCategory(created.id, title, it)
        }
        source.inherited["modelBinding"]?.let {
            chatRepository.updateSessionBinding(
                created.id,
                it,
                source.inherited["modelId"] ?: created.modelId,
            )
        }
        source.inherited["thinkingOverride"]?.let {
            chatRepository.dao.updateThinkingOverride(created.id, it)
        }
        return created.id
    }

    override suspend fun appendMessages(sessionId: String, messages: List<SessionForkMessage>) {
        messages.forEachIndexed { index, message ->
            // 逐条 append：repo 分配新的 sort_order 与主键，派生会话的顺序
            // 与源分支前缀一致（harness 契约要求顺序，不强求主键相同）。
            chatRepository.appendMessage(
                sessionId,
                role = branchRoles.getOrElse(index) { "user" },
                partsJson = message.payloadJson,
            )
        }
    }

    private companion object {
        const val PAGE = 50
    }
}
