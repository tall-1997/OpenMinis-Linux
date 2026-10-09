package com.openminis.app.harness.session

import com.openminis.app.harness.HarnessLanes
import com.openminis.app.harness.HarnessLog
import com.openminis.app.harness.AssistantText
import com.openminis.app.harness.CapabilityEvent
import com.openminis.app.harness.HarnessMessage
import com.openminis.app.harness.ModelSwitchEvent
import com.openminis.app.harness.SkillSuggestion
import com.openminis.app.harness.ToolCall
import com.openminis.app.harness.ToolResult
import com.openminis.app.harness.UserMessage
import com.openminis.app.harness.model.HarnessEntryEntity
import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Adapted from taixu SessionTreeStore (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 不可变会话树的序列化与活动分支投影：append 只追加节点并移动 lane 叶子，
 * rewind / fork 只动指针，被放弃的分支永远保留在盘上。
 *
 * 与上游的差异只有接缝：AppLogger → [HarnessLog]，Room 仓储 →
 * [SessionTreeRepository]，MAIN_LANE → [HarnessLanes.MAIN_LANE]。
 * 并发模型原样保留：per-(session,lane) 的**保留锁**（计数等待者，最后一个
 * 离开才摘除 mutex，避免新调用者拿到另一把锁而旧等待者还排在旧锁上），
 * 以及解码分支的 SoftReference LRU（同 leaf 命中免重复解码）。
 */
class SessionTreeStore(
    private val repository: SessionTreeRepository,
    private val json: Json,
) {
    private class LaneLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val laneLocks = ConcurrentHashMap<String, LaneLock>()
    private data class CachedBranch(val leafId: String?, val messages: SoftReference<List<HarnessMessage>>)
    private val decodedBranches = LinkedHashMap<String, CachedBranch>(8, 0.75f, true)

    private suspend fun decodedBranch(sessionId: String, leafId: String?): List<HarnessMessage> {
        synchronized(decodedBranches) {
            decodedBranches[sessionId]?.takeIf { it.leafId == leafId }?.messages?.get()?.let { return it }
        }
        val loaded = repository.branch(sessionId, leafId).mapNotNull(::decode)
        synchronized(decodedBranches) {
            decodedBranches[sessionId] = CachedBranch(leafId, SoftReference(loaded))
            if (decodedBranches.size > MAX_DECODED_BRANCHES) {
                decodedBranches.remove(decodedBranches.keys.first())
            }
        }
        return loaded
    }

    private suspend fun <T> withRetainedLaneLock(sessionId: String, laneName: String, block: suspend () -> T): T {
        val key = "$sessionId/$laneName"
        val holder = laneLocks.compute(key) { _, current ->
            (current ?: LaneLock()).also { it.users++ }
        }!!
        try {
            return holder.mutex.withLock { block() }
        } finally {
            // 把等待者也算作用户。最后一个持有者离开后才摘除，于是新调用者
            // 不可能在旧等待者还排队时拿到另一把 mutex。
            laneLocks.compute(key) { _, current ->
                if (current !== holder) current else holder.takeIf { --it.users > 0 }
            }
        }
    }

    suspend fun ensureMainLane(sessionId: String) {
        repository.ensureLane(sessionId, MAIN_LANE)
    }

    /** 当前 lane 的叶子条目 id（无 lane 或空 lane 时为 null）。 */
    suspend fun laneLeafId(sessionId: String, laneName: String = MAIN_LANE): String? =
        runCatching { repository.findLane(sessionId, laneName)?.leafId }.getOrNull()

    suspend fun load(sessionId: String, laneName: String = MAIN_LANE): List<HarnessMessage> = runCatching {
        val lane = repository.ensureLane(sessionId, laneName)
        repository.branchTail(sessionId, lane.leafId, MAX_LIVE_ENTRIES).mapNotNull(::decode)
    }.onFailure { throwable ->
        HarnessLog.e("Failed to load harness branch for $sessionId/$laneName: ${throwable.message}", throwable)
    }.getOrDefault(emptyList())

    suspend fun loadAt(sessionId: String, leafId: String?): List<HarnessMessage> = runCatching {
        repository.branchTail(sessionId, leafId, MAX_LIVE_ENTRIES).mapNotNull(::decode)
    }.onFailure { throwable ->
        HarnessLog.e("Failed to load harness branch at $sessionId/$leafId: ${throwable.message}", throwable)
    }.getOrDefault(emptyList())

    suspend fun append(sessionId: String, message: HarnessMessage, laneName: String = MAIN_LANE) {
        withRetainedLaneLock(sessionId, laneName) {
            val lane = repository.ensureLane(sessionId, laneName)
            val entry = HarnessEntryEntity(
                id = message.id,
                sessionId = sessionId,
                parentId = lane.leafId,
                createdAt = message.createdAt,
                entryType = "message",
                customType = messageType(message),
                payloadJson = json.encodeToString(HarnessMessage.serializer(), message),
            )
            repository.appendToLane(sessionId, laneName, entry)
        }
    }

    /**
     * 持久化某条用户消息的前缀块（recall_context entry，紧随该用户消息追加）。
     *
     * 块内容 = 记忆召回 + 任务计划看板：二者都是逐轮变化的内容，合并成同一段
     * 字节挂在该轮上，轮内冻结。entry id 由 userMessageId 确定性推导，幂等性由
     * 存储层语义保证（同 id 同 payload 复用；同 id 不同 payload 派生新 id）。
     * 该 entry 不解码进消息流（entryType 不是 "message"），仅由压缩投影读出并
     * 映射到 provider 投影的对应 user 消息后缀；UI 与检索路径天然不可见。
     *
     * @return 是否成功持久化。调用方必须在 false 时放弃挂载——未持久化的字节
     *   一旦进入投影，下一轮组装便会漂移，违背 prefix cache 稳定性契约。
     */
    suspend fun appendRecallBlock(
        sessionId: String,
        userMessageId: String,
        block: String,
        laneName: String = MAIN_LANE,
    ): Boolean {
        if (block.isBlank()) return false
        return withRetainedLaneLock(sessionId, laneName) {
            runCatching {
                val lane = repository.ensureLane(sessionId, laneName)
                val entry = HarnessEntryEntity(
                    id = RECALL_ENTRY_PREFIX + userMessageId,
                    sessionId = sessionId,
                    parentId = lane.leafId,
                    createdAt = System.currentTimeMillis(),
                    entryType = RECALL_ENTRY_TYPE,
                    customType = userMessageId,
                    payloadJson = block,
                )
                repository.appendToLane(sessionId, laneName, entry)
            }.onFailure {
                HarnessLog.w("Failed to persist recall block for $userMessageId: ${it.message}")
            }.isSuccess
        }
    }

    /**
     * 供压缩等「读快照-落库」协作方复用同一把 per-lane 锁：保证 compaction entry
     * 的重读与写入和本 lane 的 append 串行化。锁内不得执行长耗时挂起调用（如 LLM）。
     */
    suspend fun <T> withLaneLock(sessionId: String, laneName: String = MAIN_LANE, block: suspend () -> T): T =
        withRetainedLaneLock(sessionId, laneName, block)

    /** 导航到 [entryId] 的 parent，被放弃的分支保留。 */
    suspend fun rewindBefore(sessionId: String, entryId: String, laneName: String = MAIN_LANE) {
        withRetainedLaneLock(sessionId, laneName) {
            val target = repository.findEntry(sessionId, entryId) ?: return@withRetainedLaneLock
            repository.moveLane(sessionId, laneName, target.parentId)
        }
    }

    suspend fun moveTo(sessionId: String, entryId: String?, laneName: String = MAIN_LANE) {
        withRetainedLaneLock(sessionId, laneName) {
            repository.moveLane(sessionId, laneName, entryId)
        }
    }

    suspend fun deleteSession(sessionId: String) {
        repository.deleteSessionData(sessionId)
        synchronized(decodedBranches) { decodedBranches.remove(sessionId) }
    }

    data class SearchHit(val message: HarnessMessage, val index: Int)

    suspend fun search(sessionId: String, query: String, limit: Int = 8): List<HarnessMessage> =
        searchIndexed(sessionId, query, limit).map { it.message }

    /** 返回活动分支中的原始索引，与 read(index) 使用同一套编号。 */
    suspend fun searchIndexed(sessionId: String, query: String, limit: Int = 8): List<SearchHit> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = decodedBranch(sessionId, lane.leafId)
        val resultsByCallId = messages.filterIsInstance<ToolResult>().groupBy { it.toolCallId }
        val callsById = messages.filterIsInstance<ToolCall>().associateBy { it.id }
        val terms = needle.split(SEARCH_TERM_SEPARATOR).filter { it.isNotBlank() }

        return messages.withIndex().mapNotNull { (index, message) ->
            val relatedText = when (message) {
                is ToolCall -> resultsByCallId[message.id].orEmpty().joinToString("\n") { searchableText(it) }
                is ToolResult -> callsById[message.toolCallId]?.let(::searchableText).orEmpty()
                else -> ""
            }
            val haystack = searchableText(message) + "\n" + relatedText
            val exactMatch = haystack.contains(needle, ignoreCase = true)
            val matchedTerms = terms.count { haystack.contains(it, ignoreCase = true) }
            if (!exactMatch && (terms.isEmpty() || matchedTerms != terms.size)) null
            else SearchMatch(message, index, if (exactMatch) matchedTerms + 2 else matchedTerms)
        }.sortedWith(compareByDescending<SearchMatch> { it.score }.thenByDescending { it.index })
            .take(limit.coerceIn(1, 20))
            .map { SearchHit(it.message, it.index) }
    }

    suspend fun read(sessionId: String, messageId: String? = null, index: Int? = null): HarnessMessage? {
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = decodedBranch(sessionId, lane.leafId)
        return when {
            !messageId.isNullOrBlank() -> messages.firstOrNull { it.id == messageId }
            index != null && index >= 0 -> messages.getOrNull(index)
            else -> null
        }
    }

    /** 读一条活动分支消息，并把工具调用/结果这一对保持在一起。 */
    suspend fun readWithRelated(
        sessionId: String,
        messageId: String? = null,
        index: Int? = null,
    ): List<HarnessMessage> {
        val lane = repository.ensureLane(sessionId, MAIN_LANE)
        val messages = decodedBranch(sessionId, lane.leafId)
        val selected = when {
            !messageId.isNullOrBlank() -> messages.firstOrNull { it.id == messageId }
            index != null && index >= 0 -> messages.getOrNull(index)
            else -> null
        } ?: return emptyList()
        val relatedIds = when (selected) {
            is ToolCall -> messages.filterIsInstance<ToolResult>()
                .filter { it.toolCallId == selected.id }
                .mapTo(mutableSetOf(selected.id)) { it.id }
            is ToolResult -> mutableSetOf(selected.id, selected.toolCallId)
            else -> mutableSetOf(selected.id)
        }
        return messages.filter { it.id in relatedIds }
    }

    internal fun decode(entity: HarnessEntryEntity): HarnessMessage? {
        if (entity.entryType != "message") return null
        return runCatching { json.decodeFromString(HarnessMessage.serializer(), entity.payloadJson) }
            .onFailure { HarnessLog.w("Skipping invalid harness entry ${entity.id}: ${it.message}") }
            .getOrNull()
    }

    private fun messageType(message: HarnessMessage): String = when (message) {
        is UserMessage -> "user"
        is AssistantText -> "assistant"
        is ToolCall -> "tool_call"
        is ToolResult -> "tool_result"
        is CapabilityEvent -> "capability_event"
        is SkillSuggestion -> "skill_suggestion"
        is ModelSwitchEvent -> "model_switch"
    }

    private fun searchableText(message: HarnessMessage): String = when (message) {
        is CapabilityEvent -> "${message.kind} ${message.name} ${message.details}"
        is SkillSuggestion -> "${message.skillName} ${message.description}"
        is ModelSwitchEvent -> "${message.fromLabel} ${message.toLabel}"
        is UserMessage -> message.text
        is AssistantText -> "${message.text}\n${message.reasoning.orEmpty()}"
        is ToolCall -> "${message.rawToolName.orEmpty()} ${message.tool} ${message.args} ${message.reasoning.orEmpty()}"
        is ToolResult -> message.output
    }

    companion object {
        const val MAIN_LANE = HarnessLanes.MAIN_LANE
        private const val MAX_DECODED_BRANCHES = 4
        const val RECALL_ENTRY_PREFIX = "recall_"

        /** 记忆召回后缀 entry 类型：紧随用户轮持久化，project() 读出映射为该轮 provider 后缀。 */
        const val RECALL_ENTRY_TYPE = "recall_context"

        /** 每个活 UI / 会话投影保留的解码消息上限。 */
        const val MAX_LIVE_ENTRIES = 600
        private val SEARCH_TERM_SEPARATOR = Regex("[\\s,，;；|]+")
    }

    private data class SearchMatch(val message: HarnessMessage, val index: Int, val score: Int)
}
