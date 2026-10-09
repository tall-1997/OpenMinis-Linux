package com.openminis.app.harness.queue

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapted from taixu queue semantics (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 覆盖：入队/列举/取消/清空、两段式 claim-confirm、崩溃恢复
 * restoreUnconfirmed、坏 payload 出队、未知队列类型出队、lane 隔离。
 */
class PromptQueueManagerTest {

    @Serializable
    private data class Payload(val text: String, val createdAt: Long = 0L)

    /** 内存实现，忠实模拟磁盘语义：claim 隐藏不删，confirm 才删。 */
    private class FakePersistence : PromptQueuePersistence {
        val records = LinkedHashMap<String, QueueRecord>()
        val cancelled = mutableListOf<String>()
        val confirmed = mutableListOf<String>()

        private fun visible(sessionId: String, laneName: String, queue: PromptQueue) =
            records.values.filter {
                it.sessionId == sessionId && it.laneName == laneName &&
                    it.queueType == queue.id && it.claimedAt == null
            }

        override suspend fun enqueue(record: QueueRecord) {
            records[record.id] = record
        }

        override suspend fun list(sessionId: String, laneName: String, queue: PromptQueue) =
            visible(sessionId, laneName, queue)

        override suspend fun listAll(sessionId: String, laneName: String) =
            records.values.filter { it.sessionId == sessionId && it.laneName == laneName && it.claimedAt == null }

        override suspend fun cancel(itemId: String) {
            records.remove(itemId)
            cancelled += itemId
        }

        override suspend fun clear(sessionId: String, laneName: String, queue: PromptQueue) {
            records.values.filter {
                it.sessionId == sessionId && it.laneName == laneName && it.queueType == queue.id
            }.forEach { records.remove(it.id) }
        }

        override suspend fun claim(sessionId: String, laneName: String, queue: PromptQueue, limit: Int) =
            visible(sessionId, laneName, queue).take(limit).onEach {
                records[it.id] = it.copy(claimedAt = System.currentTimeMillis())
            }

        override suspend fun confirmConsumed(itemIds: List<String>) {
            itemIds.forEach { records.remove(it) }
            confirmed += itemIds
        }

        override suspend fun restoreUnconfirmed(sessionId: String): Int {
            val stale = records.values.filter { it.sessionId == sessionId && it.claimedAt != null }
            stale.forEach { records[it.id] = it.copy(claimedAt = null) }
            return stale.size
        }
    }

    private fun manager(persistence: PromptQueuePersistence = FakePersistence()) =
        PromptQueueManager<Payload>(persistence, Payload.serializer())

    @Test
    fun `enqueue then list returns payload in order`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("first"))
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("second"))
        val items = m.list("s1", PromptQueue.FOLLOW_UP)
        assertEquals(listOf("first", "second"), items.map { it.payload.text })
        assertEquals(2, items.size)
    }

    @Test
    fun `queues are isolated by type`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.STEER, Payload("steer-me"))
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("after"))
        assertEquals("steer-me", m.first("s1", PromptQueue.STEER)?.payload?.text)
        assertEquals("after", m.first("s1", PromptQueue.FOLLOW_UP)?.payload?.text)
        assertNull(m.first("s1", PromptQueue.NEXT_RUN))
    }

    @Test
    fun `lanes are isolated`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("main-lane"), laneName = "main")
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("sub-lane"), laneName = "sub")
        assertEquals(listOf("main-lane"), m.list("s1", PromptQueue.FOLLOW_UP).map { it.payload.text })
        assertEquals(listOf("sub-lane"), m.list("s1", PromptQueue.FOLLOW_UP, "sub").map { it.payload.text })
    }

    @Test
    fun `sessions are isolated`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("one"))
        m.enqueue("s2", PromptQueue.FOLLOW_UP, Payload("two"))
        assertEquals(listOf("one"), m.list("s1", PromptQueue.FOLLOW_UP).map { it.payload.text })
        assertTrue(m.listAll("s2").all { it.payload.text == "two" })
    }

    @Test
    fun `listAll labels queue type`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.STEER, Payload("a"))
        m.enqueue("s1", PromptQueue.NEXT_RUN, Payload("b"))
        val byQueue = m.listAll("s1").associate { it.payload.text to it.queue }
        assertEquals(PromptQueue.STEER, byQueue["a"])
        assertEquals(PromptQueue.NEXT_RUN, byQueue["b"])
    }

    @Test
    fun `cancel removes by visible index`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("first"))
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("second"))
        m.cancel("s1", PromptQueue.FOLLOW_UP, 0)
        assertEquals(listOf("second"), m.list("s1", PromptQueue.FOLLOW_UP).map { it.payload.text })
    }

    @Test
    fun `cancelById removes regardless of visibility`() = runTest {
        val m = manager()
        val id = m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("gone"))
        m.cancelById(id)
        assertTrue(m.list("s1", PromptQueue.FOLLOW_UP).isEmpty())
    }

    @Test
    fun `clear empties one queue type only`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.STEER, Payload("steer"))
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("follow"))
        m.clear("s1", PromptQueue.STEER)
        assertNull(m.first("s1", PromptQueue.STEER))
        assertNotNull(m.first("s1", PromptQueue.FOLLOW_UP))
    }

    @Test
    fun `claim hides items until confirmed`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("one"))
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("two"))
        val claimed = m.claim("s1", PromptQueue.FOLLOW_UP)
        assertEquals(listOf("one", "two"), claimed.map { it.payload.text })
        // 领走后不可见
        assertTrue(m.list("s1", PromptQueue.FOLLOW_UP).isEmpty())
        // 但未确认前仍在盘上（listAll 也不可见，restore 能找回）
        assertEquals(2, m.restoreUnconfirmed("s1"))
        assertEquals(listOf("one", "two"), m.list("s1", PromptQueue.FOLLOW_UP).map { it.payload.text })
    }

    @Test
    fun `claim with limit takes prefix only`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("one"))
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("two"))
        val claimed = m.claim("s1", PromptQueue.FOLLOW_UP, limit = 1)
        assertEquals(listOf("one"), claimed.map { it.payload.text })
        assertEquals(listOf("two"), m.list("s1", PromptQueue.FOLLOW_UP).map { it.payload.text })
    }

    @Test
    fun `confirm deletes claimed items for good`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("done"))
        val claimed = m.claim("s1", PromptQueue.FOLLOW_UP)
        m.confirmConsumed(claimed.map { it.id })
        assertEquals(0, m.restoreUnconfirmed("s1"))
        assertTrue(m.list("s1", PromptQueue.FOLLOW_UP).isEmpty())
    }

    @Test
    fun `restore only touches the given session`() = runTest {
        val m = manager()
        m.enqueue("s1", PromptQueue.FOLLOW_UP, Payload("a"))
        m.enqueue("s2", PromptQueue.FOLLOW_UP, Payload("b"))
        m.claim("s1", PromptQueue.FOLLOW_UP)
        // s2 无被领条目 → 0，且 s2 队列不受影响
        assertEquals(0, m.restoreUnconfirmed("s2"))
        assertEquals(listOf("b"), m.list("s2", PromptQueue.FOLLOW_UP).map { it.payload.text })
        // s1 的被领条目放回
        assertEquals(1, m.restoreUnconfirmed("s1"))
        assertEquals(listOf("a"), m.list("s1", PromptQueue.FOLLOW_UP).map { it.payload.text })
    }

    @Test
    fun `invalid payload is dropped from the queue`() = runTest {
        val persistence = FakePersistence()
        val m = PromptQueueManager<Int>(persistence, Int.serializer())
        // 手工塞进一个非 Int payload 的记录
        persistence.enqueue(
            QueueRecord(
                id = "bad",
                sessionId = "s1",
                laneName = "main",
                queueType = PromptQueue.FOLLOW_UP.id,
                createdAt = 0L,
                payloadJson = "\"not-an-int\"",
            ),
        )
        assertTrue(m.list("s1", PromptQueue.FOLLOW_UP).isEmpty())
        // decode-or-cancel：坏条目被移除
        assertTrue(persistence.records.isEmpty())
    }

    @Test
    fun `unknown queue type is dropped by listAll`() = runTest {
        val persistence = FakePersistence()
        val m = manager(persistence)
        persistence.enqueue(
            QueueRecord(
                id = "weird",
                sessionId = "s1",
                laneName = "main",
                queueType = "legacy_queue",
                createdAt = 0L,
                payloadJson = "{}",
            ),
        )
        assertTrue(m.listAll("s1").isEmpty())
        assertTrue(persistence.records.isEmpty())
    }

    @Test
    fun `generic payload rides through the manager`() = runTest {
        val m = PromptQueueManager(persistence = FakePersistence(), serializer = ListSerializer(String.serializer()))
        m.enqueue("s1", PromptQueue.FOLLOW_UP, listOf("a", "b"))
        assertEquals(listOf(listOf("a", "b")), m.list("s1", PromptQueue.FOLLOW_UP).map { it.payload })
    }
}
