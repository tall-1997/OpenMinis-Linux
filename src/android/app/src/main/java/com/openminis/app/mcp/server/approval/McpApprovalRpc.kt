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
 * [T-mcp-approval] JSON-RPC surface for the three-phase approval flow,
 * mounted under the `phone_` method family. All decision logic lives in
 * [McpApprovalCore]; this class only maps params to outcomes and outcomes to
 * JSON-RPC envelopes.
 *
 * Method table:
 *  - phone_task_create     create a named task (PENDING) → {taskId, status}
 *  - phone_task_approve    task grant PENDING → ACTIVE → {status, approvalTtlMs}
 *  - phone_task_revoke     revoke task + outstanding runs → {status}
 *  - phone_task_list       full state dump for the approvals UI
 *  - phone_task_request    submit one run (WAITING) → {taskId, operationId, requestId, status, approvalTtlMs}
 *  - phone_task_status     one operation's view
 *  - phone_task_approve_op per-run grant WAITING → APPROVED (source binding optional)
 *  - phone_operation_execute run an APPROVED operation (source + fingerprint checked)
 *  - phone_task_cancel     drop one WAITING operation
 *  - phone_task_cleanup    prune expired grants + stale task dirs
 *  - phone_task_delete     delete task + all its operations + task dir
 */
