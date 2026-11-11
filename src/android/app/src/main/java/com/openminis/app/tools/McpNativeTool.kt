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
            "'call' invokes a tool with a JSON arguments object; 'resources'/'read_resource' list and read " +
            "server resources; 'prompts'/'get_prompt' list and fetch prompt templates. " +
            "resources/prompts need a server that implements them — an unsupported server answers with a clean not-supported message.",
        parameters = mapOf(
            "action" to AgentToolParam(
                "string",
                "One of: servers, tools, call, resources, read_resource, prompts, get_prompt.",
                enumValues = listOf("servers", "tools", "call", "resources", "read_resource", "prompts", "get_prompt"),
            ),
            "server" to AgentToolParam("string", "Server id (required for all actions except servers)."),
            "tool" to AgentToolParam("string", "Tool name on that server (required for call)."),
            "arguments" to AgentToolParam("object", "Arguments object for the tool (call) or prompt variables (get_prompt). Omit when the tool/prompt takes none."),
            "uri" to AgentToolParam("string", "Resource uri (required for read_resource; see action=resources for the list)."),
            "name" to AgentToolParam("string", "Prompt name (required for get_prompt; see action=prompts for the list)."),
        ),
        required = listOf("action"),
        propertyOrdering = listOf("action", "server", "tool", "arguments", "uri", "name"),
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
            "resources" -> {
                if (server.isEmpty()) return@withContext invalid("'server' is required for action=resources")
                wrapServerSupport(server) {
                    McpNativeBridge.listResources(context, repo, sessionId, server)
                }
            }
            "read_resource" -> {
                val uri = args.optString("uri").trim()
                if (server.isEmpty()) return@withContext invalid("'server' is required for action=read_resource")
                if (uri.isEmpty()) return@withContext invalid("'uri' is required for action=read_resource")
                wrapServerSupport(server) {
                    McpNativeBridge.readResource(context, repo, sessionId, server, uri)
                }
            }
            "prompts" -> {
                if (server.isEmpty()) return@withContext invalid("'server' is required for action=prompts")
                wrapServerSupport(server) {
                    McpNativeBridge.listPrompts(context, repo, sessionId, server)
                }
            }
            "get_prompt" -> {
                val name = args.optString("name").trim()
                if (server.isEmpty()) return@withContext invalid("'server' is required for action=get_prompt")
                if (name.isEmpty()) return@withContext invalid("'name' is required for action=get_prompt")
                val pargs = when (val raw = args.opt("arguments")) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                    else -> JSONObject()
                }
                wrapServerSupport(server) {
                    McpNativeBridge.getPrompt(context, repo, sessionId, server, name, pargs)
                }
            }
            else -> invalid("'action' must be one of: servers, tools, call, resources, read_resource, prompts, get_prompt (got '${action}')")
        }
    }

    /**
     * Runs a resources/prompts passthrough and converts the interface's
     * UnsupportedOperationException into a clean result — a server that only
     * speaks the tools subset is a normal state, not an error to retry. The
     * message is "resources/list not supported by ..." on BOTH transports
     * (the interface default and McpHttpClient's -32601 normalization), so
     * one substringBefore extracts the method name for the hint.
     */
    private inline fun wrapServerSupport(server: String, block: () -> String): ToolExecutionResult =
        runCatching { ToolExecutionResult(block(), true, toolTitle = "mcp:$server") }
            .getOrElse { e ->
                when (e) {
                    is UnsupportedOperationException -> ToolExecutionResult(
                        "$server does not support " + e.message.orEmpty()
                            .substringBefore(" not supported") + " — pick a server that implements it.",
                        false,
                        errorCode = ToolErrorCode.UNSUPPORTED,
                        toolTitle = NAME,
                    )
                    else -> throw e
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
