package com.openminis.app.mcp.server.tools

import com.openminis.app.mcp.server.McpServerCore
import com.openminis.app.mcp.server.approval.ApprovalFakePrefs
import com.openminis.app.mcp.server.approval.McpApprovalCore
import com.openminis.app.mcp.server.approval.McpApprovalExecutor
import com.openminis.app.mcp.server.approval.McpApprovalManager
import com.openminis.app.mcp.server.approval.McpApprovalRpc
import com.openminis.app.mcp.server.approval.McpApprovalStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Contract of the externally reachable approval surface: three tools, no
 * approval actions, and the same three-phase gate as the RPC layer.
 *
 * The order is the contract: a task is submitted while PENDING, the phone
 * activates it, only then can a run be filed — and each run still waits for
 * its own approval before `phone_operation_execute` touches the dispatcher.
 */
class ApprovalToolsTest {

    /** Business tools only — the approval executor must never re-enter itself. */
    private class FakeDispatcher : McpServerCore.ToolDispatcher {
        var calls = 0
        var lastTool: String? = null
        var lastArguments: JSONObject? = null
        override fun listTools(): JSONArray = JSONArray()
        override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult {
            calls++
            lastTool = name
            lastArguments = arguments
            return McpServerCore.CallResult(JSONArray().put(McpToolShared.text("ran:$name")))
        }
    }

    private lateinit var store: McpApprovalStore
    private lateinit var manager: McpApprovalManager
    private lateinit var biz: FakeDispatcher
    private lateinit var rpc: McpApprovalRpc
    private lateinit var specs: List<McpToolSpec>

