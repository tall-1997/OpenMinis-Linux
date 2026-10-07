package com.openminis.app.mcp.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpServerCore 纯 JSON-RPC handler 的形状钉死：握手、错误码、token 门禁、
 * notification 语义。零 Android 依赖，纯 JVM 可跑。
 */
class McpServerCoreTest {

    private class EmptyDispatcher : McpServerCore.ToolDispatcher {
        override fun listTools(): JSONArray = JSONArray()
        override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult =
            McpServerCore.CallResult(JSONArray())
    }

    private class RecordingDispatcher : McpServerCore.ToolDispatcher {
        var lastName: String? = null
        var lastArgs: JSONObject = JSONObject()
        val tools = listOf("device_info", "shell_exec", "ui_read", "ui_action", "file_read")
        override fun listTools(): JSONArray = JSONArray(tools.map { JSONObject().put("name", it) })
        override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult {
            lastName = name
            lastArgs = arguments
            return McpServerCore.CallResult(JSONArray().put(McpToolDispatcher.text("ok")))
        }
    }

    private fun handle(
        method: String,
        params: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
        id: Int? = 1,
        dispatcher: McpServerCore.ToolDispatcher = EmptyDispatcher(),
        authToken: String? = null,
        serverVersion: String = "0.0.0",
    ): McpServerCore.Response =
        McpServerCore.handle(method, params, headers, id, dispatcher, authToken, serverVersion)

    private fun resultBody(r: McpServerCore.Response): JSONObject = JSONObject(r.body)
    private fun errorOf(r: McpServerCore.Response): JSONObject = resultBody(r).getJSONObject("error")
    private fun resultOf(r: McpServerCore.Response): JSONObject = resultBody(r).getJSONObject("result")

    // ─── initialize ───────────────────────────────────────────────────────

