package com.openminis.app.harness.queue

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Adapted from taixu queue persistence semantics (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 默认文件持久化：`<root>/<sessionId>.json` 单文件承载该会话全部 lane /
 * 队列的条目。写走临时文件 + 原子改名，读侧容忍旧格式（未知字段忽略）。
 * 根目录由宿主指定（应用私有目录，不经 SAF，模型不可见）。
 */
class FilePromptQueuePersistence(private val root: File) : PromptQueuePersistence {

    @Serializable
    private data class SessionFile(val records: List<QueueRecord> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val fileLock = Mutex()

    private fun fileFor(sessionId: String): File {
        // 会话 id 可能含路径分隔符（lane 会话 id 形如 lane:<owner>:<name>），
        // 也可能来自 deep link（不可信）。统一按 UTF-8 字节做百分号式转义：
        //   安全字符（字母数字与 . - ）原样；'_' → "__"；其余字节 → "_" + 两位 hex。
        // 转义 '_' 自身是单射性的关键——否则原始 id "_2f" 会与 '/' 的转义
        // 结果 "_2f" 撞同一个文件，两个会话共享一份队列（串味 + 误删）。
        // 顺带把 '/' 挡在文件名之外，id 再怪也逃不出 root。
        val safe = buildString(sessionId.length + 8) {
            for (b in sessionId.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                when {
                    c == '_' -> append("__")
                    c.isLetterOrDigit() || c == '.' || c == '-' -> append(c)
                    else -> append('_').append("%02x".format(b))
                }
            }
        }
        return File(root, "$safe.json")
    }

    /** root 下的全部会话文件（`.tmp` 兄弟文件被后缀天然过滤掉）。 */
    private fun sessionFiles(): List<File> =
        root.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()

    /**
     * 读写单个会话文件（串行化 + 原子改名）。
     *
     * 以 [File] 为键而不是 sessionId：`cancel` / `confirmConsumed` 只有 itemId，
     * 是靠扫目录反查的，拿到的已经是**转义后**的文件名。若把它再喂回 [fileFor]
     * 做二次转义（'_' → "__"），就会指向一个不存在的文件、静默什么都不删。
     */
    private suspend fun <R> withRawFile(file: File, block: suspend (MutableList<QueueRecord>) -> R): R =
        fileLock.withLock {
            val records = if (file.isFile) {
                runCatching { json.decodeFromString(SessionFile.serializer(), file.readText(Charsets.UTF_8)).records }
                    .getOrElse {
                        // 损坏文件按空处理：队列是 best-effort 持久层，宁可丢队列不可挡聊天。
                        System.err.println("Prompt queue file unreadable for ${file.name}: ${it.message}")
                        emptyList()
                    }
            } else {
                emptyList()
            }
            val mutable = records.toMutableList()
            val result = block(mutable)
            if (mutable != records) {
                root.mkdirs()
                val tmp = File(root, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(SessionFile.serializer(), SessionFile(mutable)), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    check(tmp.renameTo(file)) { "atomic rename failed for ${file.name}" }
                }
            }
            result
        }

    private suspend fun <R> withFile(sessionId: String, block: suspend (MutableList<QueueRecord>) -> R): R =
        withRawFile(fileFor(sessionId), block)

    override suspend fun enqueue(record: QueueRecord) = withFile(record.sessionId) { it += record }

    override suspend fun list(sessionId: String, laneName: String, queue: PromptQueue) =
        withFile(sessionId) { list ->
            list.filter { it.laneName == laneName && it.queueType == queue.id && it.claimedAt == null }
        }

    override suspend fun listAll(sessionId: String, laneName: String) =
        withFile(sessionId) { list ->
            list.filter { it.laneName == laneName && it.claimedAt == null }
        }

    override suspend fun cancel(itemId: String) {
        // cancel 只知道 itemId，不知道 sessionId —— 扫全部会话文件。
        // 单条 id 全局唯一，命中即止。
        for (file in sessionFiles()) {
            val removed = withRawFile(file) { list ->
                val before = list.size
                list.removeAll { it.id == itemId }
                before != list.size
            }
            if (removed) return
        }
    }

    override suspend fun clear(sessionId: String, laneName: String, queue: PromptQueue) {
        withFile(sessionId) { list -> list.removeAll { it.laneName == laneName && it.queueType == queue.id } }
    }

    override suspend fun claim(sessionId: String, laneName: String, queue: PromptQueue, limit: Int) =
        withFile(sessionId) { list ->
            val targets = list.filter {
                it.laneName == laneName && it.queueType == queue.id && it.claimedAt == null
            }.take(limit)
            targets.forEach { target ->
                val idx = list.indexOfFirst { it.id == target.id }
                if (idx >= 0) list[idx] = target.copy(claimedAt = System.currentTimeMillis())
            }
            targets
        }

    override suspend fun confirmConsumed(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        // 不能像 cancel 那样「命中一个文件就返回」：itemIds 是一批，理论上
        // 可以跨会话（宿主的队列若不带会话归属，消费时的 sessionId 未必等于
        // 入队时的）。按 id 全量扫，语义才与「确认这一批已消费」相符。
        for (file in sessionFiles()) {
            withRawFile(file) { list -> list.removeAll { it.id in itemIds } }
        }
    }

    override suspend fun restoreUnconfirmed(sessionId: String): Int =
        withFile(sessionId) { list ->
            var restored = 0
            for (i in list.indices) {
                if (list[i].claimedAt != null) {
                    list[i] = list[i].copy(claimedAt = null)
                    restored++
                }
            }
            restored
        }

    /**
     * 会话删除时清理：整份会话文件删除，幂等。
     *
     * [T-queue-disk-persistence] 早期这条链走 clear(FOLLOW_UP)：文件留下，
     * 每个有排队历史的已删会话都在 root 里积累一个 {"records":[]} 空 JSON，
     * claimed / 非 FOLLOW_UP 条目也跟着会话死不掉。现在 drop 面是活代码
     * （PromptQueueManager.dropSession ← PromptQueueBridge.dropSession）。
     */
    override suspend fun dropSession(sessionId: String) {
        fileLock.withLock { fileFor(sessionId).delete() }
    }
}
