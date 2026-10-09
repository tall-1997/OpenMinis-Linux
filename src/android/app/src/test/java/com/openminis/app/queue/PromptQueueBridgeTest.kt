package com.openminis.app.queue

import com.openminis.app.ui.chat.QueuedPrompt
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-queue-disk-persistence] 宿主接缝（[PromptQueueBridge]）的 JVM 面测试。
 *
 * 覆盖的是「排队提示词能不能活着穿过进程死亡」这条链，以及接缝自己的两个坑：
 *
 *  - **manager 必须按 filesDir 缓存**。早期实现是单个 `@Volatile` 字段（「第一个
 *    目录赢」），生产无害但纯 JVM 面各测试传自己的临时目录 → 静默共享同一份
 *    队列状态，失败长得像特性 bug。见 [managerIsCachedPerFilesDir]。
 *  - **附件走平行数组 DTO**。`InputAttachment` 持 `android.net.Uri`，JVM 测试里
 *    `Uri.parse` 返回 null，所以这里只测落盘格式的保真度与「Uri 重建不出来时
 *    优雅降级」，不测 Uri 本身（那部分要装包验）。
 *
 * 落盘层的转义单射性 / 反查不二次转义，见 harness 侧
 * `FilePromptQueuePersistenceTest`。
 */
class PromptQueueBridgeTest {

    private lateinit var filesDir: File
    private lateinit var queueRoot: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("bridge-queue").toFile()
        queueRoot = File(filesDir, "prompt_queue")
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun prompt(id: String, text: String = id) = QueuedPrompt(id = id, text = text)

    private fun restore(sessionId: String): List<QueuedPrompt> =
        runBlocking { PromptQueueBridge.restore(filesDir, sessionId) }

    private fun texts(sessionId: String): List<String> = restore(sessionId).map { it.text }

    // ─── 基本往返 ──────────────────────────────────────────────────────

    @Test
    fun `text prompt survives an enqueue-restore round trip`() = runBlocking {
        PromptQueueBridge.enqueue(filesDir, "sess-1", prompt("queued_42", "keep working on the report"))

        val restored = restore("sess-1")

        assertEquals(1, restored.size)
        // id 必须原样保留：撤回（cancel）与消费确认（confirm）都按 id 对账，
        // 恢复时换个 id 就等于让磁盘上的幽灵条目再也删不掉。
        assertEquals("queued_42", restored.single().id)
        assertEquals("keep working on the report", restored.single().text)
        assertTrue(restored.single().attachments.isEmpty())
    }

    @Test
    fun `restore returns prompts in enqueue order`() = runBlocking {
        // createdAt 是毫秒精度，同毫秒内入队的顺序靠稳定排序保住。
        listOf("first", "second", "third").forEachIndexed { i, label ->
            PromptQueueBridge.enqueue(filesDir, "sess-1", prompt("q$i", label))
        }

        assertEquals(listOf("first", "second", "third"), texts("sess-1"))
    }

    @Test
    fun `restore of a session that never enqueued returns empty`() {
        assertTrue("cold session must not fabricate a queue", texts("never-used").isEmpty())
        assertFalse("and must not create the queue directory", queueRoot.exists())
    }

    // ─── 撤回与消费确认 ────────────────────────────────────────────────

    @Test
    fun `cancel removes exactly one prompt`() = runBlocking {
        PromptQueueBridge.enqueue(filesDir, "sess-1", prompt("q1", "one"))
        PromptQueueBridge.enqueue(filesDir, "sess-1", prompt("q2", "two"))

        PromptQueueBridge.cancel(filesDir, "q1")

        assertEquals(listOf("two"), texts("sess-1"))
    }

    @Test
    fun `cancel of an unknown id is a no-op`() = runBlocking {
        PromptQueueBridge.enqueue(filesDir, "sess-1", prompt("q1", "one"))

        PromptQueueBridge.cancel(filesDir, "does-not-exist")

        assertEquals(listOf("one"), texts("sess-1"))
    }

    @Test
    fun `confirm removes the consumed batch and keeps the rest`() = runBlocking {
        listOf("q1", "q2", "q3").forEach {
            PromptQueueBridge.enqueue(filesDir, "sess-1", prompt(it))
        }

        // drain 把 q1+q2 合成一条真实 DB 行后确认消费；q3 是确认之后才排进来的。
        PromptQueueBridge.confirm(filesDir, "sess-1", listOf("q1", "q2"))

        assertEquals(listOf("q3"), texts("sess-1"))
    }

