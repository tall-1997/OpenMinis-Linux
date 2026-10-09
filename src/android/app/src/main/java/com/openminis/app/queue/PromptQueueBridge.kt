package com.openminis.app.queue

import android.content.Context
import com.openminis.app.harness.queue.PromptQueue
import com.openminis.app.harness.queue.PromptQueueManager
import com.openminis.app.harness.queue.FilePromptQueuePersistence
import com.openminis.app.session.InputAttachment
import com.openminis.app.ui.chat.QueuedPrompt
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [T-queue-disk-persistence] harness 提示词队列与 OpenMinis 宿主之间的唯一接缝。
 *
 * 为什么需要它：harness 是纯 JVM 库（禁 android/androidx），拿不到 `Context`、
 * `InputAttachment`（android.net.Uri）。宿主侧的排队提示词（用户在 agent 跑动
 * 中继续输入的那几条）此前只活在 `_promptQueue` 内存 StateFlow 里——进程被杀
 * （OOM、崩溃、用户划掉）即蒸发，用户看到的是"我发的话没了"。
 *
 * 本桥把每条排队提示词镜像到 `filesDir/prompt_queue/`（与会话工作区同盘不同
 * 目录，不经 SAF，模型不可见），消费时机与内存队列完全对齐：
 *
 *  - enqueue（`enqueuePrompt`）→ 落盘 PENDING；
 *  - 消费（`injectQueuedPromptsAsNewTurn` / `drainQueuedPrompts` 在
 *    `chatRepository.appendMessage` 成功后）→ confirmConsumed 删除；
 *  - 撤回（`removeQueuedPrompt` / `withdrawQueuedMessage`）→ cancel 删除；
 *  - 重启恢复（`loadSession`）→ list 重建 `_promptQueue` + 占位气泡；
 *  - 会话消亡 / 清空（`clearChat` / `deleteSession`）→ dropSession 删整份
 *    镜像文件（早期走 clear(FOLLOW_UP)，文件留成 {"records":[]} 空壳）。
 *
 * 全部 best-effort：磁盘队列失败绝不阻塞主流程（内存队列仍是运行时事实源，
 * 磁盘只是重启保险）。两段式 claim/confirm 故意不用——单进程单 ViewModel，
 * 内存队列本身就是"领走"，直接 list+confirm 即可；claim 中途崩溃会把提示词
 * 卡进"已领未确认"的恢复灰区，反而多一个状态要解释。镜像写本身仍是
 * fire-and-forget，但写入 Job 由 [launchMirrorWrite] 记账、消费确认前 join
 * （见 [awaitPendingWrites]），入队-确认的落盘窗口是闭合的。
 */
object PromptQueueBridge {

    private const val DIR = "prompt_queue"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 按 filesDir 绝对路径缓存 manager。
     *
     * 为什么不是单个 @Volatile 字段：宿主 filesDir 全进程唯一，但本桥还暴露了
     * 纯 JVM 面（测试各传自己的临时目录）。「第一个目录赢」的单例会让后续调用
     * 静默共享同一份队列状态——那种失败长得像特性 bug，查起来极贵。生产路径
     * 恒定 → map 里始终只有一项，与单例等价。
     */
    private val managers = ConcurrentHashMap<String, PromptQueueManager<QueuedPromptPayload>>()

    /** 进程内 per-filesDir 单例 manager；首次调用时装配磁盘持久化。 */
    fun manager(filesDir: File): PromptQueueManager<QueuedPromptPayload> =
        managers.computeIfAbsent(filesDir.absolutePath) {
            PromptQueueManager(
                FilePromptQueuePersistence(File(filesDir, DIR)),
                QueuedPromptPayload.serializer(),
                json,
            )
        }

    /**
     * [T-queue-mirror-write-race] 每 filesDir 的在途镜像写 Job 台账。
     *
     * 宿主侧的镜像写是 fire-and-forget（enqueuePrompt 在主线程，不能为一次
     * 小文件写阻塞它——这正是当初做成 async 的原因），但工具边界的消费确认
     * 可能在入队后几 ms 就到：若 confirm 的扫描跑在记录落盘之前，晚到的写
     * 会把已消费的条目在盘上复活（重启后还原出幽灵气泡）。confirm / cancel
     * / dropSession 动文件前先 join 同一 filesDir 的全部在途写，窗口闭合。
     */
    private val pendingWrites = ConcurrentHashMap<String, MutableSet<Job>>()

