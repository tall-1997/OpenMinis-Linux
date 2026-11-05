package com.openminis.app.mcp.server.approval

import com.openminis.app.mcp.server.McpServerCore
import com.openminis.app.mcp.server.McpServerCore.Response
import com.openminis.app.mcp.server.approval.McpApprovalCore.ErrorCode
import com.openminis.app.mcp.server.approval.McpApprovalCore.OperationStatus
import com.openminis.app.mcp.server.approval.McpApprovalCore.Outcome
import com.openminis.app.mcp.server.approval.McpApprovalCore.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-mcp-approval] JSON-RPC surface for the three-phase approval flow.
 * All decision logic lives in [McpApprovalCore]; this class only maps params
 * to outcomes and outcomes to JSON-RPC envelopes.
 *
 * [P1-dead-branches] The REAL call graph has exactly two entry points:
 *  - External MCP clients: only the 4 methods in [EXTERNAL_METHODS] reach
 *    [handle], and only through the tool surface ([com.openminis.app.mcp.server.tools.ApprovalTools].
 *    invoke, which gates on [isExternalMethod] first). An external caller can
 *    *request* and *run*, it can never grant itself permission.
 *  - The approvals UI ([com.openminis.app.ui.settings.McpApprovalSection])
 *    calls [McpApprovalManager] DIRECTLY (approveTask / revokeTask /
 *    approveOperation / cancelOperation / pruneExpired) and uses
 *    [executeApproved] for runs — it never goes through [handle].
 *
 * Everything else (task list / delete / cleanup / revoke over RPC) used to sit
 * here as unreachable `phone_*` branches and was removed — git history keeps
 * the shapes. The phone-only actions remain available exactly where they
 * always were reachable from: the in-process UI.
 *
 * Reachable method table:
 *  - phone_task_request     plan a task grant (PENDING) → {taskId, status}
 *  - phone_local_run        submit one run (WAITING) → {taskId, operationId, requestId, status, approvalTtlMs}
 *  - phone_operation_execute run an APPROVED operation (source + fingerprint checked)
 *  - phone_task_status      read-only view: one operation, or a task with all its operations
 */
