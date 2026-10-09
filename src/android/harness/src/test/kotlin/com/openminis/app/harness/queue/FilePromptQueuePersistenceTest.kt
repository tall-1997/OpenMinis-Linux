package com.openminis.app.harness.queue

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-queue-disk-persistence] 文件持久化层的两条不变量，都是宿主接线时才暴露
 * 出来的：
 *
 *  1. **会话 id → 文件名单射**。旧实现把每个非法字符都替换成 `_`，于是
 *     `lane:owner:name` / `lane/owner/name` / `lane_owner_name` 三个不同会话
 *     共用一个队列文件——A 会话的 `dropSession` 会把 B 的排队提示词一起删掉，
 *     而 lane id 恰恰是冒号分隔的。改成按 UTF-8 字节百分号式转义（`_` 自身
 *     转义成 `__`）后互不重叠。
 *
 *  2. **反查路径不能二次转义**。`cancel` / `confirmConsumed` 只有 itemId，靠扫
 *     目录反查会话文件；拿到的已经是转义后的名字。早期实现把它再喂回 `fileFor`
 *     → `_` 被加倍 → 指向不存在的文件 → **静默什么都不删**（幽灵条目在重启后
 *     复活）。现在统一走 `withRawFile(File)`。
 *
 * 管理器级语义（claim/confirm/restoreUnconfirmed 的两段式消费）见
 * [PromptQueueManagerTest]；这里只测落盘层。
 */
class FilePromptQueuePersistenceTest {

    private lateinit var root: File
    private lateinit var persistence: FilePromptQueuePersistence