    /**
     * Fire-and-forget 一次镜像写，并把它的 Job 记入 [pendingWrites]。
     * 宿主侧所有镜像 IO（enqueue / cancel / dropSession）从这里走；Job 完成
     * 后自行出账，台账不积攒。
     */
    fun launchMirrorWrite(filesDir: File, scope: CoroutineScope, block: suspend () -> Unit): Job {
        val ledger = pendingWrites.computeIfAbsent(filesDir.absolutePath) { ConcurrentHashMap.newKeySet<Job>() }
        val job = scope.launch { block() }
        ledger.add(job)
        job.invokeOnCompletion { ledger.remove(job) }
        return job
    }

    /**
     * join 同一 filesDir 的全部在途镜像写（快照后逐个 join；join 期间新到的
     * 写属于确认之后才入队的提示词，必须留给磁盘，不能等）。
     *
     * confirm / cancel / dropSession 的内部版在动文件前先走这一步；公开出来
     * 供会话切换交接（ChatViewModelQueueDiskExt.onActiveSessionChanged）在
     * cancel+enqueue 对账前先排干在途写，防同 id 双写。
     */
    suspend fun awaitPendingWrites(filesDir: File) {
        val ledger = pendingWrites[filesDir.absolutePath] ?: return
        for (job in ledger.toList()) job.join()
    }

    // ─── 落盘 DTO ───────────────────────────────────────────────────────

    /** 磁盘镜像格式。附件五元组平行数组（id/名称/uri/mime/是否图片）。 */
    @Serializable
    data class QueuedPromptPayload(
        val text: String = "",
        val attachmentIds: List<String> = emptyList(),
        val attachmentNames: List<String> = emptyList(),
        val attachmentUris: List<String> = emptyList(),
        val attachmentMimeTypes: List<String> = emptyList(),
        val attachmentIsImages: List<Boolean> = emptyList(),
        val createdAt: Long = 0,
    ) {
        /** 还原宿主 QueuedPrompt（Uri.parse 重建 android.net.Uri）。 */
        fun toQueuedPrompt(id: String): QueuedPrompt {
            val attachments = attachmentIds.indices.mapNotNull { i ->
                runCatching {
                    InputAttachment(
                        id = attachmentIds[i],
                        fileName = attachmentNames.getOrElse(i) { "attachment" },
                        uri = android.net.Uri.parse(attachmentUris.getOrElse(i) { "" }),
                        mimeType = attachmentMimeTypes.getOrElse(i) { "*/*" },
                        kind = if (attachmentIsImages.getOrElse(i) { false }) {
                            InputAttachment.Kind.IMAGE
                        } else {
                            InputAttachment.Kind.DOCUMENT
                        },
                    )
                }.getOrNull()
            }
            return QueuedPrompt(id = id, text = text, attachments = attachments)
        }

        companion object {
            fun from(prompt: QueuedPrompt): QueuedPromptPayload = QueuedPromptPayload(
                text = prompt.text,
                attachmentIds = prompt.attachments.map { it.id },
                attachmentNames = prompt.attachments.map { it.fileName },
                attachmentUris = prompt.attachments.map { it.uri.toString() },
                attachmentMimeTypes = prompt.attachments.map { it.mimeType },
                attachmentIsImages = prompt.attachments.map { it.isImage },
                createdAt = System.currentTimeMillis(),
            )
        }
    }

    // ─── 宿主操作面（全部 best-effort，IO 线程） ─────────────────────────

    /** 排队时镜像落盘。id 用宿主 prompt.id，恢复时才能对上撤回语义。 */
    suspend fun enqueue(context: Context, sessionId: String, prompt: QueuedPrompt) {
        withContext(Dispatchers.IO) {
            runCatching {
                manager(context.filesDir).enqueue(
                    sessionId = sessionId,
                    queue = PromptQueue.FOLLOW_UP,
                    payload = QueuedPromptPayload.from(prompt),
                    itemId = prompt.id,
                )
            }
        }
    }

