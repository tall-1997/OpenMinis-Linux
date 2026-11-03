package com.openminis.app.mcp.server.approval

import com.openminis.app.mcp.server.McpServerCore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [T-mcp-approval-ui] The approvals sheet executes through
 * [McpApprovalRpc.executeApproved] — the same pipeline the external
 * `phone_operation_execute` method runs. These tests pin that in-process path
 * on its own so a UI-driven run can never drift from the RPC contract:
 * one gate, one executor, one commit, identical refusals.
 */
class McpApprovalUiPathTest {

    private class CountingDispatcher : McpServerCore.ToolDispatcher {
        var calls = 0
        var lastName: String? = null
        override fun listTools(): JSONArray = JSONArray()
        override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult {
            calls++
            lastName = name
            return McpServerCore.CallResult(
                JSONArray().put(JSONObject().put("type", "text").put("text", "ran:$name")),
            )
        }
    }

    private lateinit var dispatcher: CountingDispatcher
    private lateinit var rpc: McpApprovalRpc
    private lateinit var manager: McpApprovalManager

    @Before
    fun setUp() {
        McpApprovalStore.taskRootPrefix =
            File(System.getProperty("java.io.tmpdir"), "mcp-approval-ui-test").path + "/"
        dispatcher = CountingDispatcher()
        manager = McpApprovalManager(McpApprovalStore(ApprovalFakePrefs()))
        rpc = McpApprovalRpc(manager, McpApprovalExecutor(dispatcher))
    }

    @After
    fun tearDown() {
        McpApprovalStore.taskRootPrefix = McpApprovalStore.TASK_ROOT_PREFIX
        File(McpApprovalStore.taskRootFor("task-1")).deleteRecursively()
    }

    /** The UI never speaks JSON-RPC: it calls the manager directly, like the sheet does. */
    private fun activeTask(taskId: String = "task-1") {
        assertTrue(manager.createTask(taskId, "demo") is McpApprovalCore.Outcome.Ok)
        assertTrue(manager.approveTask(taskId) is McpApprovalCore.Outcome.Ok)
    }

    private fun waitingOp(operationId: String = "op-1") {
        val out = manager.submitRun(
            taskId = "task-1",
            operationId = operationId,
            requestId = McpApprovalCore.requestIdFor("task-1", "shell_exec", """{"command":"echo hi"}"""),
            tool = "shell_exec",
            argumentsJson = """{"command":"echo hi"}""",
        )
        assertTrue("submit failed: $out", out is McpApprovalCore.Outcome.Ok)
    }

    private fun runUi(operationId: String = "op-1", fingerprint: String? = null) =
        rpc.executeApproved(
            operationId = operationId,
            fingerprint = fingerprint,
            sourceContext = McpApprovalExecutor.SourceContext(null, null),
        )

    @Test
    fun `ui run executes an approved operation and commits it`() {
        activeTask()
        waitingOp()
        assertTrue(manager.approveOperation("op-1", null, null) is McpApprovalCore.Outcome.Ok)

        val outcome = runUi()

        assertNull("unexpected refusal: ${outcome.error?.message}", outcome.error)
        val result = outcome.result
        assertNotNull(result)
        assertEquals("EXECUTED", result!!.optString("status"))
        assertEquals("shell_exec", dispatcher.lastName)
        assertEquals("the tool must run exactly once", 1, dispatcher.calls)
        assertTrue(
            "commit must be recorded for the badge",
            "op-1" in manager.state().executedOperationIds,
        )

        // Same artifact layout the RPC path writes.
        val dir = File(McpApprovalStore.taskRootFor("task-1"))
        assertTrue(File(dir, "arguments.json").isFile)
        assertTrue(File(dir, "result.json").isFile)
        // The wire value is taskRootFor verbatim (trailing separator included):
        // File(...).path would normalise it away and pin a second spelling of
        // the same directory, exactly the drift the RPC test already guards.
        assertEquals(McpApprovalStore.taskRootFor("task-1"), result.optString("taskDir"))
    }

    @Test
    fun `ui run before approval is refused and the tool never runs`() {
        activeTask()
        waitingOp()

        val outcome = runUi()

        assertEquals(McpApprovalCore.ErrorCode.OPERATION_NOT_WAITING, outcome.error?.code)
        assertEquals(0, dispatcher.calls)
        assertNull(outcome.result)
        assertTrue("op-1" !in manager.state().executedOperationIds)
    }

    @Test
    fun `a fingerprint bound at approval is enforced on the ui path`() {
        activeTask()
        waitingOp()
        assertTrue(manager.approveOperation("op-1", null, "fp-bound") is McpApprovalCore.Outcome.Ok)

        // The sheet has no fingerprint field: an unbound presentation is refused,
        // never silently treated as "unbound approval".
        val refused = runUi(fingerprint = null)
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH, refused.error?.code)
        assertEquals(0, dispatcher.calls)
        assertTrue("op-1" !in manager.state().executedOperationIds)

        // Presenting the bound value runs it.
        val accepted = runUi(fingerprint = "fp-bound")
        assertNull(accepted.error?.message)
        assertEquals("EXECUTED", accepted.result!!.optString("status"))
        assertEquals(1, dispatcher.calls)
    }

    @Test
    fun `ui run after revocation reports the operation as gone`() {
        activeTask()
        waitingOp()
        assertTrue(manager.approveOperation("op-1", null, null) is McpApprovalCore.Outcome.Ok)
        assertTrue(manager.revokeTask("task-1") is McpApprovalCore.Outcome.Ok)

        val outcome = runUi()

        assertEquals(McpApprovalCore.ErrorCode.OPERATION_NOT_FOUND, outcome.error?.code)
        assertEquals(0, dispatcher.calls)
    }

    @Test
    fun `ui run of an unknown operation is refused`() {
        activeTask()

        val outcome = runUi(operationId = "op-missing")

        assertEquals(McpApprovalCore.ErrorCode.OPERATION_NOT_FOUND, outcome.error?.code)
        assertEquals(0, dispatcher.calls)
    }

    @Test
    fun `ui run twice keeps the idempotent contract`() {
        activeTask()
        waitingOp()
        assertTrue(manager.approveOperation("op-1", null, null) is McpApprovalCore.Outcome.Ok)

        assertNull(runUi().error?.message)
        val second = runUi()

        assertEquals(
            "second run reports ALREADY_EXECUTED rather than running again",
            McpApprovalCore.ErrorCode.ALREADY_EXECUTED,
            second.error?.code,
        )
        assertEquals(1, dispatcher.calls)
    }
}
