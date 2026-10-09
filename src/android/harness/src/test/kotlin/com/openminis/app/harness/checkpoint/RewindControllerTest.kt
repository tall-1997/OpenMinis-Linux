package com.openminis.app.harness.checkpoint

import com.openminis.app.harness.WorkspaceFileAccess
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Adapted from taixu RewindControllerTest (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 */
class RewindControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun textOf(root: File, path: String): String? {
        val file = root.resolve(path)
        return if (file.exists()) file.readText() else null
    }

    private suspend fun write(root: File, path: String, content: String) {
        check(WorkspaceFileAccess(root).write(path, content).isSuccess) { "测试 setup 写文件失败: $path" }
    }

    @Test
    fun `code rewind restores files and deletes those created after the target turn`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        // turn0：a.txt = "v1"，b.txt 不存在
        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", null)
        store.capture("s", "b.txt", null)
        write(root, "a.txt", "v1")
        // turn1：a.txt = "v2"，创建 b.txt
        rc.beginTurn("s", "p1")
        store.capture("s", "a.txt", "v1")
        store.capture("s", "b.txt", null)
        write(root, "a.txt", "v2")
        write(root, "b.txt", "b")

        // 撤回到 turn1：a.txt 应回到 "v1"，b.txt（turn1 时不存在）应被删除
        val plan = rc.prepare("s", 1, RewindScope.CODE)
        val result = rc.commit(plan)

        assertEquals(1, result.filesRestored)
        assertEquals(1, result.filesDeleted)
        assertFalse(result.partial)
        assertEquals("v1", textOf(root, "a.txt"))
        assertNull(textOf(root, "b.txt"))
    }

    @Test
    fun `code rewind to turn0 restores to initial absent state`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", null)
        write(root, "a.txt", "v1")
        rc.beginTurn("s", "p1")
        store.capture("s", "a.txt", "v1")
        write(root, "a.txt", "v2")

        rc.commit(rc.prepare("s", 0, RewindScope.CODE))
        assertNull(textOf(root, "a.txt"))
    }

    @Test
    fun `conversation scope is partial when no conversation rewirer is configured`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", null)
        write(root, "a.txt", "v1")

        val result = rc.commit(rc.prepare("s", 0, RewindScope.BOTH))
        assertTrue(result.partial)
        assertTrue(result.note.orEmpty().contains("对话"))
    }

    @Test
    fun `path traversal in restore target is rejected by the workspace boundary`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        // 伪装一个可逃逸路径：delete 解析到工作区外应返回 false，不会产生副作用
        store.beginTurn("s", "p0")
        store.capture("s", "../../outside.txt", null)
        val result = rc.commit(rc.prepare("s", 0, RewindScope.CODE))
        assertTrue(result.filesDeleted == 0)
        assertEquals(0, result.filesRestored)
    }

    @Test
    fun `externally modified file is skipped and reported as conflict`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        // turn0：agent 把 a.txt 从 "v1" 写到 "v2"（带改动后凭据）
        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", "v1")
        write(root, "a.txt", "v1")
        store.captureAfterImage("s", "a.txt", "v2")
        write(root, "a.txt", "v2")

        // 用户在会话外把 a.txt 改成 "external"
        write(root, "a.txt", "external")

        val result = rc.commit(rc.prepare("s", 0, RewindScope.CODE))
        assertTrue(result.partial)
        assertEquals(listOf("a.txt"), result.conflicts)
        // 外部改动未被覆盖
        assertEquals("external", textOf(root, "a.txt"))
        assertTrue(result.note.orEmpty().contains("a.txt"))
    }

    @Test
    fun `file matching its after-image restores without conflict`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", "v1")
        write(root, "a.txt", "v1")
        store.captureAfterImage("s", "a.txt", "v2")
        write(root, "a.txt", "v2")

        // 无外部改动：当前内容与最后凭据一致 → 正常恢复
        val result = rc.commit(rc.prepare("s", 0, RewindScope.CODE))
        assertFalse(result.partial)
        assertTrue(result.conflicts.isEmpty())
        assertEquals("v1", textOf(root, "a.txt"))
    }

    @Test
    fun `externally deleted file with after-image is a conflict`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        rc.beginTurn("s", "p0")
        store.capture("s", "b.txt", null)
        write(root, "b.txt", "b")
        store.captureAfterImage("s", "b.txt", "b")

        // 用户在会话外删除了该文件
        root.resolve("b.txt").delete()

        val result = rc.commit(rc.prepare("s", 0, RewindScope.CODE))
        // 计划是"恢复为不存在"（删除），但文件已被外部删除 → 记为冲突跳过
        assertTrue(result.conflicts.contains("b.txt"))
        assertFalse(root.resolve("b.txt").exists())
    }

    @Test
    fun `undo restores the pre-rewind disk state`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        // turn0：agent 把 a.txt 写到 "v2"（凭据齐全）
        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", "v1")
        write(root, "a.txt", "v1")
        store.captureAfterImage("s", "a.txt", "v2")
        write(root, "a.txt", "v2")

        // rewind 到 turn0：a.txt 回到 "v1"
        rc.commit(rc.prepare("s", 0, RewindScope.CODE))
        assertEquals("v1", textOf(root, "a.txt"))

        // undo：a.txt 回到 rewind 前的 "v2"
        val undoResult = rc.undoLastRewind("s")!!
        assertEquals(1, undoResult.filesRestored)
        assertFalse(undoResult.partial)
        assertEquals("v2", textOf(root, "a.txt"))
        // 单层级：记录已消费，再次 undo 返回 null
        assertNull(rc.undoLastRewind("s"))
    }

    @Test
    fun `undo record is invalidated by a new agent write`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", "v1")
        write(root, "a.txt", "v2")
        rc.commit(rc.prepare("s", 0, RewindScope.CODE))

        // rewind 之后智能体又写文件 → 撤销窗口关闭（undo 会覆盖新写入）
        rc.beginTurn("s", "p1")
        store.capture("s", "a.txt", "v1")
        assertNull(rc.undoLastRewind("s"))
    }

    @Test
    fun `undo skips path externally modified after the rewind`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val store = CheckpointStore()
        val rc = RewindController(store, WorkspaceFileAccess(root))

        rc.beginTurn("s", "p0")
        store.capture("s", "a.txt", "v1")
        write(root, "a.txt", "v2")
        rc.commit(rc.prepare("s", 0, RewindScope.CODE))
        assertEquals("v1", textOf(root, "a.txt"))

        // rewind 之后用户把文件改成 "external" → undo 跳过该路径
        write(root, "a.txt", "external")
        val undoResult = rc.undoLastRewind("s")!!
        assertTrue(undoResult.conflicts.contains("a.txt"))
        assertEquals("external", textOf(root, "a.txt"))
    }
}