    @Test
    fun `initialize handshake returns protocol version and server info`() {
        val r = handle("initialize")
        assertEquals(200, r.statusCode)
        val result = resultOf(r)
        assertEquals(McpServerCore.PROTOCOL_VERSION, result.getString("protocolVersion"))
        assertEquals(McpServerCore.SERVER_NAME, result.getJSONObject("serverInfo").getString("name"))
        assertEquals("0.0.0", result.getJSONObject("serverInfo").getString("version"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
        assertEquals("2.0", resultBody(r).getString("jsonrpc"))
        assertEquals(1, resultBody(r).getInt("id"))
    }

    @Test
    fun `initialize surfaces injected server version`() {
        val r = handle("initialize", serverVersion = "9.9.9")
        assertEquals("9.9.9", resultOf(r).getJSONObject("serverInfo").getString("version"))
    }

    @Test
    fun `ping returns empty result envelope`() {
        val r = handle("ping")
        assertEquals(200, r.statusCode)
        assertTrue(resultOf(r).length() == 0)
    }

    // ─── tools ────────────────────────────────────────────────────────────

    @Test
    fun `tools list carries all five tools`() {
        val d = RecordingDispatcher()
        val r = handle("tools/list", dispatcher = d)
        val tools = resultOf(r).getJSONArray("tools")
        assertEquals(5, tools.length())
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        for (expected in d.tools) assertTrue("missing $expected", names.contains(expected))
    }

    @Test
    fun `tools call forwards name and arguments to dispatcher`() {
        val d = RecordingDispatcher()
        val params = JSONObject()
            .put("name", "file_read")
            .put("arguments", JSONObject().put("path", "/var/minis/a.txt"))
        val r = handle("tools/call", params, dispatcher = d)
        assertEquals(200, r.statusCode)
        assertEquals("file_read", d.lastName)
        assertEquals("/var/minis/a.txt", d.lastArgs.getString("path"))
        assertFalse(resultOf(r).optBoolean("isError"))
    }

    @Test
    fun `unknown method maps to -32601`() {
        val r = handle("frobnicate")
        assertEquals(200, r.statusCode)
        assertEquals(McpServerCore.ErrorCode.METHOD_NOT_FOUND, errorOf(r).getInt("code"))
        assertTrue(errorOf(r).getString("message").contains("frobnicate"))
    }

    @Test
    fun `unknown tool returns dispatcher error as isError result`() {
        val failing = object : McpServerCore.ToolDispatcher {
            override fun listTools(): JSONArray = JSONArray()
            override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult =
                McpServerCore.CallResult(
                    JSONArray().put(McpToolDispatcher.text("Unknown tool: $name")),
                    isError = true,
                )
        }
        val r = handle("tools/call", JSONObject().put("name", "nope"), dispatcher = failing)
        assertEquals(200, r.statusCode)
        assertTrue(resultOf(r).getBoolean("isError"))
        assertTrue(resultOf(r).getJSONArray("content").getJSONObject(0).getString("text").contains("nope"))
    }

    @Test
    fun `missing tool name maps to -32602`() {
        val r = handle("tools/call", JSONObject())
        assertEquals(McpServerCore.ErrorCode.INVALID_PARAMS, errorOf(r).getInt("code"))
    }

    @Test
    fun `missing params on tools call maps to -32602`() {
        val r = handle("tools/call", null)
        assertEquals(McpServerCore.ErrorCode.INVALID_PARAMS, errorOf(r).getInt("code"))
    }

    @Test
    fun `dispatcher error code becomes json rpc error envelope with data`() {
        val failing = object : McpServerCore.ToolDispatcher {
            override fun listTools(): JSONArray = JSONArray()
            override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult =
                McpServerCore.CallResult(
                    JSONArray().put(McpToolDispatcher.text("SecurityGate denied: nope")),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = "nope",
                )
        }
        val r = handle("tools/call", JSONObject().put("name", "shell_exec"), dispatcher = failing)
        assertEquals(McpServerCore.ErrorCode.GATE_DENIED, errorOf(r).getInt("code"))
        assertEquals("nope", errorOf(r).getString("data"))
    }

    // ─── notifications ────────────────────────────────────────────────────

    @Test
    fun `notifications and unknown notifications answer 202 with empty body`() {
        val r1 = handle("notifications/initialized", id = null)
        assertEquals(202, r1.statusCode)
        assertEquals("", r1.body)
        val r2 = handle("frobnicate", id = null)
        assertEquals(202, r2.statusCode)
        assertEquals("", r2.body)
    }

    // ─── auth ─────────────────────────────────────────────────────────────

    @Test
    fun `wrong bearer token maps to -32004 with http 401`() {
        val r = handle(
            "ping", headers = mapOf("authorization" to "Bearer wrong"), authToken = "sekrit",
        )
        assertEquals(401, r.statusCode)
        assertEquals(McpServerCore.ErrorCode.AUTH_REQUIRED, errorOf(r).getInt("code"))
    }

    @Test
    fun `missing auth header when token enabled maps to -32004`() {
        val r = handle("ping", authToken = "sekrit")
        assertEquals(401, r.statusCode)
        assertEquals(McpServerCore.ErrorCode.AUTH_REQUIRED, errorOf(r).getInt("code"))
    }

    @Test
    fun `correct bearer token passes and blank token disables auth`() {
        val ok = handle("ping", headers = mapOf("authorization" to "Bearer sekrit"), authToken = "sekrit")
        assertEquals(200, ok.statusCode)
        // blank token = auth off: headerless request still passes
        val off = handle("ping", authToken = "   ")
        assertEquals(200, off.statusCode)
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────

    @Test
    fun `parseHttpRequest extracts method path headers and body`() {
        val raw = "POST /mcp HTTP/1.1\r\nContent-Length: 15\r\n\r\n{\"method\":\"x\"}"
        val p = McpServerCore.parseHttpRequest(raw)
        assertEquals("POST", p!!.method)
        assertEquals("/mcp", p.path)
        assertEquals(15, p.contentLength)
        assertEquals("""{"method":"x"}""", p.body)
    }

    @Test
    fun `parseHttpRequest rejects garbage and truncates oversized body`() {
        assertNull(McpServerCore.parseHttpRequest("not a request line"))
        val p = McpServerCore.parseHttpRequest(
            "POST /mcp HTTP/1.1\r\nContent-Length: 3\r\n\r\n{\"a\":1}{\"b\":2}",
        )
        assertEquals(3, p!!.body.length)
        assertEquals("{\"a", p.body)
    }

    @Test
    fun `parseJsonRpc extracts method params and id`() {
        val r = McpServerCore.parseJsonRpc("""{"method":"ping","id":7,"params":{"a":1}}""")
        assertEquals("ping", r!!.method)
        assertEquals(7, r.id)
        assertEquals(1, r.params!!.getInt("a"))
        assertNull(McpServerCore.parseJsonRpc("not json"))
        assertNull(McpServerCore.parseJsonRpc("""{"id":1}""")) // no method
    }

    @Test
    fun `buildHttpResponse emits status content length and cors`() {
        val http = McpServerCore.buildHttpResponse(
            McpServerCore.Response(200, """{"jsonrpc":"2.0"}"""),
        )
        assertTrue(http.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(http.contains("Content-Type: application/json"))
        assertTrue(http.contains("Content-Length: 17"))
        assertTrue(http.contains("Access-Control-Allow-Origin: *"))
        assertTrue(http.endsWith("\r\n\r\n" + """{"jsonrpc":"2.0"}"""))
    }

    @Test
    fun `buildHttpResponse maps 405 to reason phrase and 202 stays bodyless`() {
        assertTrue(McpServerCore.buildHttpResponse(McpServerCore.Response(405, "x"))
            .startsWith("HTTP/1.1 405 Method Not Allowed"))
        val accepted = McpServerCore.buildHttpResponse(McpServerCore.Response(202, ""))
        assertTrue(accepted.startsWith("HTTP/1.1 202 Accepted"))
        assertFalse(accepted.contains("Content-Length"))
    }
}
