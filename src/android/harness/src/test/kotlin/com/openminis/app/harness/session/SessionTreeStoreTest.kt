package com.openminis.app.harness.session

import com.openminis.app.harness.AssistantText
import com.openminis.app.harness.HarnessTool
import com.openminis.app.harness.ToolCall
import com.openminis.app.harness.ToolResult
import com.openminis.app.harness.UserMessage
import com.openminis.app.harness.model.HarnessEntryEntity
import com.openminis.app.harness.model.HarnessLaneEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-taixu-2.4-prereq] SessionTreeStore 的验收测试。上游 taixu 没有针对它的
 * 单测（session/ 下只有投影与装配类测试），这里按它的契约自定验收：
 * 追加成链、rewind 只动指针不删分支、读取编号与搜索打分、recall 块不进消息流、
 * 坏 entry 降级跳过、lane 与会话隔离。
 */
class SessionTreeStoreTest {

    private lateinit var repository: FakeSessionTreeRepository
    private lateinit var store: SessionTreeStore

    @Before
    fun setUp() {
        repository = FakeSessionTreeRepository()
        store = SessionTreeStore(repository, Json)
    }

    private fun user(id: String, text: String) = UserMessage(id = id, createdAt = 1L, text = text)

    private fun assistant(id: String, text: String) = AssistantText(id = id, createdAt = 2L, text = text)

    private fun textOf(message: com.openminis.app.harness.HarnessMessage): String =
        (message as? UserMessage)?.text ?: (message as? AssistantText)?.text ?: error("unexpected type")

    private fun texts(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): List<String> =
        runBlocking { store.load(sessionId, laneName).map(::textOf) }

    // ─── 追加与投影 ────────────────────────────────────────────────────

    @Test
    fun `append builds a parent chain and load returns root-first order`() = runBlocking {
        store.append("s1", user("u1", "one"))
        store.append("s1", assistant("a1", "two"))
        store.append("s1", user("u2", "three"))

        assertEquals("a1", repository.entry("u2")!!.parentId)
        assertEquals(listOf("one", "two", "three"), texts("s1"))
        assertEquals("u2", store.laneLeafId("s1"))
    }

    @Test
    fun `lanes are isolated projections over the same entry pool`() = runBlocking {
        store.append("s1", user("u1", "main-one"))
        store.append("s1", user("s1-sub", "sub-one"), laneName = "sub")

        assertEquals(listOf("main-one"), texts("s1"))
        assertEquals(listOf("sub-one"), texts("s1", "sub"))
    }

    // ─── rewind / moveTo：只动指针 ─────────────────────────────────────

    @Test
    fun `rewindBefore moves the leaf to the parent and keeps the abandoned branch`() = runBlocking {
        store.append("s1", user("u1", "one"))
        store.append("s1", assistant("a1", "two"))
        store.append("s1", user("u2", "three"))

        store.rewindBefore("s1", "u2")

        assertEquals("a1", store.laneLeafId("s1"))
        assertEquals(listOf("one", "two"), texts("s1"))
        // 被放弃的分支节点仍在盘上（fork / 重放依赖这一点）
        assertEquals(3, repository.entriesOf("s1").size)
    }

    @Test
    fun `rewindBefore on an unknown entry is a no-op`() = runBlocking {
        store.append("s1", user("u1", "one"))

        store.rewindBefore("s1", "ghost")

        assertEquals("u1", store.laneLeafId("s1"))
    }

    @Test
    fun `moveTo null empties the projection without deleting entries`() = runBlocking {
        store.append("s1", user("u1", "one"))

        store.moveTo("s1", null)

        assertTrue(store.load("s1").isEmpty())
        assertEquals(1, repository.entriesOf("s1").size)
    }

    // ─── 读取 ──────────────────────────────────────────────────────────

    @Test
    fun `read resolves by id then by index and misses return null`() = runBlocking {
        store.append("s1", user("u1", "one"))
        store.append("s1", assistant("a1", "two"))

        assertEquals("two", (store.read("s1", messageId = "a1") as AssistantText).text)
        assertEquals("one", (store.read("s1", index = 0) as UserMessage).text)
        assertNull(store.read("s1", messageId = "ghost"))
        assertNull(store.read("s1", index = 9))
        assertNull(store.read("s1"))
    }

    @Test
    fun `readWithRelated keeps a tool call and its results together`() = runBlocking {
        store.append("s1", user("u1", "go"))
        store.append("s1", toolCall("c1"))
        store.append("s1", toolResult("r1", "c1", "first"))
        store.append("s1", toolResult("r2", "c1", "second"))
        store.append("s1", assistant("a1", "done"))

        val fromCall = store.readWithRelated("s1", messageId = "c1").map { it.id }.toSet()
        assertEquals(setOf("c1", "r1", "r2"), fromCall)

        val fromResult = store.readWithRelated("s1", messageId = "r2").map { it.id }.toSet()
        assertEquals(setOf("r2", "c1"), fromResult)

        assertEquals(listOf("a1"), store.readWithRelated("s1", messageId = "a1").map { it.id })
    }

    // ─── 搜索 ──────────────────────────────────────────────────────────