    @Before
    fun setUp() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mcp-approval-tools-test")
        McpApprovalStore.taskRootPrefix = tmp.path + "/"
        store = McpApprovalStore(ApprovalFakePrefs())
        manager = McpApprovalManager(store)
        biz = FakeDispatcher()
        rpc = McpApprovalRpc(manager, McpApprovalExecutor(biz))
        specs = approvalTools(rpc)
    }

    @After
    fun tearDown() {
        McpApprovalStore.taskRootPrefix = McpApprovalStore.TASK_ROOT_PREFIX
        File(McpApprovalStore.taskRootFor("task-1")).deleteRecursively()
    }

    private fun spec(name: String): McpToolSpec =
        specs.firstOrNull { it.name == name } ?: error("missing tool '$name'")

    private fun call(name: String, args: JSONObject): McpServerCore.CallResult = spec(name).handler(args)

    private fun textOf(result: McpServerCore.CallResult): String =
        result.content.optJSONObject(0)?.optString("text").orEmpty()

    /** request → phone activates the task → return its id. */
    private fun activeTask(name: String): String {
        val created = call("phone_task_request", JSONObject().put("name", name))
        assertFalse(textOf(created), created.isError)
        val taskId = JSONObject(textOf(created)).optString("taskId")
        assertTrue(taskId.isNotBlank())
        assertTrue(manager.approveTask(taskId) is McpApprovalCore.Outcome.Ok)
        return taskId
    }

    // -- registry ------------------------------------------------------------

    @Test
    fun `the surface is exactly the four approval tools`() {
        assertEquals(
            listOf("phone_task_request", "phone_local_run", "phone_operation_execute", "phone_task_status"),
            specs.map { it.name },
        )
    }

    @Test
    fun `approval actions are absent from the tool surface`() {
        val names = specs.map { it.name }.toSet()
        listOf(
            "phone_task_approve",
            "phone_task_approve_op",
            "phone_task_revoke",
            "phone_task_list",
            "phone_task_cancel",
            "phone_task_cleanup",
            "phone_task_delete",
        ).forEach { assertFalse("$it must not be exposed", it in names) }
    }

    @Test
    fun `every tool ships a valid input schema`() {
        specs.forEach { s ->
            assertEquals("object", s.schema.optString("type"))
            assertTrue("${s.name} has no properties", s.schema.optJSONObject("properties") != null)
            assertTrue("${s.name} has no description", s.description.isNotBlank())
        }
    }

    @Test
    fun `the registry is empty without a runtime and full with one`() {
        assertTrue(approvalToolSpecs(null).isEmpty())
        assertEquals(4, approvalToolSpecs(rpc).size)
    }

    // -- flow ----------------------------------------------------------------

    @Test
    fun `the three tools drive a full approval flow`() {
        val taskId = activeTask("nightly audit")

        val pending = call("phone_local_run", JSONObject()
            .put("taskId", taskId)
            .put("operationId", "op-1")
            .put("tool", "shell_exec")
            .put("arguments", JSONObject().put("command", "echo hi").put("timeout_sec", 900)))
        assertFalse(textOf(pending), pending.isError)
        val submitted = JSONObject(textOf(pending))
        assertEquals("WAITING", submitted.optString("status"))
        assertEquals("op-1", submitted.optString("operationId"))
        assertEquals(0, biz.calls)

        // Nothing runs before the phone approves the operation.
        val early = call("phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertTrue(early.isError)
        assertTrue(textOf(early).contains(McpApprovalCore.ErrorCode.OPERATION_NOT_WAITING.name))
        assertEquals(0, biz.calls)

        assertTrue(manager.approveOperation("op-1", sourceFileHash = null, operationFingerprint = null) is McpApprovalCore.Outcome.Ok)

        val executed = call("phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertFalse(textOf(executed), executed.isError)
        assertEquals(1, biz.calls)
        assertEquals("shell_exec", biz.lastTool)
        assertTrue(textOf(executed).contains("EXECUTED"))
        // The business tool's own content is surfaced alongside the envelope.
        assertTrue(executed.content.length() >= 2)
        assertEquals("ran:shell_exec", executed.content.optJSONObject(1)?.optString("text"))

        // Replay is idempotent: same call, no second execution.
        val replay = call("phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertFalse(textOf(replay), replay.isError)
        assertEquals(1, biz.calls)
        assertTrue(textOf(replay).contains("replayed"))
    }

    @Test
    fun `a run cannot be filed before the task is activated`() {
        val created = call("phone_task_request", JSONObject().put("name", "pending task"))
        val taskId = JSONObject(textOf(created)).optString("taskId")
        val res = call("phone_local_run", JSONObject()
            .put("taskId", taskId)
            .put("operationId", "op-0")
            .put("tool", "shell_exec")
            .put("arguments", JSONObject().put("command", "echo hi")))
        assertTrue(res.isError)
        assertTrue(textOf(res).contains(McpApprovalCore.ErrorCode.TASK_NOT_ACTIVE.name))
    }

    @Test
    fun `the timeout is clamped to the run ceiling`() {
        val taskId = activeTask("clamp")
        call("phone_local_run", JSONObject()
            .put("taskId", taskId)
            .put("operationId", "op-1")
            .put("tool", "shell_exec")
            .put("arguments", JSONObject().put("command", "echo hi").put("timeout_sec", 900)))
        manager.approveOperation("op-1", null, null)
        val res = call("phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertFalse(textOf(res), res.isError)
        assertEquals(McpApprovalCore.MAX_RUN_SECONDS, biz.lastArguments?.optInt("timeout_sec"))
    }

    @Test
    fun `status tool answers for a task id`() {
        val taskId = activeTask("status view")
        call("phone_local_run", JSONObject()
            .put("taskId", taskId)
            .put("operationId", "op-1")
            .put("tool", "shell_exec")
            .put("arguments", JSONObject().put("command", "echo hi")))
        val res = call("phone_task_status", JSONObject().put("taskId", taskId))
        assertFalse(textOf(res), res.isError)
        val body = JSONObject(textOf(res))
        assertEquals(taskId, body.optString("taskId"))
        assertEquals("ACTIVE", body.optString("status"))
        val ops = body.optJSONArray("operations")
        assertEquals(1, ops.length())
        assertEquals("op-1", ops.optJSONObject(0).optString("operationId"))
        assertEquals("WAITING", ops.optJSONObject(0).optString("status"))
        assertFalse(ops.optJSONObject(0).optBoolean("executed"))
    }

    @Test
    fun `an operation may not target the approval tools themselves`() {
        val taskId = activeTask("loop")
        val filed = call("phone_local_run", JSONObject()
            .put("taskId", taskId)
            .put("operationId", "op-loop")
            .put("tool", "phone_task_request")
            .put("arguments", JSONObject().put("name", "nested")))
        assertFalse(textOf(filed), filed.isError)
        manager.approveOperation("op-loop", null, null)
        // Even an approved operation cannot re-enter the approval surface.
        val res = call("phone_operation_execute", JSONObject().put("operationId", "op-loop"))
        assertTrue(res.isError)
        assertTrue(textOf(res).contains(McpApprovalCore.ErrorCode.INVALID_NAME.name))
        assertEquals(0, biz.calls)
    }
}
