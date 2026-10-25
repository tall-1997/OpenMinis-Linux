package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.mcp.client.McpJsonRpc
import com.openminis.app.mcp.client.McpNativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-mcp-native] First-class MCP tool: `servers` / `tools` / `call` against
 * the configured MCP servers, executed in-process instead of round-tripping
 * `minis-mcp-cli` through shell_execute → PRoot → Python daemon. The CLI
 * remains available as a fallback (interactive terminal, exotic cases).
 *
 * Gating: SecurityGateImpl classifies "mcp" as a NET tool — same confirm
 * behaviour the CLI path had via shell_execute. Per-tool disable switches
 * are enforced inside [McpNativeBridge] (MCPToolPolicy).
 */
object McpNativeTool {

    const val NAME = "mcp"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Call MCP (Model Context Protocol) servers natively — faster than minis-mcp-cli via shell. " +
            "Actions: 'servers' lists configured servers; 'tools' lists one server's tools; " +
            "'call' invokes a tool with a JSON arguments object.",
        parameters = mapOf(
            "action" to AgentToolParam(
                "string",
                "One of: servers, tools, call.",
                enumValues = listOf("servers", "tools", "call"),
            ),
            "server" to AgentToolParam("string", "Server id (required for tools/call; see action=servers)."),
            "tool" to AgentToolParam("string", "Tool name on that server (required for call)."),
            "arguments" to AgentToolParam("object", "Arguments object for the tool (call only). Omit for tools with no parameters."),
        ),
        required = listOf("action"),
        propertyOrdering = listOf("action", "server", "tool", "arguments"),
    )

    suspend fun execute(
        argsJson: String,
        context: Context,
        sessionId: String,
        repo: MCPRepository?,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (repo == null) {
            return@withContext ToolExecutionResult(
                "MCP is not available in this session.",
                false,
                errorCode = ToolErrorCode.UNSUPPORTED,
                toolTitle = NAME,
            )
        }
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return@withContext invalid("arguments must be a JSON object")
        val action = args.optString("action", "").trim()
        val server = args.optString("server", "").trim()
        val tool = args.optString("tool", "").trim()
        when (action) {
            "servers" -> ToolExecutionResult(
                McpNativeBridge.serversOverview(repo, sessionId),
                true,
                toolTitle = "mcp servers",
            )
            "tools" -> {
                if (server.isEmpty()) return@withContext invalid("'server' is required for action=tools")
                try {
                    ToolExecutionResult(
                        McpNativeBridge.listTools(context, repo, sessionId, server),
                        true,
                        toolTitle = "mcp tools:$server",
                    )
                } catch (e: McpJsonRpc.McpError) {
                    failure(e, "mcp tools:$server")
                }
            }
            "call" -> {
                if (server.isEmpty()) return@withContext invalid("'server' is required for action=call")
                if (tool.isEmpty()) return@withContext invalid("'tool' is required for action=call")
                val arguments = when (val raw = args.opt("arguments")) {
                    null, JSONObject.NULL -> JSONObject()
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrElse {
                        return@withContext invalid("'arguments' must be a JSON object, got an unparseable string")
                    }
                    else -> return@withContext invalid("'arguments' must be a JSON object")
                }
                try {
                    val outcome = McpNativeBridge.callTool(context, repo, sessionId, server, tool, arguments)
                    if (outcome.isError) {
                        ToolExecutionResult(
                            outcome.text,
                            false,
                            errorCode = ToolErrorCode.EXECUTION_FAILED,
                            recoveryHint = "The MCP server reported this tool call as failed. Check the arguments against action=tools schema.",
                            toolTitle = "mcp call:$server/$tool",
                        )
                    } else {
                        ToolExecutionResult(outcome.text, true, toolTitle = "mcp call:$server/$tool")
                    }
                } catch (e: McpJsonRpc.McpError) {
                    failure(e, "mcp call:$server/$tool")
                }
            }
            else -> invalid("'action' must be one of: servers, tools, call (got '${action}')")
        }
    }

    private fun invalid(msg: String): ToolExecutionResult = ToolExecutionResult(
        msg,
        false,
        errorCode = ToolErrorCode.INVALID_ARGUMENTS,
        recoveryHint = "Example: {\"action\":\"call\",\"server\":\"git\",\"tool\":\"get_diff\",\"arguments\":{}}",
        toolTitle = NAME,
    )

    fun failure(e: McpJsonRpc.McpError, title: String): ToolExecutionResult {
        val (code, hint) = when (e.code) {
            McpJsonRpc.McpError.CONNECTION_ERROR -> ToolErrorCode.NETWORK_ERROR to
                "Server unreachable. Check its url/command in Settings → MCP; for stdio servers make sure the sandbox has booted (run any shell command first)."
            McpJsonRpc.McpError.TIMEOUT -> ToolErrorCode.TIMEOUT to
                "Server took too long. Retry once; for slow startups raise startupTimeoutSeconds in servers.json."
            McpJsonRpc.McpError.AUTH_REQUIRED -> ToolErrorCode.AUTH_REQUIRED to
                "Authorize this server in Settings → MCP (OAuth), or check its Authorization header value."
            "NOT_FOUND" -> ToolErrorCode.NOT_FOUND to
                "Use action=servers to see configured server ids."
            "TOOL_DISABLED" -> ToolErrorCode.PERMISSION_DENIED to
                "Enable the tool in Settings → MCP, or pick another tool."
            else -> ToolErrorCode.EXECUTION_FAILED to null
        }
        return ToolExecutionResult(
            e.message ?: e.code,
            false,
            errorCode = code,
            recoveryHint = hint,
            toolTitle = title,
        )
    }
}