class McpApprovalRpc(
    private val manager: McpApprovalManager,
    private val executor: McpApprovalExecutor,
) {

    /**
     * The three-phase surface an external MCP client may reach — exposed as
     * tools, never as raw RPC. Approval actions stay phone-only: an external
     * caller can *request* and *run*, it can never grant itself permission.
     */
    fun isExternalMethod(method: String): Boolean = method in EXTERNAL_METHODS

    fun handle(method: String, params: JSONObject?, id: Any?): Response = try {
        when (method) {
            "phone_task_request" -> handleTaskCreate(params, id)
            "phone_local_run" -> handleLocalRun(params, id)
            "phone_task_status" -> handleTaskStatus(params, id)
            "phone_operation_execute" -> handleExecute(params, id)
            else -> McpServerCore.rpcError(id, -32601, "Unknown method: $method")
        }
    } catch (t: Throwable) {
        McpServerCore.rpcError(id, -32603, "internal error: ${t.message}")
    }

    private companion object {
        val EXTERNAL_METHODS = setOf(
            "phone_task_request",
            "phone_local_run",
            "phone_operation_execute",
            "phone_task_status",
        )
    }

    // -- helpers -------------------------------------------------------------

    private fun err(id: Any?, code: ErrorCode, message: String): Response =
        McpServerCore.rpcError(id, -32001, message, data = JSONObject().put("approvalCode", code.name))

    private fun outcome(id: Any?, out: Outcome, result: JSONObject): Response =
        when (out) {
            is Outcome.Ok -> McpServerCore.jsonRpcResult(id, result)
            is Outcome.Err -> err(id, out.code, out.message)
        }

    private fun str(params: JSONObject?, key: String, default: String? = null): String? =
        params?.optString(key, "")?.takeIf { it.isNotBlank() } ?: default

    private fun opJson(op: McpApprovalCore.Operation, executed: Boolean): JSONObject = JSONObject().apply {
        put("operationId", op.operationId)
        put("taskId", op.taskId)
        put("requestId", op.requestId)
        put("tool", op.tool)
        put("status", op.status.name)
        put("createdAtMs", op.createdAtMs)
        put("approvedAtMs", op.approvedAtMs ?: JSONObject.NULL)
        put("executed", executed)
        put("sourceFileHashBound", op.sourceFileHash != null)
        put("fingerprintBound", op.operationFingerprint != null)
    }

    private fun taskJson(task: McpApprovalCore.Task): JSONObject = JSONObject().apply {
        put("taskId", task.taskId)
        put("name", task.name)
        put("status", task.status.name)
        put("createdAtMs", task.createdAtMs)
    }

    // -- methods -------------------------------------------------------------

    private fun handleTaskCreate(params: JSONObject?, id: Any?): Response {
        val taskId = str(params, "taskId") ?: McpApprovalStore.newId()
        val name = str(params, "name") ?: return err(id, ErrorCode.INVALID_NAME, "missing task 'name'")
        return outcome(id, manager.createTask(taskId, name), JSONObject()
            .put("taskId", taskId)
            .put("status", TaskStatus.PENDING.name))
    }

    private fun handleLocalRun(params: JSONObject?, id: Any?): Response {
        val taskId = str(params, "taskId") ?: return err(id, ErrorCode.INVALID_ID, "missing taskId")
        val tool = str(params, "tool") ?: return err(id, ErrorCode.INVALID_NAME, "missing tool name")
        val operationId = str(params, "operationId") ?: McpApprovalStore.newId()
        val argsObj = params?.opt("arguments")
        val argumentsJson = when (argsObj) {
            null -> "{}"
            is String -> argsObj
            else -> argsObj.toString()
        }
        // requestId is the content hash of the run; the caller may pass it (the
        // core re-derives and compares) or omit it (filled in here). Either way
        // the caller cannot rename a run's content after the fact.
        val requestId = str(params, "requestId")
            ?: McpApprovalCore.requestIdFor(taskId, tool, argumentsJson)
        val out = manager.submitRun(taskId, operationId, requestId, tool, argumentsJson)
        if (out is Outcome.Err) return err(id, out.code, out.message)
        return McpServerCore.jsonRpcResult(id, JSONObject()
            .put("taskId", taskId)
            .put("operationId", operationId)
            .put("requestId", requestId)
            .put("status", OperationStatus.WAITING.name)
            .put("approvalTtlMs", McpApprovalCore.APPROVAL_TTL_MS))
    }

    /**
     * Read-only lookup at either level: `operationId` answers for one operation,
     * `taskId` answers for the task plus every operation under it. Clients poll
     * this while the user decides on the phone, so it never mutates state.
     */
    private fun handleTaskStatus(params: JSONObject?, id: Any?): Response {
        val operationId = str(params, "operationId")
        if (operationId != null) {
            val op = manager.operation(operationId) ?: return err(id, ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
            val executed = operationId in manager.state().executedOperationIds
            return McpServerCore.jsonRpcResult(id, opJson(op, executed))
        }
        val taskId = str(params, "taskId") ?: return err(id, ErrorCode.INVALID_ID, "missing taskId or operationId")
        val task = manager.task(taskId) ?: return err(id, ErrorCode.TASK_NOT_FOUND, "task '$taskId' not found")
        val state = manager.state()
        val operations = JSONArray()
        state.operations.values
            .filter { it.taskId == taskId }
            .sortedWith(compareBy<McpApprovalCore.Operation>({ it.createdAtMs }, { it.operationId }))
            .forEach { operations.put(opJson(it, it.operationId in state.executedOperationIds)) }
        return McpServerCore.jsonRpcResult(id, taskJson(task).put("operations", operations))
    }

    // [P1-dead-branches] handleApproveOperation / handleExecute's phone-only
    // siblings (approve / revoke / list / cancel / cleanup / delete) were
    // removed with their unreachable `handle` branches: the approvals UI goes
    // through McpApprovalManager directly, never through this RPC surface.

    private fun handleExecute(params: JSONObject?, id: Any?): Response {
        val operationId = str(params, "operationId") ?: return err(id, ErrorCode.INVALID_ID, "missing operationId")
        val outcome = executeApproved(
            operationId = operationId,
            fingerprint = str(params, "operationFingerprint"),
            sourceContext = McpApprovalExecutor.SourceContext(
                sourceFileHash = str(params, "sourceFileHash"),
                sourceFilePath = str(params, "sourceFilePath"),
            ),
        )
        val failure = outcome.error
        if (failure != null) {
            if (failure.code == ErrorCode.ALREADY_EXECUTED) return replayExecuted(operationId, id)
            return err(id, failure.code, failure.message)
        }
        return McpServerCore.jsonRpcResult(id, outcome.result ?: JSONObject())
    }

    /**
     * The one execution pipeline: gate → content binding → run → commit.
     * Shared by the RPC surface and the in-process approvals UI, so a tap in
     * the sheet and a client call can never diverge in what they check.
     *
     * [fingerprint] null means "no content binding presented" — the gate only
     * passes when the grant itself carries no binding either.
     */
    fun executeApproved(
        operationId: String,
        fingerprint: String?,
        sourceContext: McpApprovalExecutor.SourceContext,
    ): ExecutionOutcome {
        when (val gate = manager.tryExecute(operationId)) {
            is Outcome.Err -> return ExecutionOutcome(error = gate)
            is Outcome.Ok -> Unit
        }
        when (val fp = manager.checkFingerprint(operationId, fingerprint)) {
            is Outcome.Err -> return ExecutionOutcome(error = fp)
            is Outcome.Ok -> Unit
        }

        val op = manager.operation(operationId)
            ?: return ExecutionOutcome(error = Outcome.Err(ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found"))
        // Write into the host directory; report the guest path back so the
        // client can open the artifacts it just produced.
        val taskRoot = manager.hostTaskRootFor(op.taskId)

        val run = executor.execute(op, taskRoot, sourceContext)
        if (!run.ok) {
            return ExecutionOutcome(
                error = Outcome.Err(
                    run.errorCode ?: ErrorCode.TASK_NOT_ACTIVE,
                    run.errorMessage ?: "execution rejected",
                ),
            )
        }
        val commitFailure = manager.markExecuted(operationId) as? Outcome.Err
        if (commitFailure != null) return ExecutionOutcome(error = commitFailure)

        val result = JSONObject()
            .put("operationId", operationId)
            .put("status", "EXECUTED")
            .put("executedAtMs", System.currentTimeMillis())
            .put("taskDir", manager.taskRootFor(op.taskId))
        run.callResult?.let { call ->
            result.put("isError", call.isError)
            result.put("content", call.content)
        }
        return ExecutionOutcome(result = result)
    }

    /** Either a JSON-RPC-shaped result or the first gate/commit failure. */
    data class ExecutionOutcome(val error: Outcome.Err? = null, val result: JSONObject? = null)

    /** Idempotent replay: an executed operation returns its stored result without running the tool again. */
    private fun replayExecuted(operationId: String, id: Any?): Response {
        val op = manager.operation(operationId)
            ?: return err(id, ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
        val taskRoot = manager.hostTaskRootFor(op.taskId)
        val stored = runCatching {
            JSONObject(File(taskRoot, "result.json").readText())
        }.getOrNull()
        val result = JSONObject()
            .put("operationId", operationId)
            .put("status", "EXECUTED")
            .put("executedAtMs", stored?.optLong("executedAtMs") ?: JSONObject.NULL)
            .put("taskDir", manager.taskRootFor(op.taskId))
            .put("replayed", true)
        if (stored != null) {
            result.put("isError", stored.optBoolean("isError"))
            result.put("content", stored.optJSONArray("content") ?: JSONArray())
        }
        return McpServerCore.jsonRpcResult(id, result)
    }
}
