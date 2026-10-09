package com.openminis.app.harness.queue

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Adapted from taixu PromptQueueManager (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * taixu 的实现绑定其 Room HarnessRuntimeRepository 与 entry-tree
 * SessionTreeStore（consume 在 Room 事务里原子完成"出队 + 追加 entry +
 * 移动叶子"）。本移植保留队列语义（持久化队列 + 显式消费时机 + per-lane
 * 串行化），把存储收窄成 [PromptQueuePersistence] 接缝，并把 payload
 * 泛型化——宿主可以排队比 taixu PendingMessage 更丰富的提示词（如带
 * 附件），无需分叉核心。
 *
 * 原子性对齐：taixu 靠 Room 事务；这里用两段式 claim/confirm——
 * [claim] 把条目标记为 claimed（不再出现在 list 里，但仍在盘上），
 * [confirmConsumed] 才真正删除。宿主在 claim 与 confirm 之间崩溃时，
 * [restoreUnconfirmed] 把未确认的领条放回队列，提示词不丢。
 */

enum class PromptQueue(val id: String) {
    /** 打断当前运行，尽快注入。 */
    STEER("steer"),

    /** 当前轮结束后作为新用户轮注入。 */
    FOLLOW_UP("follow_up"),

    /** 下一次显式运行时才消费。 */
    NEXT_RUN("next_run"),
}

/** 持久化队列条目。payload 以 JSON 字符串存储，由 manager 解码。 */
@Serializable
data class QueueRecord(
    val id: String,
    val sessionId: String,
    val laneName: String,
    val queueType: String,
    val createdAt: Long,
    val payloadJson: String,
    /** claim 时间戳；非 null 表示已领走待确认（list 不返回）。 */
    val claimedAt: Long? = null,
)

/**
 * 队列存储接缝（对应 taixu HarnessRuntimeRepository 的队列操作面）。
 * 实现必须保证：同 session+lane 的写操作串行化（manager 已按 lane 加锁，
 * 实现侧无需再锁，但不得引入跨 lane 的写竞争）。
 */
interface PromptQueuePersistence {
    suspend fun enqueue(record: QueueRecord)

    suspend fun list(sessionId: String, laneName: String, queue: PromptQueue): List<QueueRecord>

    suspend fun listAll(sessionId: String, laneName: String): List<QueueRecord>

    suspend fun cancel(itemId: String)

    suspend fun clear(sessionId: String, laneName: String, queue: PromptQueue)

    /** 领取：标记 claimedAt 并返回领走的条目（保持队列顺序）。 */
    suspend fun claim(sessionId: String, laneName: String, queue: PromptQueue, limit: Int): List<QueueRecord>

    /** 确认消费：真正删除。 */
    suspend fun confirmConsumed(itemIds: List<String>)

    /** 把 claimed 但未 confirm 的条目放回队列（崩溃恢复）。返回放回条数。 */
    suspend fun restoreUnconfirmed(sessionId: String): Int
}

/** 解码后的队列项，面向队列感知 UI。 */
data class QueuedItem<T>(
    val id: String,
    val queue: PromptQueue,
    val payload: T,
)

/**
 * Durable prompt queues with explicit consumption timing.
 *
 * 所有操作以 [laneName] 定位队列（默认主 lane）；子智能体等独立 lane
 * 可通过显式传参获得同等的持久化队列能力。
 */
class PromptQueueManager<T>(
    private val persistence: PromptQueuePersistence,
    private val serializer: KSerializer<T>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val laneLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <R> withLaneLock(sessionId: String, laneName: String, block: suspend () -> R): R {
        val mutex = laneLocks.compute("$sessionId/$laneName") { _, current -> current ?: Mutex() }!!
        return mutex.withLock { block() }
    }

    /** 对齐 taixu 的 decode-or-cancel：坏条目直接移除，不阻塞队列。 */
    private suspend fun decodeOrCancel(record: QueueRecord): T? {
        val decoded = runCatching { json.decodeFromString(serializer, record.payloadJson) }
        if (decoded.isFailure) {
            System.err.println("Removing invalid queued prompt ${record.id}: ${decoded.exceptionOrNull()?.message}")
            persistence.cancel(record.id)
            return null
        }
        return decoded.getOrNull()
    }

    suspend fun enqueue(
        sessionId: String,
        queue: PromptQueue,
        payload: T,
        laneName: String = MAIN_LANE,
        itemId: String? = null,
    ): String = withLaneLock(sessionId, laneName) {
        val id = itemId ?: UUID.randomUUID().toString()
        persistence.enqueue(
            QueueRecord(
                id = id,
                sessionId = sessionId,
                laneName = laneName,
                queueType = queue.id,
                createdAt = System.currentTimeMillis(),
                payloadJson = json.encodeToString(serializer, payload),
            ),
        )
        id
    }

    suspend fun list(
        sessionId: String,
        queue: PromptQueue,
        laneName: String = MAIN_LANE,
    ): List<QueuedItem<T>> = persistence.list(sessionId, laneName, queue).mapNotNull { record ->
        decodeOrCancel(record)?.let { QueuedItem(record.id, queue, it) }
    }

    suspend fun first(
        sessionId: String,
        queue: PromptQueue,
        laneName: String = MAIN_LANE,
    ): QueuedItem<T>? = list(sessionId, queue, laneName).firstOrNull()

    suspend fun listAll(
        sessionId: String,
        laneName: String = MAIN_LANE,
    ): List<QueuedItem<T>> = persistence.listAll(sessionId, laneName).mapNotNull { record ->
        val queue = PromptQueue.entries.firstOrNull { it.id == record.queueType }
        if (queue == null) {
            System.err.println("Removing queued prompt ${record.id} with unknown queue type ${record.queueType}")
            persistence.cancel(record.id)
            return@mapNotNull null
        }
        decodeOrCancel(record)?.let { QueuedItem(record.id, queue, it) }
    }

    /** 按可见序号移除（对齐 taixu 的 cancel(index)）。 */
    suspend fun cancel(sessionId: String, queue: PromptQueue, index: Int, laneName: String = MAIN_LANE) {
        list(sessionId, queue, laneName).getOrNull(index)?.let { persistence.cancel(it.id) }
    }

    suspend fun cancelById(itemId: String) {
        persistence.cancel(itemId)
    }

    suspend fun clear(sessionId: String, queue: PromptQueue, laneName: String = MAIN_LANE) {
        persistence.clear(sessionId, laneName, queue)
    }

    /**
     * 领取队列项（两段式消费第一步）。领走后条目不再出现在 [list]，
     * 但仍在盘上；宿主完成真正的消息落库后必须 [confirmConsumed]。
     * 崩溃恢复用 [restoreUnconfirmed]。
     */
    suspend fun claim(
        sessionId: String,
        queue: PromptQueue,
        limit: Int = Int.MAX_VALUE,
        laneName: String = MAIN_LANE,
    ): List<QueuedItem<T>> = withLaneLock(sessionId, laneName) {
        persistence.claim(sessionId, laneName, queue, limit).mapNotNull { record ->
            decodeOrCancel(record)?.let { QueuedItem(record.id, queue, it) }
        }
    }

    /** 两段式消费第二步：确认领走的条目已安全落地，删除。 */
    suspend fun confirmConsumed(itemIds: List<String>) {
        if (itemIds.isNotEmpty()) persistence.confirmConsumed(itemIds)
    }

    /** 崩溃恢复：把 claim 后未 confirm 的条目放回队列。返回放回条数。 */
    suspend fun restoreUnconfirmed(sessionId: String): Int = persistence.restoreUnconfirmed(sessionId)

    companion object {
        const val MAIN_LANE = "main"
    }
}
