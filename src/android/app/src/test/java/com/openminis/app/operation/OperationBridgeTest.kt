package com.openminis.app.operation

import com.openminis.app.harness.HarnessMessage
import com.openminis.app.harness.HarnessTool
import com.openminis.app.harness.ToolCall
import com.openminis.app.harness.ToolResult
import com.openminis.app.harness.effects.ToolReplayPolicy
import com.openminis.app.harness.operation.OperationSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [T-operation-wiring] 宿主桥验收：转移落盘、崩溃窗口查询、finish 清账。
 * 直驱 File 面（桥的 context 重载只是 filesDir 的薄包装），零 Android 依赖。
 */
class OperationBridgeTest {

    private fun freshDir() = Files.createTempDirectory("op-bridge").toFile()

    /** 直驱 File 面的桥视图（等价于 OperationBridge.persistence/coordinator 的 context 重载）。 */
    private class TestBridge(dir: java.io.File) {
        private val persistence = OperationBridge.persistence(dir)
        private val coordinator = OperationBridge.coordinator(dir)
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun begin(sid: String): String? = runCatching { coordinator.beginRun(sid) }.getOrNull()
        suspend fun toolIntent(op: String, call: ToolCall) {
            coordinator.toolIntent(
                op, call,
                json.encodeToString(HarnessMessage.serializer(), call),
                ToolReplayPolicy.forTool(call.tool, call.rawToolName), 1,
            )
        }
        suspend fun toolSettled(op: String, result: ToolResult, name: String) =
            coordinator.toolSettled(op, result, 1, name)
        suspend fun suspend(op: String, reason: String) = coordinator.suspendOperation(op, reason)
        suspend fun finish(sid: String, outcome: String) = coordinator.finish(sid, outcome)
        suspend fun active(sid: String) = coordinator.active(sid)
        suspend fun unsettled(sid: String): List<ToolCall> = persistence.listActiveOperations(sid)
            .filter { it.status == "running" && it.phase == "tool_intent" }
            .mapNotNull { op ->
                val snapshot = runCatching { json.decodeFromString(OperationSnapshot.serializer(), op.stateJson) }.getOrNull()
                val payload = snapshot?.effectPayloadJson ?: return@mapNotNull null
                runCatching { json.decodeFromString(HarnessMessage.serializer(), payload) }.getOrNull() as? ToolCall
            }
    }

    @Test
    fun `tool intent survives process death and is recoverable as a ToolCall`() {
        runBlocking {
        val dir = freshDir()
        val bridge = TestBridge(dir)
        val op = bridge.begin("s1")!!
        bridge.toolIntent(op, ToolCall(id="call-1", createdAt=1L, tool=HarnessTool.READ, args=JsonObject(emptyMap()), rawToolName="file_read"))

        // 新进程视角：另一个 bridge 实例读同一目录
        val recovered = TestBridge(dir).unsettled("s1")
        assertEquals(1, recovered.size)
        assertEquals("call-1", recovered[0].id)
        assertEquals("file_read", recovered[0].rawToolName)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `finish clears the ledger so recovery sees nothing`() {
        runBlocking {
        val dir = freshDir()
        val bridge = TestBridge(dir)
        val op = bridge.begin("s1")!!
        bridge.toolIntent(op, ToolCall(id="call-2", createdAt=1L, tool=HarnessTool.BASE, args=JsonObject(emptyMap()), rawToolName="shell_execute"))
        bridge.finish("s1", "completed")

        assertTrue(TestBridge(dir).unsettled("s1").isEmpty())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `settled tool calls are not recoverable`() {
        runBlocking {
        val dir = freshDir()
        val bridge = TestBridge(dir)
        val op = bridge.begin("s1")!!
        bridge.toolIntent(op, ToolCall(id="call-3", createdAt=1L, tool=HarnessTool.READ, args=JsonObject(emptyMap()), rawToolName="file_read"))
        bridge.toolSettled(op, ToolResult("call-3:result", 2L, "call-3", true, "ok"), "file_read")

        assertTrue(TestBridge(dir).unsettled("s1").isEmpty())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `suspend marks the operation suspended not running`() {
        runBlocking {
        val dir = freshDir()
        val bridge = TestBridge(dir)
        val op = bridge.begin("s1")!!
        bridge.suspend(op, "user cancel")

        assertTrue(TestBridge(dir).unsettled("s1").isEmpty())
        assertNotNull(bridge.active("s1"))
            dir.deleteRecursively()
        }
    }
}
