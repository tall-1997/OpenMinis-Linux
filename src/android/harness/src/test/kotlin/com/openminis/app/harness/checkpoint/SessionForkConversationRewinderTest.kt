package com.openminis.app.harness.checkpoint

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapted from taixu SessionForkConversationRewinderTest (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 用内存假端口验证 fork 语义，不依赖 Room / Android。
 */
class SessionForkConversationRewinderTest {

    /** 内存版会话存储：扁平消息列表（对齐宿主 MessageEntity 模型）。 */
    private class FakePort : SessionForkPort {
        val sessions = LinkedHashMap<String, SessionForkSource>()
        val messages = mutableListOf<Pair<String, SessionForkMessage>>() // sessionId to message
        val createdTitles = mutableListOf<String>()
        var nextId = 0
        var failAppendAfter: Int? = null

        override suspend fun findSession(sessionId: String) = sessions[sessionId]

        override suspend fun branchMessages(sessionId: String): List<SessionForkMessage> =
            messages.filter { it.first == sessionId }.map { it.second }

        override suspend fun createForkedSession(source: SessionForkSource, title: String, now: Long): String {
            val id = "fork-${nextId++}"
            sessions[id] = source.copy(id = id, title = title)
            createdTitles += title
            return id
        }

        override suspend fun appendMessages(sessionId: String, messages: List<SessionForkMessage>) {
            failAppendAfter?.let { limit ->
                if (messages.size > limit) error("simulated partial write")
            }
            this.messages += messages.map { sessionId to it }
        }

        suspend fun seed(sessionId: String, vararg ids: String) {
            ids.forEachIndexed { index, id ->
                messages += sessionId to SessionForkMessage(
                    id = id,
                    parentId = ids.getOrNull(index - 1),
                    createdAt = 10L + index,
                    payloadJson = """{"id":"$id"}""",
                )
            }
        }
    }

    private fun sourceSession() = SessionForkSource(
        id = "src",
        title = "源会话",
        inherited = mapOf("modelId" to "m1", "workspace" to "/ws/demo"),
    )

    @Test
    fun `fork copies branch prefix before anchor with remapped ids and rebuilt parent chain`() = runBlocking {
        val port = FakePort()
        val store = CheckpointStore()
        port.sessions["src"] = sourceSession()
        port.seed("src", "u1", "a1", "u2", "a2")

        // turn0 锚点为 u2：派生会话应复制 u1、a1（锚点之前），丢弃 u2 及其后
        store.beginTurn("src", "第二轮", "u2")
        store.capture("src", "demo.txt", "old")
        store.beginTurn("src", "第三轮", "a2") // 关闭上一轮

        val rewinder = SessionForkConversationRewinder(port, store)
        val forkedId = checkNotNull(rewinder.rewindConversation("src", 0))

        val forked = port.branchMessages(forkedId)
        assertEquals(2, forked.size)
        assertTrue("entry id 必须全部换新", forked.none { it.id in setOf("u1", "a1", "u2", "a2") })
        assertNull("首条消息无父", forked.first().parentId)
        assertEquals("父链按新 id 重建", forked[0].id, forked[1].parentId)
        // 原会话树不受影响
        assertEquals(4, port.branchMessages("src").size)
        // 元数据继承
        val forkedSession = port.sessions.getValue(forkedId)
        assertEquals("m1", forkedSession.inherited["modelId"])
        assertEquals("/ws/demo", forkedSession.inherited["workspace"])
        assertTrue(port.createdTitles.single().contains("回退"))
    }

    @Test
    fun `fork returns null when session anchor or branch prefix is missing`() = runBlocking {
        val port = FakePort()
        val store = CheckpointStore()
        port.sessions["src"] = sourceSession()
        port.seed("src", "u1")

        store.beginTurn("src", "p", "u1")
        store.capture("src", "demo.txt", "old")
        store.beginTurn("src", "p2") // 关闭 turn0

        val rewinder = SessionForkConversationRewinder(port, store)
        // 会话不存在
        assertNull(rewinder.rewindConversation("nope", 0))
        // 轮次无锚点（无写触碰的轮不入 checkpoint）
        assertNull(rewinder.rewindConversation("src", 99))
        // 锚点是分支首条消息（前缀为空 → 无可回退）
        assertNull(rewinder.rewindConversation("src", 0))
        // 未产生任何派生会话
        assertTrue(port.createdTitles.isEmpty())
    }

    @Test
    fun `fork without title falls back to a readable default`() = runBlocking {
        val port = FakePort()
        val store = CheckpointStore()
        port.sessions["src"] = SessionForkSource(id = "src", title = null)
        port.seed("src", "u1", "u2")
        store.beginTurn("src", "p", "u2")
        store.capture("src", "a.txt", "x")
        store.beginTurn("src", "p2")

        SessionForkConversationRewinder(port, store).rewindConversation("src", 0)
        assertTrue(port.createdTitles.single().startsWith("会话 · "))
    }

    @Test
    fun `rewind controller BOTH scope reports the forked session id`() = runBlocking {
        val port = FakePort()
        val store = CheckpointStore()
        port.sessions["src"] = sourceSession()
        port.seed("src", "u1", "a1", "u2")

        val root = kotlin.io.path.createTempDirectory("ws").toFile()
        val fileAccess = com.openminis.app.harness.WorkspaceFileAccess(root)
        store.beginTurn("src", "第二轮", "u2")
        store.capture("src", "demo.txt", "old")
        fileAccess.write("demo.txt", "new")
        store.beginTurn("src", "第三轮") // 关闭 turn0

        val rewinder = SessionForkConversationRewinder(port, store)
        val rc = RewindController(store, fileAccess, rewinder)

        val result = rc.commit(rc.prepare("src", 0, RewindScope.BOTH))

        assertEquals(false, result.partial)
        assertEquals(1, result.filesRestored)
        assertEquals("old", java.io.File(root, "demo.txt").readText())
        assertEquals("fork-0", result.forkedSessionId)
    }
}
