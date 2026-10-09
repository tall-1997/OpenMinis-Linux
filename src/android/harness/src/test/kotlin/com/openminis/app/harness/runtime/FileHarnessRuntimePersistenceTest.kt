package com.openminis.app.harness.runtime

import com.openminis.app.harness.model.HarnessEntryEntity
import com.openminis.app.harness.model.HarnessLaneEntity
import com.openminis.app.harness.model.HarnessLaneResultEntity
import com.openminis.app.harness.model.HarnessOperationEntity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-operation-wiring] 磁盘运行时仓储验收：分支链、lane 指针、finish 清账、
 * 坏文件降级、单射转义（lane 会话 id 含冒号/斜杠不撞文件名）。
 */
class FileHarnessRuntimePersistenceTest {

    private val roots = mutableListOf<File>()

    @org.junit.After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun persistence(): FileHarnessRuntimePersistence {
        val root = Files.createTempDirectory("harness-runtime").toFile()
        roots += root
        return FileHarnessRuntimePersistence(root)
    }

    private fun entry(id: String, parentId: String?, sessionId: String = "s1") =
        HarnessEntryEntity(sequence = 0, id = id, sessionId = sessionId, parentId = parentId, createdAt = 1, entryType = "message", payloadJson = "{}")

    private fun operation(id: String, sessionId: String = "s1", laneName: String = "main") =
        HarnessOperationEntity(id = id, sessionId = sessionId, laneName = laneName, kind = "run", status = "running", phase = "provider_intent", startedAt = 1, updatedAt = 1, startLeafId = null, stateJson = "{}")

    @Test
    fun `branch walks the parent chain in order`() = runBlocking {
        val p = persistence()
        val lane = p.ensureLane("s1", "main")
        p.appendToLane("s1", "main", entry("e1", null))
        p.appendToLane("s1", "main", entry("e2", "e1"))
        p.appendToLane("s1", "main", entry("e3", "e2"))
        val chain = p.branch("s1", "e3")
        assertEquals(listOf("e1", "e2", "e3"), chain.map { it.id })
        assertEquals(2, p.branchTail("s1", "e3", 2).size)
        assertEquals("e3", p.findLane("s1", "main")!!.leafId)
    }

    @Test
    fun `moveLane rewinds the pointer without deleting entries`() = runBlocking {
        val p = persistence()
        p.ensureLane("s1", "main")
        p.appendToLane("s1", "main", entry("e1", null))
        p.appendToLane("s1", "main", entry("e2", "e1"))
        p.moveLane("s1", "main", "e1")
        assertEquals("e1", p.findLane("s1", "main")!!.leafId)
        assertEquals(2, p.branch("s1", "e2").size) // 被放弃的分支保留
    }

    @Test
    fun `finish removes the operation and records the result`() = runBlocking {
        val p = persistence()
        val lane = p.ensureLane("s1", "main")
        p.beginOperation(lane.copy(currentOperationId = "op1"), operation("op1"))
        assertEquals(1, p.listActiveOperations("s1").size)
        p.finishOperation(
            HarnessLaneResultEntity("s1", "main", "op1", "completed", null, null, 2),
            p.findLane("s1", "main")!!.copy(currentOperationId = null, updatedAt = 2),
        )
        assertTrue(p.listActiveOperations("s1").isEmpty())
        assertNull(p.findOperation("op1"))
    }

    @Test
    fun `settle appends entry usage and updates operation atomically`() = runBlocking {
        val p = persistence()
        val lane = p.ensureLane("s1", "main")
        p.beginOperation(lane.copy(currentOperationId = "op1"), operation("op1"))
        val e = entry("e1", null)
        p.settleEffect(e, null, operation("op1").copy(phase = "tool_settled"), p.findLane("s1", "main")!!.copy(leafId = "e1"))
        assertEquals("e1", p.findLane("s1", "main")!!.leafId)
        assertEquals("tool_settled", p.findOperation("op1")!!.phase)
        assertEquals(1, p.branch("s1", "e1").size)
    }

    @Test
    fun `unreadable file degrades to empty state`() = runBlocking {
        val root = Files.createTempDirectory("harness-runtime").toFile()
        roots += root
        val p = FileHarnessRuntimePersistence(root)
        p.ensureLane("s1", "main")
        // 写坏主文件
        File(root, firstFileName(root)).writeText("{broken")
        val p2 = FileHarnessRuntimePersistence(root)
        assertNull(p2.findLane("s1", "main"))
    }

    @Test
    fun `session ids with colons and slashes map to distinct files`() = runBlocking {
        val p = persistence()
        val a = p.ensureLane("lane:owner:name", "main")
        val b = p.ensureLane("lane/owner/name", "main")
        val c = p.ensureLane("lane_owner_name", "main")
        p.appendToLane("lane:owner:name", "main", entry("e1", null, "lane:owner:name"))
        // 互不污染：b/c 的 lane 上没有 e1
        assertNull(p.findLane("lane/owner/name", "main")!!.leafId)
        assertNull(p.findLane("lane_owner_name", "main")!!.leafId)
        assertEquals("e1", p.findLane("lane:owner:name", "main")!!.leafId)
    }

    private fun firstFileName(root: File): String {
        val names = root.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name }
        return names.first()
    }
}