    @Test
    fun `search requires every term unless the phrase matches exactly`() = runBlocking {
        store.append("s1", assistant("a1", "alpha beta gamma"))
        store.append("s1", assistant("b1", "beta only"))

        assertEquals(listOf("a1"), store.search("s1", "alpha beta").map { it.id })
        // 单词精确匹配两条都中；同分按索引倒序（更新的在前）
        assertEquals(listOf("b1", "a1"), store.search("s1", "beta").map { it.id })
        assertTrue(store.search("s1", "   ").isEmpty())
    }

    @Test
    fun `search reaches a tool call through its results text`() = runBlocking {
        store.append("s1", toolCall("c1"))
        store.append("s1", toolResult("r1", "c1", "nothing about zebras here"))

        assertEquals(setOf("c1", "r1"), store.search("s1", "zebras").map { it.id }.toSet())
    }

    // ─── recall 块 ─────────────────────────────────────────────────────

    @Test
    fun `recall block persists out of band and never enters the message stream`() = runBlocking {
        store.append("s1", user("u1", "one"))

        assertTrue(store.appendRecallBlock("s1", "u1", "memory: user prefers kotlin"))
        assertFalse("blank block must not persist", store.appendRecallBlock("s1", "u1", "  "))

        // 消息流看不见它（entryType 不是 message）
        assertEquals(listOf("one"), texts("s1"))
        // 但盘上确有一条 recall entry，customType 挂着用户消息 id
        val recall = repository.entriesOf("s1").single { it.entryType == SessionTreeStore.RECALL_ENTRY_TYPE }
        assertEquals("u1", recall.customType)
        assertEquals(SessionTreeStore.RECALL_ENTRY_PREFIX + "u1", recall.id)
    }

    // ─── 降级与隔离 ────────────────────────────────────────────────────

    @Test
    fun `non-message and corrupt entries are skipped not fatal`() = runBlocking {
        store.append("s1", user("u1", "one"))
        // 手工塞两条坏数据：类型不对 / payload 不是合法 HarnessMessage
        repository.rawAppend(
            HarnessEntryEntity(id = "x1", sessionId = "s1", parentId = "u1", createdAt = 3L, entryType = "compaction", payloadJson = "{}"),
        )
        repository.rawAppend(
            HarnessEntryEntity(id = "x2", sessionId = "s1", parentId = "x1", createdAt = 4L, entryType = "message", customType = "user", payloadJson = "{not json"),
        )
        store.append("s1", user("u2", "two"))

        assertEquals(listOf("one", "two"), texts("s1"))
    }

    @Test
    fun `deleteSession drops data and the decode cache`() = runBlocking {
        store.append("s1", user("u1", "one"))
        store.load("s1") // 填解码缓存

        store.deleteSession("s1")

        assertTrue(repository.entriesOf("s1").isEmpty())
        assertTrue(store.load("s1").isEmpty())
    }

    // ─── 道具 ──────────────────────────────────────────────────────────

    private fun toolCall(id: String) = ToolCall(
        id = id,
        createdAt = 3L,
        tool = HarnessTool.READ,
        args = JsonObject(emptyMap()),
        rawToolName = "grep",
    )

    private fun toolResult(id: String, callId: String, output: String) = ToolResult(
        id = id,
        createdAt = 4L,
        toolCallId = callId,
        success = true,
        output = output,
    )

    /** 全内存 fake：branch 走 parent 链，branchTail 取其有界后缀。 */
    private class FakeSessionTreeRepository : SessionTreeRepository {
        private val entries = mutableListOf<HarnessEntryEntity>()
        private val lanes = LinkedHashMap<Pair<String, String>, HarnessLaneEntity>()

        fun entriesOf(sessionId: String) = entries.filter { it.sessionId == sessionId }
        fun entry(id: String) = entries.firstOrNull { it.id == id }

        fun rawAppend(entry: HarnessEntryEntity) {
            entries += entry
        }

        override suspend fun ensureLane(sessionId: String, laneName: String): HarnessLaneEntity {
            lanes[sessionId to laneName]?.let { return it }
            val lane = HarnessLaneEntity(sessionId = sessionId, name = laneName, leafId = null, updatedAt = 0L)
            lanes[sessionId to laneName] = lane
            return lane
        }

        override suspend fun findLane(sessionId: String, laneName: String) = lanes[sessionId to laneName]

        override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> {
            if (leafId == null) return emptyList()
            val byId = entries.associateBy { it.id }
            val chain = ArrayList<HarnessEntryEntity>()
            var cursor: String? = leafId
            while (cursor != null) {
                val entry = byId[cursor] ?: error("Missing entry $cursor")
                chain += entry
                cursor = entry.parentId
            }
            return chain.asReversed()
        }

        override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int) =
            branch(sessionId, leafId).takeLast(limit)

        override suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity) {
            entries += entry
            lanes[sessionId to laneName] = lanes[sessionId to laneName]!!.copy(leafId = entry.id, updatedAt = entry.createdAt)
        }

        override suspend fun moveLane(sessionId: String, laneName: String, leafId: String?) {
            lanes[sessionId to laneName]?.let { lanes[sessionId to laneName] = it.copy(leafId = leafId) }
        }

        override suspend fun findEntry(sessionId: String, entryId: String) =
            entries.firstOrNull { it.sessionId == sessionId && it.id == entryId }

        override suspend fun deleteSessionData(sessionId: String) {
            entries.removeAll { it.sessionId == sessionId }
            lanes.keys.removeAll { it.first == sessionId }
        }
    }
}
