package com.openminis.app.checkpoint

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.harness.checkpoint.SessionForkMessage
import com.openminis.app.harness.checkpoint.SessionForkPort
import com.openminis.app.harness.checkpoint.SessionForkSource
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File

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
 *
 * [T-p1-9-fork-workspace] fork 必须带走工作区，否则 BOTH/CONVERSATION 回溯派生
 * 的会话在 `/var/minis` 下两手空空——续聊的模型看不到任何前缀消息引用的文件：
 * - 源会话已归档项目（folderId）：fork 直接**加入同一项目**（setFolderIfUnfiled +
 *   rememberFolder），文件本来就放在共享项目目录，join 即可见，无需复制；
 * - 源会话未归档：文件在 `minis-sessions/<sid>/{workspace,attachments,offloads,browser}`
 *   私有目录下，fork 复制这四个子目录（memory 是会话日志，属会话私有，不复制）。
 */
internal class AppSessionForkPort(
    private val chatRepository: ChatRepository,
    private val filesDir: File,
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
                // [T-p1-9-fork-workspace] 项目归属跟随源会话：已归档会话 fork 出来
                // 仍归原项目，共享工作区即刻可见。
                session.folderId?.let { put("folderId", it) }
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
        try {
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
            val folderId = source.inherited["folderId"]
            if (folderId != null) {
                // createSession 不支持 folderId 列——插入后补挂项目（会话此刻必然
                // unfiled，setFolderIfUnfiled 语义恰好匹配），rememberFolder 由它代办。
                val filed = chatRepository.setFolderIfUnfiled(folderId, created.id)
                if (!filed) {
                    AppLogger.warning(
                        "AppSessionForkPort",
                        "fork ${created.id} failed to join project $folderId — workspace stays private",
                    )
                }
                // 已归档：文件在共享项目目录，join 即可见，无需复制。
            } else {
                copyPrivateWorkspace(source.id, created.id)
            }
        } catch (t: Throwable) {
            // fork 是本调用刚刚创建的派生行：半截 fork（有会话无完整对话/工作区）
            // 留在会话列表里只会让用户看到「空对话」。清掉再抛，rewind 整体失败。
            runCatching { chatRepository.deleteSession(created.id) }
            throw t
        }
        return created.id
    }

    /**
     * [T-p1-9-fork-workspace] 未归档源会话：把四个共享子目录递归复制到 fork 的
     * 私有目录。源目录缺失/为空则跳过（新会话本就如此）；memory 是会话日志，
     * 不随 fork 复制。copyRecursively 失败不静默——半份工作区比空工作区更糟，
     * 记日志后重抛，由上层的 fork 回滚接住。
     */
    private suspend fun copyPrivateWorkspace(sourceId: String, forkId: String) {
        val sourceRoot = SessionWorkspace.base(filesDir, sourceId)
        if (!sourceRoot.isDirectory) return
        val forkRoot = SessionWorkspace.base(filesDir, forkId)
        var copied = 0L
        var bytes = 0L
        for (subdir in SessionWorkspace.SHARED_SUBDIRS) {
            val from = File(sourceRoot, subdir)
            if (!from.isDirectory || from.listFiles().isNullOrEmpty()) continue
            val to = File(forkRoot, subdir)
            try {
                to.mkdirs()
                val ok = from.copyRecursively(to, overwrite = false, onError = { file, e ->
                    AppLogger.warning("AppSessionForkPort", "workspace copy failed at ${file.path}: ${e.message}")
                    OnErrorAction.SKIP
                })
                if (!ok) throw IllegalStateException("copyRecursively reported failure for $subdir")
                from.walkTopDown().filter { it.isFile }.forEach {
                    copied++
                    bytes += it.length()
                }
            } catch (t: Throwable) {
                throw IllegalStateException("fork workspace copy failed for '$subdir'", t)
            }
        }
        if (copied > 0) {
            AppLogger.info(
                "AppSessionForkPort",
                "[T-p1-9-fork-workspace] fork workspace copied: files=$copied bytes=$bytes (source=$sourceId fork=$forkId)",
            )
        }
    }

    override suspend fun appendMessages(sessionId: String, messages: List<SessionForkMessage>) {
        // [T-p1-9-fork-roles-guard] 下标旁路的角色列表必须与消息等长：rewinder
        // 只重写 id/parentId，长度前缀不变是其契约的一半；一旦破坏（上游改行为），
        // 静默 getOrElse 兜底会把整段历史错标角色。fail-fast。
        check(messages.size == branchRoles.size) {
            "fork role prefix out of sync: ${messages.size} messages vs ${branchRoles.size} roles"
        }
        messages.forEachIndexed { index, message ->
            // 逐条 append：repo 分配新的 sort_order 与主键，派生会话的顺序
            // 与源分支前缀一致（harness 契约要求顺序，不强求主键相同）。
            chatRepository.appendMessage(
                sessionId,
                role = branchRoles[index],
                partsJson = message.payloadJson,
            )
        }
    }

    private companion object {
        const val PAGE = 50
    }
}
