package com.openminis.app.checkpoint

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.harness.checkpoint.CheckpointMeta
import com.openminis.app.harness.checkpoint.CheckpointStore
import com.openminis.app.harness.checkpoint.FileCheckpointPersistence
import com.openminis.app.harness.checkpoint.RewindController
import com.openminis.app.harness.checkpoint.SessionForkConversationRewinder
import java.io.File

/**
 * [T-checkpoint-rewind] harness 检查点/回滚设施与 OpenMinis 宿主之间的**唯一接缝**。
 *
 * 为什么需要它：harness 是纯 JVM 库（禁 android/androidx），拿不到 `Context`、
 * `PRootKernel`、Room——这三样都由本桥接层按端口注入（[AppRewindFileAccess] /
 * [AppSessionForkPort]）。工具层（file_write / file_edit）只依赖本对象，
 * 不直接接触 harness 类型。
 *
 * 生命周期：
 * - [store] 进程内单例，落盘根目录 `filesDir/checkpoints/<sessionId>/`，
 *   与会话工作区同盘但不同目录（不经 SAF，模型不可见）；
 * - 会话删除时由 [ChatRepository.deleteSession] 调 [dropSession] 清理，
 *   异步写队列与删除走同一串行执行器（见 CheckpointStore.dropSession）。
 */
object CheckpointBridge {

    private const val DIR = "checkpoints"

    @Volatile
    private var storeRef: CheckpointStore? = null

    /** 进程内单例 store；首次调用时装配磁盘持久化。 */
    fun store(filesDir: File): CheckpointStore {
        storeRef?.let { return it }
        return synchronized(this) {
            storeRef ?: CheckpointStore().also { store ->
                store.persistence = FileCheckpointPersistence(File(filesDir, DIR))
                storeRef = store
            }
        }
    }

    /**
     * 用户轮起点：开启本轮 checkpoint（并关闭上一轮）。
     * 与上游 `HarnessLoop.beginTurn(sessId, userText, userMessage.id)` 同一时机，
     * 由 `runAgentLoop` 入口统一驱动，因此 send / retry / resume / drain
     * 五条进 loop 的路径自动覆盖。
     */
    fun beginTurn(context: Context, sessionId: String, prompt: String, anchorMessageId: String?) {
        runCatching { store(context.filesDir).beginTurn(sessionId, prompt, anchorMessageId) }
    }

    /**
     * 写前捕获轮初内容。文件**不存在**时记录 null —— rewind 时删除本轮新建的
     * 文件，这是「撤回该轮」的完整语义。超大文件跳过捕获：既恢复不了内容，
     * 也不能记 null（那会在 rewind 时误删）。best-effort，捕获失败绝不挡写入。
     */
    fun captureBefore(context: Context, sessionId: String, path: String, host: File): Boolean {
        if (!host.exists()) {
            return runCatching { store(context.filesDir).capture(sessionId, path, null) }
                .getOrDefault(false)
        }
        if (!host.isFile || host.length() > CheckpointStore.SNAPSHOT_MAX_BYTES) return false
        val before = runCatching { host.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        return runCatching { store(context.filesDir).capture(sessionId, path, before) }
            .getOrDefault(false)
    }

    /** file_edit 在读写锁内已持有即将被替换的内容，无需二次读盘。 */
    fun captureBeforeText(context: Context, sessionId: String, path: String, before: String): Boolean {
        if (before.toByteArray(Charsets.UTF_8).size > CheckpointStore.SNAPSHOT_MAX_BYTES) return false
        return runCatching { store(context.filesDir).capture(sessionId, path, before) }
            .getOrDefault(false)
    }

    /** 写后凭据：rewind 的冲突检测基线（无凭据的路径不做冲突检测）。 */
    fun captureAfter(context: Context, sessionId: String, path: String, host: File): Boolean {
        if (!host.isFile || host.length() > CheckpointStore.SNAPSHOT_MAX_BYTES) return false
        val after = runCatching { host.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        return captureAfterText(context, sessionId, path, after)
    }

    fun captureAfterText(context: Context, sessionId: String, path: String, after: String): Boolean {
        if (after.toByteArray(Charsets.UTF_8).size > CheckpointStore.SNAPSHOT_MAX_BYTES) return false
        return runCatching { store(context.filesDir).captureAfterImage(sessionId, path, after) }
            .getOrDefault(false)
    }

    /** 该会话的轮次摘要（升序），供 rewind UI 按 anchor 定位轮次。 */
    fun checkpoints(context: Context, sessionId: String): List<CheckpointMeta> =
        runCatching { store(context.filesDir).checkpoints(sessionId) }.getOrDefault(emptyList())

    /** 会话删除/重建时清理（内存态 + 磁盘 + 撤销记录）。 */
    fun dropSession(filesDir: File, sessionId: String) {
        runCatching { store(filesDir).dropSession(sessionId) }
    }

    /**
     * 组装一次 rewind 编排器。controller 本身无状态（只持引用），
     * 每次按需构造即可；会话作用域经 `commit(plan, workspace = sessionId)`
     * 传给 [AppRewindFileAccess.withBase]。
     */
    fun controller(context: Context, chatRepository: ChatRepository): RewindController {
        val store = store(context.filesDir)
        return RewindController(
            store = store,
            fileAccess = AppRewindFileAccess(context, ""),
            conversationRewinder = SessionForkConversationRewinder(
                AppSessionForkPort(chatRepository),
                store,
            ),
        )
    }
}