    @Before
    fun setUp() {
        root = File(Files.createTempDirectory("queue-persist").toString())
        persistence = FilePromptQueuePersistence(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun record(id: String, sessionId: String, queue: PromptQueue = PromptQueue.FOLLOW_UP) =
        QueueRecord(
            id = id,
            sessionId = sessionId,
            laneName = PromptQueueManager.MAIN_LANE,
            queueType = queue.id,
            createdAt = System.currentTimeMillis(),
            payloadJson = """{"text":"$id"}""",
        )

    private fun ids(sessionId: String, queue: PromptQueue = PromptQueue.FOLLOW_UP): List<String> =
        runBlocking {
            persistence.list(sessionId, PromptQueueManager.MAIN_LANE, queue).map { it.id }
        }

    // ─── 不变量 1：会话 id → 文件名单射 ────────────────────────────────

    @Test
    fun `session ids that differ only by separator get distinct queue files`() = runBlocking {
        val colon = "lane:owner:name"
        val slash = "lane/owner/name"
        val underscore = "lane_owner_name"

        persistence.enqueue(record("c1", colon))
        persistence.enqueue(record("s1", slash))
        persistence.enqueue(record("u1", underscore))

        // 三个会话各自只看得见自己的条目 —— 共用文件的话这里会各看见 3 条。
        assertEquals(listOf("c1"), ids(colon))
        assertEquals(listOf("s1"), ids(slash))
        assertEquals(listOf("u1"), ids(underscore))

        // 落盘确实是三个不同文件。
        assertEquals(3, root.listFiles { f -> f.name.endsWith(".json") }?.size)
    }

    @Test
    fun `dropping one session leaves the separator-twin intact`() = runBlocking {
        persistence.enqueue(record("c1", "lane:owner:name"))
        persistence.enqueue(record("u1", "lane_owner_name"))

        persistence.dropSession("lane:owner:name")

        assertTrue("colon session queue should be gone", ids("lane:owner:name").isEmpty())
        assertEquals(
            "underscore twin must survive its separator-twin's deletion",
            listOf("u1"),
            ids("lane_owner_name"),
        )
    }

    @Test
    fun `dropping a session deletes its file instead of leaving an emptied shell`() = runBlocking {
        // [T-queue-disk-persistence] 旧链路（clear(FOLLOW_UP)）只把记录清空、
        // 文件留下：每个有排队历史的已删会话都在 root 里积累一个
        // {"records":[]} 空 JSON，非 FOLLOW_UP 条目也跟着死不掉。drop 必须
        // 整份文件消失——这里特意在同会话文件里放一条 STEER 记录钉住
        // 「跨队列类型一起走」。
        persistence.enqueue(record("a1", "sess-a"))
        persistence.enqueue(record("a2", "sess-a", PromptQueue.STEER))
        persistence.enqueue(record("b1", "sess-b"))

        persistence.dropSession("sess-a")

        assertEquals(
            "the dropped session's file must be GONE, not emptied",
            1,
            root.listFiles { f -> f.name.endsWith(".json") }?.size,
        )
        assertTrue("dropped session reads back empty", ids("sess-a").isEmpty())
        assertEquals("the other session must be untouched", listOf("b1"), ids("sess-b"))
    }

    @Test
    fun `underscore in a raw id cannot masquerade as an escape sequence`() = runBlocking {
        // "_2f" 是合法原始 id；"/" 的转义结果也是 "_2f"。转义 '_' 自身正是为了
        // 让这两者不撞同一个文件。
        persistence.enqueue(record("raw", "_2f"))
        persistence.enqueue(record("escaped", "/"))

        assertEquals(listOf("raw"), ids("_2f"))
        assertEquals(listOf("escaped"), ids("/"))
    }

    @Test
    fun `hostile session id cannot escape the queue root`() = runBlocking {
        persistence.enqueue(record("h1", "../../evil"))

        // 条目落在 root 内、可正常读回。
        assertEquals(listOf("h1"), ids("../../evil"))
        assertTrue(root.listFiles()!!.isNotEmpty())

        // root 之外没有被写出任何东西（'/' 已被转义成 _2f，逃不出去）。
        assertFalse("must not write above the queue root", File(root.parentFile, "evil.json").exists())
        val strays = (root.parentFile.listFiles() ?: emptyArray())
            .filter { it.name != root.name && it.name.startsWith("evil") }
        assertTrue("stray files escaped the queue root: $strays", strays.isEmpty())
    }

    // ─── 不变量 2：反查路径不二次转义 ─────────────────────────────────

    @Test
    fun `cancel by itemId finds sessions whose id needed escaping`() = runBlocking {
        val sessionId = "lane:owner:name" // 转义后含 '_'，是二次转义 bug 的触发面
        persistence.enqueue(record("victim", sessionId))
        persistence.enqueue(record("keeper", sessionId))

        persistence.cancel("victim")

        assertEquals(
            "cancel must delete through the escaped filename, not a re-escaped one",
            listOf("keeper"),
            ids(sessionId),
        )
    }

    @Test
    fun `confirmConsumed removes a batch spanning several session files`() = runBlocking {
        persistence.enqueue(record("a1", "sess-a"))
        persistence.enqueue(record("b1", "lane:b:x"))
        persistence.enqueue(record("b2", "lane:b:x"))

        // 一批里混了两个会话的 id（宿主队列若不带会话归属，消费时的 sessionId
        // 未必等于入队时的）。命中即止的实现只会清掉第一个文件。
        persistence.confirmConsumed(listOf("a1", "b1"))

        assertTrue(ids("sess-a").isEmpty())
        assertEquals(listOf("b2"), ids("lane:b:x"))
    }

    @Test
    fun `confirmConsumed with an empty batch touches nothing`() = runBlocking {
        persistence.enqueue(record("a1", "sess-a"))

        persistence.confirmConsumed(emptyList())

        assertEquals(listOf("a1"), ids("sess-a"))
    }

    // ─── 落盘健壮性 ────────────────────────────────────────────────────

    @Test
    fun `corrupt session file reads back empty instead of throwing`() = runBlocking {
        root.mkdirs()
        File(root, "broken.json").writeText("{{{ not json", Charsets.UTF_8)

        assertTrue("corrupt queue must degrade to empty", ids("broken").isEmpty())

        // 坏文件不阻挡同一 root 下别的会话。
        persistence.enqueue(record("ok", "healthy"))
        assertEquals(listOf("ok"), ids("healthy"))
    }

    @Test
    fun `stale tmp sibling is never swept as a session file`() = runBlocking {
        persistence.enqueue(record("a1", "sess-a"))
        // 写一半崩掉留下的临时文件。故意用**另一个会话**的名字：写盘路径正是
        // `<file>.json.tmp`，若拿 sess-a 自己的 tmp 当道具，它会被正常写入覆盖
        // ——那是正确行为，测不出东西。
        //
        // 道具里塞一条 id 也叫 a1 的记录：万一 sessionFiles() 的后缀过滤失效把它
        // 扫进去，cancel 会删掉这条并回写 → 内容变化 → 断言失败。
        val stale = File(root, "zzz-stale.json.tmp")
        val staleContent = """{"records":[{"id":"a1","sessionId":"zzz-stale","laneName":"main",""" +
            """"queueType":"follow_up","createdAt":0,"payloadJson":"{}"}]}"""
        stale.writeText(staleContent, Charsets.UTF_8)

        persistence.cancel("a1")

        assertTrue("the real session file should have been swept", ids("sess-a").isEmpty())
        assertEquals(
            "a .json.tmp sibling must never be parsed or rewritten as a session file",
            staleContent,
            stale.readText(Charsets.UTF_8),
        )
    }
}