    @Test
    fun `confirm with an empty batch touches nothing`() = runBlocking {
        PromptQueueBridge.enqueue(filesDir, "sess-1", prompt("q1"))

        PromptQueueBridge.confirm(filesDir, "sess-1", emptyList())

        assertEquals(listOf("q1"), texts("sess-1"))
    }

    @Test
    fun `confirm waits for a still-pending mirror write before scanning`() = runBlocking {
        // [T-queue-mirror-write-race] 工具边界的确认可能在入队后几 ms 就到；
        // 确认的扫描若跑在记录落盘之前，晚到的写会把条目在盘上复活（重启后
        // 还原出幽灵气泡）。confirm 必须先 join 同一 filesDir 的在途写。
        PromptQueueBridge.launchMirrorWrite(filesDir, this) {
            kotlinx.coroutines.delay(150)
            PromptQueueBridge.enqueue(filesDir, "sess-1", prompt("q1", "late write"))
        }

        PromptQueueBridge.confirm(filesDir, "sess-1", listOf("q1"))

        assertTrue("the late record must not outlive its own confirm", texts("sess-1").isEmpty())
    }

    // ─── 会话隔离 ──────────────────────────────────────────────────────

    @Test
    fun `dropSession clears only that session`() = runBlocking {
        PromptQueueBridge.enqueue(filesDir, "sess-a", prompt("a1", "alpha"))
        PromptQueueBridge.enqueue(filesDir, "sess-b", prompt("b1", "beta"))

        PromptQueueBridge.dropSession(filesDir, "sess-a")

        assertTrue("dropped session must come back empty", texts("sess-a").isEmpty())
        assertEquals("the other session must be untouched", listOf("beta"), texts("sess-b"))
    }

    @Test
    fun `dropSession deletes the mirror file instead of leaving an emptied shell`() = runBlocking {
        // [T-queue-disk-persistence] 空壳 {"records":[]} 不再累积：整份文件
        // 必须消失。"sess-a" 全是安全字符 → 转义后就是 sess-a.json。
        PromptQueueBridge.enqueue(filesDir, "sess-a", prompt("a1"))

        PromptQueueBridge.dropSession(filesDir, "sess-a")

        assertFalse(
            "the session's mirror file must be deleted, not emptied",
            File(queueRoot, "sess-a.json").exists(),
        )
    }

    @Test
    fun `dropSession twice is idempotent`() = runBlocking {
        PromptQueueBridge.enqueue(filesDir, "sess-a", prompt("a1"))

        PromptQueueBridge.dropSession(filesDir, "sess-a")
        PromptQueueBridge.dropSession(filesDir, "sess-a")

        assertTrue(texts("sess-a").isEmpty())
    }

    @Test
    fun `sessions differing only by separator keep separate queues`() = runBlocking {
        // lane 会话 id 是冒号分隔的（lane:<owner>:<name>）。这三个 id 若在文件名
        // 层面撞车，A 会话的 dropSession 会连 B 的排队提示词一起删掉。
        PromptQueueBridge.enqueue(filesDir, "lane:owner:x", prompt("c1", "colon"))
        PromptQueueBridge.enqueue(filesDir, "lane/owner/x", prompt("s1", "slash"))
        PromptQueueBridge.enqueue(filesDir, "lane_owner_x", prompt("u1", "underscore"))

        assertEquals(listOf("colon"), texts("lane:owner:x"))
        assertEquals(listOf("slash"), texts("lane/owner/x"))
        assertEquals(listOf("underscore"), texts("lane_owner_x"))
    }

    @Test
    fun `hostile session id stays inside the queue directory`() = runBlocking {
        // sessionId 可以来自 deep link，不能让它决定文件落在哪。
        PromptQueueBridge.enqueue(filesDir, "../../evil", prompt("h1", "escape attempt"))

        assertEquals(listOf("escape attempt"), texts("../../evil"))
        queueRoot.listFiles()?.forEach { f ->
            assertTrue("every queue file must live in the queue root: ${f.name}", f.parentFile == queueRoot)
        }
        assertFalse(
            "nothing may be written above filesDir",
            File(filesDir.parentFile, "evil.json").exists(),
        )
    }

    // ─── 落盘健壮性 ────────────────────────────────────────────────────

    @Test
    fun `corrupt queue file restores as empty without throwing`() = runBlocking {
        queueRoot.mkdirs()
        File(queueRoot, "sess-broken.json").writeText("{{{ not json at all", Charsets.UTF_8)

        // 队列是 best-effort 持久层：宁可丢队列，不可让 loadSession 抛异常挡住聊天。
        assertTrue(texts("sess-broken").isEmpty())
    }

