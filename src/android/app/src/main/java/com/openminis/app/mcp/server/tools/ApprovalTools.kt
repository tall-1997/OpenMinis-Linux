package com.openminis.app.mcp.server.tools

import com.openminis.app.mcp.server.McpServerCore
import com.openminis.app.mcp.server.approval.McpApprovalRpc
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-mcp-approval] The three-phase approval flow as first-class MCP tools.
 *
 * An external MCP client only speaks `tools/list` + `tools/call`, so the
 * approval surface has to be tools rather than raw `phone_*` RPC methods:
 *
 *  1. `phone_task_request`    — announce a task, get a PENDING taskId.
 *  2. `phone_local_run`       — submit one run; requestId is its content hash.
 *  3. `phone_operation_execute` — run an operation the phone already approved.
 *
 * Approval itself (`phone_task_approve`, `phone_task_approve_op`, revoke,
 * delete…) is deliberately absent here: the user grants permission on the
 * phone, an external caller can never grant it to itself. Those methods stay
 * on [McpApprovalRpc] for the in-process approvals UI.
 *
 * Registry entry point: a null runtime yields an empty surface, so a server
 * started without the approval store never advertises `phone_*` tools — an
 * advertised-but-absent tool is exactly what the announced-tool gate forbids.
 */
internal fun approvalToolSpecs(rpc: McpApprovalRpc?): List<McpToolSpec> =
    if (rpc == null) emptyList() else approvalTools(rpc)

internal fun approvalTools(rpc: McpApprovalRpc): List<McpToolSpec> = listOf(
    McpToolSpec(
        name = "phone_task_request",
        description = McpToolSchemas.phoneTaskRequest,
        schema = McpToolSchemas.phoneTaskRequestSchema,
        handler = { invoke(rpc, "phone_task_request", it) },
    ),
    McpToolSpec(
        name = "phone_local_run",
        description = McpToolSchemas.phoneLocalRun,
        schema = McpToolSchemas.phoneLocalRunSchema,
        handler = { invoke(rpc, "phone_local_run", it) },
    ),
    McpToolSpec(
        name = "phone_operation_execute",
        description = McpToolSchemas.phoneOperationExecute,
        schema = McpToolSchemas.phoneOperationExecuteSchema,
        handler = { invoke(rpc, "phone_operation_execute", it) },
    ),
    McpToolSpec(
        name = "phone_task_status",
        description = McpToolSchemas.phoneTaskStatus,
        schema = McpToolSchemas.phoneTaskStatusSchema,
        handler = { invoke(rpc, "phone_task_status", it) },
    ),
)

/**
 * Adapter: run one `phone_*` RPC and flatten its JSON result into a tool
 * result. A rejected request becomes a normal `isError` tool result — the
 * protocol call itself succeeded, the answer is "denied" — so clients see the
 * reason instead of a transport-level JSON-RPC fault.
 */
private fun invoke(rpc: McpApprovalRpc, method: String, arguments: JSONObject): McpServerCore.CallResult {
    if (!rpc.isExternalMethod(method)) {
        return McpToolShared.error("Error: '$method' is not callable from the external tool surface")
    }
    val response = rpc.handle(method, arguments, "tool")
    val body = runCatching { JSONObject(response.body) }.getOrNull()
        ?: return McpToolShared.error("Error: malformed response from the approval runtime")

    body.optJSONObject("error")?.let { error ->
        val message = error.optString("message", "request rejected")
        val code = error.optJSONObject("data")?.optString("approvalCode").orEmpty()
        val suffix = if (code.isNotBlank()) " [$code]" else ""
        return McpToolShared.error("Error: $message$suffix")
    }

    val result = body.optJSONObject("result") ?: JSONObject()
    val content = JSONArray().put(McpToolShared.text(result.toString(2)))
    // phone_operation_execute embeds the tool's own content; surface it too so
    // clients get the same payload shape they get from any other tool.
    result.optJSONArray("content")?.let { inner -> for (i in 0 until inner.length()) content.put(inner.opt(i)) }
    return McpServerCore.CallResult(content, isError = result.optBoolean("isError", false))
}