class McpApprovalRpc(
    private val manager: McpApprovalManager,
    private val executor: McpApprovalExecutor,
) {

    fun isApprovalMethod(method: String): Boolean = method.startsWith("phone_")

    fun handle(method: String, params: JSONObject?, id: Any?): Response = try {
        when (method) {
            "phone_task_create" -> handleTaskCreate(params, id)
            "phone_task_approve" -> handleTaskApprove(params, id)
            "phone_task_revoke" -> handleTaskRevoke(params, id)
            "phone_task_list" -> handleTaskList(params, id)
            "phone_task_request" -> handleTaskRequest(params, id)
            "phone_task_status" -> handleTaskStatus(params, id)
            "phone_task_approve_op" -> handleApproveOperation(params, id)
            "phone_operation_execute" -> handleExecute(params, id)
            "phone_task_cancel" -> handleCancel(params, id)
            "phone_task_cleanup" -> handleCleanup(params, id)
            "phone_task_delete" -> handleDelete(params, id)
            else -> McpServerCore.rpcError(id, -32601, "Unknown method: $method")
        }
    } catch (t: Throwable) {
        McpServerCore.rpcError(id, -32603, "internal error: ${t.message}")
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

    private fun handleTaskApprove(params: JSONObject?, id: Any?): Response {
        val taskId = str(params, "taskId") ?: return err(id, ErrorCode.INVALID_ID, "missing taskId")
        val out = manager.approveTask(taskId)
        val result = JSONObject()
            .put("taskId", taskId)
            .put("status", if (out is Outcome.Ok) TaskStatus.ACTIVE.name else manager.task(taskId)?.status?.name ?: "UNKNOWN")
            .put("approvalTtlMs", McpApprovalCore.APPROVAL_TTL_MS)
        return outcome(id, out, result)
    }

    private fun handleTaskRevoke(params: JSONObject?, id: Any?): Response {
        val taskId = str(params, "taskId") ?: return err(id, ErrorCode.INVALID_ID, "missing taskId")
        val out = manager.revokeTask(taskId)
        return outcome(id, out, JSONObject()
            .put("taskId", taskId)
            .put("status", if (out is Outcome.Ok) TaskStatus.REVOKED.name else manager.task(taskId)?.status?.name ?: "UNKNOWN"))
    }

    private fun handleTaskList(params: JSONObject?, id: Any?): Response {
        val state = manager.state()
        val tasks = org.json.JSONArray()
        state.tasks.values.sortedBy { it.createdAtMs }.forEach { tasks.put(taskJson(it)) }
        val ops = org.json.JSONArray()
        state.operations.values.sortedBy { it.createdAtMs }
            .forEach { ops.put(opJson(it, it.operationId in state.executedOperationIds)) }
        return McpServerCore.jsonRpcResult(id, JSONObject()
            .put("epoch", state.epoch)
            .put("tasks", tasks)
            .put("operations", ops))
    }

    private fun handleTaskRequest(params: JSONObject?, id: Any?): Response {
        val taskId = str(params, "taskId") ?: return err(id, ErrorCode.INVALID_ID, "missing taskId")
        val tool = str(params, "tool") ?: return err(id, ErrorCode.INVALID_NAME, "missing tool name")
        val operationId = str(params, "operationId") ?: McpApprovalStore.newId()
        val argsObj = params?.opt("arguments")
        val argumentsJson = when (argsObj) {
            null -> "{}"
            is String -> argsObj
            else -> argsObj.toString()
        }
        val out = manager.submitRun(taskId, operationId, tool, argumentsJson)
        if (out is Outcome.Err) return err(id, out.code, out.message)
        val result = JSONObject()
            .put("taskId", taskId)
            .put("operationId", operationId)
            .put("requestId", McpApprovalCore.requestIdFor(taskId, tool, argumentsJson))
            .put("status", OperationStatus.WAITING.name)
            .put("approvalTtlMs", McpApprovalCore.APPROVAL_TTL_MS)
        return McpServerCore.jsonRpcResult(id, result)
    }

    private fun handleTaskStatus(params: JSONObject?, id: Any?): Response {
        val operationId = str(params, "operationId") ?: return err(id, ErrorCode.INVALID_ID, "missing operationId")
        val op = manager.operation(operationId) ?: return err(id, ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
        val executed = operationId in manager.state().executedOperationIds
        return McpServerCore.jsonRpcResult(id, opJson(op, executed))
    }

    private fun handleApproveOperation(params: JSONObject?, id: Any?): Response {
        val operationId = str(params, "operationId") ?: return err(id, ErrorCode.INVALID_ID, "missing operationId")
        val sourceFileHash = str(params, "sourceFileHash")
        val fingerprint = str(params, "operationFingerprint")
        val out = manager.approveOperation(operationId, sourceFileHash, fingerprint)
        return outcome(id, out, JSONObject()
            .put("operationId", operationId)
            .put("status", OperationStatus.APPROVED.name)
            .put("approvalTtlMs", McpApprovalCore.APPROVAL_TTL_MS))
    }

    private fun handleExecute(params: JSONObject?, id: Any?): Response {
        val operationId = str(params, "operationId") ?: return err(id, ErrorCode.INVALID_ID, "missing operationId")
        val fingerprint = str(params, "operationFingerprint")
        val sourceContext = McpApprovalExecutor.SourceContext(
            sourceFileHash = str(params, "sourceFileHash"),
            sourceFilePath = str(params, "sourceFilePath"),
        )

        when (val gate = manager.tryExecute(operationId)) {
            is Outcome.Err -> {
                if (gate.code == ErrorCode.ALREADY_EXECUTED) return replayExecuted(operationId, id)
                return err(id, gate.code, gate.message)
            }
            is Outcome.Ok -> Unit
        }
        when (val fp = manager.checkFingerprint(operationId, fingerprint)) {
            is Outcome.Err -> return err(id, fp.code, fp.message)
            is Outcome.Ok -> Unit
        }

        val op = manager.operation(operationId)
            ?: return err(id, ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
        val taskRoot = manager.taskRootFor(op.taskId)

        val run = executor.execute(op, taskRoot, sourceContext)
        if (!run.ok) {
            return err(id, run.errorCode ?: ErrorCode.TASK_NOT_ACTIVE, run.errorMessage ?: "execution rejected")
        }

        val committed = manager.markExecuted(operationId)
        val call = run.callResult
        val result = JSONObject()
            .put("operationId", operationId)
            .put("status", "EXECUTED")
            .put("executedAtMs", System.currentTimeMillis())
            .put("taskDir", taskRoot)
        if (committed is Outcome.Err) {
            return err(id, committed.code, committed.message)
        }
        if (call != null) {
            result.put("isError", call.isError)
            result.put("content", call.content)
        }
        return McpServerCore.jsonRpcResult(id, result)
    }

    /** Idempotent replay: an executed operation returns its stored result without running the tool again. */
    private fun replayExecuted(operationId: String, id: Any?): Response {
        val op = manager.operation(operationId)
            ?: return err(id, ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
        val taskRoot = manager.taskRootFor(op.taskId)
        val stored = runCatching {
            JSONObject(File(taskRoot, "result.json").readText())
        }.getOrNull()
        val result = JSONObject()
            .put("operationId", operationId)
            .put("status", "EXECUTED")
            .put("executedAtMs", stored?.optLong("executedAtMs") ?: JSONObject.NULL)
            .put("taskDir", taskRoot)
            .put("replayed", true)
        if (stored != null) {
            result.put("isError", stored.optBoolean("isError"))
            result.put("content", stored.optJSONArray("content") ?: JSONArray())
        }
        return McpServerCore.jsonRpcResult(id, result)
    }

    private fun handleCancel(params: JSONObject?, id: Any?): Response {
        val operationId = str(params, "operationId") ?: return err(id, ErrorCode.INVALID_ID, "missing operationId")
        // Cancel = drop a WAITING operation; an executed one is immutable.
        val out = manager.cancelOperation(operationId)
        return outcome(id, out, JSONObject()
            .put("operationId", operationId)
            .put("status", "CANCELLED"))
    }

    private fun handleCleanup(params: JSONObject?, id: Any?): Response {
        val (state, removed) = manager.pruneExpired()
        // Delete task dirs whose operations were all pruned.
        val orphanTaskIds = state.tasks.keys.filter { taskId ->
            state.operations.none { it.value.taskId == taskId }
        }
        var dirsDeleted = 0
        orphanTaskIds.forEach { if (manager.deleteTaskDirectory(it)) dirsDeleted++ }
        return McpServerCore.jsonRpcResult(id, JSONObject()
            .put("removed", removed.size)
            .put("dirsDeleted", dirsDeleted))
    }

    private fun handleDelete(params: JSONObject?, id: Any?): Response {
        val taskId = str(params, "taskId") ?: return err(id, ErrorCode.INVALID_ID, "missing taskId")
        val out = manager.deleteTask(taskId)
        return outcome(id, out, JSONObject()
            .put("taskId", taskId)
            .put("status", "DELETED"))
    }
}
