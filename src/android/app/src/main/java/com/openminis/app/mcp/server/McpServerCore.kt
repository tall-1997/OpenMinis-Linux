package com.openminis.app.mcp.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON-RPC 2.0 / MCP handler for the built-in MCP server.
 * Zero Android dependencies — socket layer calls [handle] and passes the result back.
 */
object McpServerCore {

    const val PROTOCOL_VERSION = "2025-03-26"
    const val SERVER_NAME = "openminis-mcp"

    /** Wire-level MCP error codes. */
    object ErrorCode {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        /** Server-level: gate denied or need-confirm with no interactive path. */
        const val GATE_DENIED = -32001
        /** Server-level: tool execution failed. */
        const val TOOL_ERROR = -32002
        /** Server-level: path outside allowed prefixes. */
        const val PATH_DENIED = -32003
        /** Server-level: missing or invalid auth token. */
        const val AUTH_REQUIRED = -32004
    }

    // ─── Data types ────────────────────────────────────────────────────────

    // [P2-dead-code] data class Request removed with parseJsonRpc — its last
    // constructor caller. The server routes via handle()'s explicit params.

    data class Response(
        val statusCode: Int,
        val body: String,
        val contentType: String = "application/json",
    )

    /** Tool dispatch contract — inject real or fake implementations. */
    interface ToolDispatcher {
        fun listTools(): JSONArray
        fun callTool(name: String, arguments: JSONObject): CallResult
    }

    data class CallResult(
        val content: JSONArray,
        val isError: Boolean = false,
        /** Non-null maps this result onto a JSON-RPC error envelope with this code. */
        val errorCode: Int? = null,
        /** Human-readable reason carried in `error.data` when [errorCode] is set. */
        val errorData: String? = null,
    )

    // ─── Main entry ────────────────────────────────────────────────────────

    /**
     * Pure handler: JSON-RPC method → HTTP response.
     *
     * @param method   JSON-RPC method name
     * @param params   JSON-RPC params object (nullable for no-param calls)
     * @param headers  HTTP request headers (used for auth)
     * @param id       JSON-RPC id; null means notification → 202 empty response
     * @param dispatcher tool implementations (injected)
     * @param authToken optional Bearer token; null/blank = auth disabled
     */
    fun handle(
        method: String,
        params: JSONObject?,
        headers: Map<String, String>,
        id: Any?,
        dispatcher: ToolDispatcher,
        authToken: String?,
        serverVersion: String = "0.0.0",
    ): Response {
        // Auth check
        if (!authToken.isNullOrBlank()) {
            val authHeader = headers["authorization"] ?: headers["Authorization"] ?: ""
            val expected = "Bearer $authToken"
            if (authHeader != expected) {
                return rpcError(id, ErrorCode.AUTH_REQUIRED, "Missing or invalid Authorization header", 401)
            }
        }

        return when (method) {
            "initialize" -> handleInitialize(params, id, serverVersion)
            "ping" -> handlePing(id)
            "tools/list" -> handleToolsList(dispatcher, id)
            "tools/call" -> handleToolsCall(params, dispatcher, id)
            // Notifications: respond 202 empty
            else -> {
                if (method.startsWith("notifications/")) {
                    Response(202, "", "text/plain")
                } else if (id == null) {
                    // Unknown notification → 202 (best-effort)
                    Response(202, "", "text/plain")
                } else {
                    rpcError(id, ErrorCode.METHOD_NOT_FOUND, "Unknown method: $method")
                }
            }
        }
    }

    // ─── Method handlers ───────────────────────────────────────────────────

    private fun handleInitialize(params: JSONObject?, id: Any?, serverVersion: String): Response {
        val result = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject())
            })
            put("serverInfo", JSONObject().apply {
                put("name", SERVER_NAME)
                put("version", serverVersion)
            })
        }
        return jsonRpcResult(id, result)
    }

    private fun handlePing(id: Any?): Response {
        return jsonRpcResult(id, JSONObject())
    }

    private fun handleToolsList(dispatcher: ToolDispatcher, id: Any?): Response {
        val tools = dispatcher.listTools()
        val result = JSONObject().apply {
            put("tools", tools)
        }
        return jsonRpcResult(id, result)
    }

    private fun handleToolsCall(params: JSONObject?, dispatcher: ToolDispatcher, id: Any?): Response {
        if (params == null) {
            return rpcError(id, ErrorCode.INVALID_PARAMS, "Missing params")
        }
        val toolName = params.optString("name", "").ifEmpty {
            return rpcError(id, ErrorCode.INVALID_PARAMS, "Missing tool 'name'")
        }
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val result = dispatcher.callTool(toolName, arguments)
        // Gate denial / path denial: the dispatcher asks for a JSON-RPC error
        // envelope instead of a normal (isError) tool result.
        if (result.errorCode != null) {
            val message = firstText(result.content) ?: "tool call rejected"
            return rpcError(id, result.errorCode, message, data = result.errorData)
        }
        val body = JSONObject().apply {
            put("content", result.content)
            if (result.isError) put("isError", true)
        }
        return jsonRpcResult(id, body)
    }

    // ─── Response builders ─────────────────────────────────────────────────

    private fun firstText(content: JSONArray): String? {
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") return part.optString("text")
        }
        return null
    }

    fun jsonRpcResult(id: Any?, result: JSONObject): Response {
        if (id == null) {
            // Notification — no response body per JSON-RPC 2.0
            return Response(202, "", "text/plain")
        }
        val envelope = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
        return Response(200, envelope.toString(), "application/json")
    }

    /**
     * Error envelope with an explicit `"id": null` — for requests whose id
     * could not be determined (body parse failure, malformed request).
     * JSON-RPC 2.0 requires a reply here, unlike true notifications.
     */
    fun rpcErrorNullId(code: Int, message: String, httpStatus: Int = 400): Response {
        val envelope = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }
        return Response(httpStatus, envelope.toString(), "application/json")
    }

    fun rpcError(id: Any?, code: Int, message: String, httpStatus: Int = 200, data: Any? = null): Response {
        if (id == null) {
            // Notification error — still nothing to return per spec
            return Response(202, "", "text/plain")
        }
        val errorObj = JSONObject().apply {
            put("code", code)
            put("message", message)
            if (data != null) {
                put("data", if (data is JSONObject || data is JSONArray) data else data.toString())
            }
        }
        val envelope = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", errorObj)
        }
        return Response(httpStatus, envelope.toString(), "application/json")
    }

    // ─── HTTP parsing helpers ──────────────────────────────────────────────

    // [P2-dead-code] parseHttpRequest/ParsedRequest/parseJsonRpc were removed:
    // zero production callers (the live request parse is the byte-level inline
    // implementation in McpHttpServer.handleConnection, which handles
    // Content-Length chunk reading the String-based copies cannot) and their
    // semantics had drifted from the inline parser. Git history keeps them.

    /** Build a minimal HTTP response string. */
    fun buildHttpResponse(response: Response): String {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ${response.statusCode} ")
        sb.append(when (response.statusCode) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            else -> "Unknown"
        })
        sb.append("\r\n")
        sb.append("Content-Type: ${response.contentType}\r\n")
        if (response.body.isNotEmpty()) {
            val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
        }
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        if (response.body.isNotEmpty()) {
            sb.append(response.body)
        }
        return sb.toString()
    }

    // [P2-dead-code] methodNotAllowedResponse / corsPreflightResponse were
    // removed: the server writes its 405 / 204 (OPTIONS) responses inline in
    // handleConnection; these builders had no production caller. Git history.
}