    /** 撤回单条（用户点掉占位气泡 / 长按撤回）。 */
    suspend fun cancel(context: Context, promptId: String) {
        withContext(Dispatchers.IO) {
            awaitPendingWrites(context.filesDir) // [T-queue-mirror-write-race]
            runCatching { manager(context.filesDir).cancelById(promptId) }
        }
    }

    /** 消费确认：内存队列已注入为真实用户消息（appendMessage 成功后调用）。 */
    suspend fun confirm(context: Context, sessionId: String, promptIds: List<String>) {
        if (promptIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            // [T-queue-mirror-write-race] 先 join 在途镜像写，再扫描删除——
            // 否则工具边界的确认（入队后几 ms 就到）会跑赢它要消费的那条
            // 记录的落盘，晚到的写把条目在盘上复活。
            awaitPendingWrites(context.filesDir)
            runCatching { manager(context.filesDir).confirmConsumed(promptIds) }
        }
    }

    /** 重启恢复：返回该会话所有未消费提示词（按 createdAt 升序）。 */
    suspend fun restore(context: Context, sessionId: String): List<QueuedPrompt> =
        withContext(Dispatchers.IO) {
            runCatching {
                manager(context.filesDir).list(sessionId, PromptQueue.FOLLOW_UP)
                    .sortedBy { it.payload.createdAt }
                    .map { it.payload.toQueuedPrompt(it.id) }
            }.getOrDefault(emptyList())
        }

    /**
     * 会话消亡（deleteSession）/ 会话清空（clearChat）时清空其磁盘队列。
     * [T-queue-disk-persistence] 走整份文件删除而不是 clear(FOLLOW_UP)：
     * clear 只清一个队列类型、文件留下（{"records":[]} 空壳 + 非 FOLLOW_UP
     * 条目跟着会话死不掉）；drop 面向「队列事实源归零」，文件不留壳。
     */
    suspend fun dropSession(context: Context, sessionId: String) {
        withContext(Dispatchers.IO) {
            awaitPendingWrites(context.filesDir) // [T-queue-mirror-write-race]
            runCatching { manager(context.filesDir).dropSession(sessionId) }
        }
    }

    // ─── 纯 JVM 面（测试用，绕开 Context） ─────────────────────────────

    suspend fun enqueue(filesDir: File, sessionId: String, prompt: QueuedPrompt) {
        withContext(Dispatchers.IO) {
            runCatching {
                manager(filesDir).enqueue(
                    sessionId, PromptQueue.FOLLOW_UP, QueuedPromptPayload.from(prompt), itemId = prompt.id,
                )
            }
        }
    }

    suspend fun restore(filesDir: File, sessionId: String): List<QueuedPrompt> =
        withContext(Dispatchers.IO) {
            runCatching {
                manager(filesDir).list(sessionId, PromptQueue.FOLLOW_UP)
                    .sortedBy { it.payload.createdAt }
                    .map { it.payload.toQueuedPrompt(it.id) }
            }.getOrDefault(emptyList())
        }

    suspend fun confirm(filesDir: File, sessionId: String, promptIds: List<String>) {
        if (promptIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            awaitPendingWrites(filesDir) // [T-queue-mirror-write-race]
            runCatching { manager(filesDir).confirmConsumed(promptIds) }
        }
    }

    suspend fun cancel(filesDir: File, promptId: String) {
        withContext(Dispatchers.IO) {
            awaitPendingWrites(filesDir) // [T-queue-mirror-write-race]
            runCatching { manager(filesDir).cancelById(promptId) }
        }
    }

    suspend fun dropSession(filesDir: File, sessionId: String) {
        withContext(Dispatchers.IO) {
            awaitPendingWrites(filesDir) // [T-queue-mirror-write-race]
            runCatching { manager(filesDir).dropSession(sessionId) }
        }
    }
}