    @Test
    fun `corrupt queue file does not block other sessions`() = runBlocking {
        queueRoot.mkdirs()
        File(queueRoot, "sess-broken.json").writeText("garbage", Charsets.UTF_8)
        PromptQueueBridge.enqueue(filesDir, "sess-good", prompt("g1", "healthy"))

        assertEquals(listOf("healthy"), texts("sess-good"))
    }

    // ─── 落盘 DTO ──────────────────────────────────────────────────────

    @Test
    fun `payload json round trip preserves attachment metadata`() {
        // InputAttachment 持 android.net.Uri，JVM 里造不出来 —— 直接测 DTO，
        // 这才是真正写进磁盘的形状（五个平行数组）。
        val payload = PromptQueueBridge.QueuedPromptPayload(
            text = "summarise these",
            attachmentIds = listOf("att-1", "att-2"),
            attachmentNames = listOf("photo.png", "notes.pdf"),
            attachmentUris = listOf("content://media/1", "file:///sdcard/notes.pdf"),
            attachmentMimeTypes = listOf("image/png", "application/pdf"),
            attachmentIsImages = listOf(true, false),
            createdAt = 1_700_000_000_000L,
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val decoded = json.decodeFromString(
            PromptQueueBridge.QueuedPromptPayload.serializer(),
            json.encodeToString(PromptQueueBridge.QueuedPromptPayload.serializer(), payload),
        )

        assertEquals(payload, decoded)
        assertEquals(listOf("photo.png", "notes.pdf"), decoded.attachmentNames)
        assertEquals(listOf(true, false), decoded.attachmentIsImages)
        assertEquals(1_700_000_000_000L, decoded.createdAt)
    }

    @Test
    fun `payload json tolerates a queue file written by an older build`() {
        // ignoreUnknownKeys：以后给 DTO 加字段不能让旧镜像变成不可读。
        val legacy = """{"text":"old","attachmentIds":[],"attachmentNames":[],""" +
            """"attachmentUris":[],"attachmentMimeTypes":[],"attachmentIsImages":[],""" +
            """"createdAt":1,"futureField":{"x":1}}"""
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val decoded = json.decodeFromString(
            PromptQueueBridge.QueuedPromptPayload.serializer(),
            legacy,
        )

        assertEquals("old", decoded.text)
        assertTrue(decoded.attachmentIds.isEmpty())
    }

    @Test
    fun `toQueuedPrompt keeps the text when a uri cannot be rebuilt`() {
        // JVM 里 Uri.parse 返回 null → InputAttachment 构造失败 → mapNotNull 把
        // 该附件丢掉。断言的是「不抛、正文还在」这条降级契约，不是附件数量
        // （后者是 JVM stub 的产物，真机上 Uri 能重建）。
        val payload = PromptQueueBridge.QueuedPromptPayload(
            text = "still readable",
            attachmentIds = listOf("att-1"),
            attachmentNames = listOf("photo.png"),
            attachmentUris = listOf("content://media/1"),
            attachmentMimeTypes = listOf("image/png"),
            attachmentIsImages = listOf(true),
        )

        val restored = payload.toQueuedPrompt("q1")

        assertEquals("q1", restored.id)
        assertEquals("still readable", restored.text)
    }

    // ─── manager 缓存 ──────────────────────────────────────────────────

    @Test
    fun `managerIsCachedPerFilesDir`() {
        val other = Files.createTempDirectory("bridge-queue-other").toFile()
        try {
            assertSame("same filesDir must reuse one manager", PromptQueueBridge.manager(filesDir), PromptQueueBridge.manager(filesDir))
            assertNotSame("a different filesDir must not inherit the first one's state", PromptQueueBridge.manager(filesDir), PromptQueueBridge.manager(other))
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun `two files dirs hold independent queues`() = runBlocking {
        val other = Files.createTempDirectory("bridge-queue-other").toFile()
        try {
            PromptQueueBridge.enqueue(filesDir, "sess-1", prompt("a1", "in-first"))
            PromptQueueBridge.enqueue(other, "sess-1", prompt("b1", "in-second"))

            // 同 sessionId、不同 filesDir：单例 manager 会让这两条互相看见。
            assertEquals(listOf("in-first"), texts("sess-1"))
            assertEquals(
                listOf("in-second"),
                PromptQueueBridge.restore(other, "sess-1").map { it.text },
            )
        } finally {
            other.deleteRecursively()
        }
    }
}
