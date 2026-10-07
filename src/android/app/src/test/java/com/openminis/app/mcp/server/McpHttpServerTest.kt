package com.openminis.app.mcp.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * McpHttpServer 的真 socket 往返测试：随机端口 start/stop 幂等、POST /mcp
 * JSON-RPC 往返、GET 405。只在 127.0.0.1 上自发自收。
 *
 * Android 依赖策略：isReturnDefaultValues=true → Log.* 为 no-op，
 * Context 同样只作构造占位（route 路径不解引用它）。
 */
class McpHttpServerTest {

    private class EchoDispatcher : McpServerCore.ToolDispatcher {
        override fun listTools(): JSONArray =
            JSONArray().put(JSONObject().put("name", "device_info"))

        override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult =
            McpServerCore.CallResult(
                JSONArray().put(McpToolDispatcher.text("echo:$name:${arguments.optString("command")}")),
            )
    }

    private fun newServer(
        token: String? = null,
        dispatcher: McpServerCore.ToolDispatcher = EchoDispatcher(),
    ): McpHttpServer =
        McpHttpServer(
            context = null as android.content.Context?,
            dispatcher = dispatcher,
            authToken = { token },
        )

    /** 取一个空闲端口（探测完立刻释放，竞态概率低且仅影响本测试重试）。 */
    private fun freePort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun startOnFreePort(server: McpHttpServer): Int {
        repeat(5) {
            val port = freePort()
            if (server.start(port)) return port
        }
        error("could not bind any free port after 5 tries")
    }

    /** 原始 HTTP 往返，返回完整响应文本。 */
    private fun rawRoundTrip(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                flush()
            }
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            buildString {
                // 简化读取：读到流结束（服务器 Connection: close）
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line).append('\n')
                }
            }
        }

    private fun post(port: Int, path: String, body: String, auth: String? = null): String {
        val headers = buildString {
            append("POST $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1:$port\r\n")
            append("Content-Type: application/json\r\n")
            if (auth != null) append("Authorization: Bearer $auth\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            append("\r\n")
        }
        return rawRoundTrip(port, headers + body)
    }

    // ─── 生命周期 ─────────────────────────────────────────────────────────

    @Test
    fun `start and stop on a random port is idempotent`() {
        val server = newServer()
        val port = startOnFreePort(server)
        try {
            assertTrue(server.handledRequestCount >= 0)
            // repeat start while running → still true, no crash
            assertTrue(server.start(port))
            // stop twice → no throw
            server.stop()
            server.stop()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop is safe even when never started`() {
        val server = newServer()
        server.stop()
        assertEquals(0L, server.handledRequestCount)
    }

    @Test
    fun `validPort bounds check`() {
        assertTrue(McpHttpServer.validPort(8765))
        assertFalse(McpHttpServer.validPort(1023))
        assertFalse(McpHttpServer.validPort(65536))
        assertFalse(McpHttpServer.validPort(0))
        assertFalse(McpHttpServer.validPort(-1))
        assertTrue(McpHttpServer.validPort(1024))
        assertTrue(McpHttpServer.validPort(65535))
    }

    // ─── POST /mcp 往返 ───────────────────────────────────────────────────

    @Test
    fun `post mcp initialize round trip returns handshake json`() {
        val server = newServer()
        val port = startOnFreePort(server)
        try {
            // 等 accept loop 就绪：直接重试短往返
            val body = """{"jsonrpc":"2.0","id":11,"method":"initialize","params":{}}"""
            var raw = ""
            run {
                repeat(20) {
                    raw = post(port, "/mcp", body)
                    if (raw.contains("protocolVersion")) return@run
                    Thread.sleep(50)
                }
            }
            assertTrue("no handshake in response: $raw", raw.contains("HTTP/1.1 200 OK"))
            // org.json does not preserve key order — locate the body by the
            // first '{' (headers never contain one).
            val jsonStart = raw.indexOf('{')
            assertTrue("jsonStart=$jsonStart raw=[$raw]", jsonStart > 0)
            val parsed = JSONObject(raw.substring(jsonStart))
            assertEquals(11, parsed.getInt("id"))
            assertEquals(
                McpServerCore.PROTOCOL_VERSION,
                parsed.getJSONObject("result").getString("protocolVersion"),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `post tools call round trip reaches the dispatcher`() {
        val server = newServer()
        val port = startOnFreePort(server)
        try {
            val body = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"shell_exec","arguments":{"command":"hi"}}}"""
            var raw = ""
            run {
                repeat(20) {
                    raw = post(port, "/mcp", body)
                    if (raw.contains("echo:")) return@run
                    Thread.sleep(50)
                }
            }
            assertTrue(raw.contains("echo:shell_exec:hi"))
            assertEquals(1L, server.handledRequestCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `post with wrong token gets 401 and never reaches tools`() {
        val server = newServer(token = "sekrit")
        val port = startOnFreePort(server)
        try {
            val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
            var raw = ""
            run {
                repeat(20) {
                    raw = post(port, "/mcp", body, auth = "wrong")
                    if (raw.contains("401")) return@run
                    Thread.sleep(50)
                }
            }
            assertTrue(raw.startsWith("HTTP/1.1 401"))
            assertTrue(raw.contains("-32004"))
            // 正确 token 则通过
            val ok = post(port, "/mcp", body, auth = "sekrit")
            assertTrue(ok.contains("HTTP/1.1 200 OK"))
            assertTrue(ok.contains("\"tools\""))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `post invalid json maps to parse error envelope`() {
        val server = newServer()
        val port = startOnFreePort(server)
        try {
            var raw = ""
            run {
                repeat(20) {
                    raw = post(port, "/mcp", "not-json{")
                    if (raw.contains("-32700")) return@run
                    Thread.sleep(50)
                }
            }
            assertTrue(raw.contains("-32700"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `message path is an accepted alias for mcp`() {
        val server = newServer()
        val port = startOnFreePort(server)
        try {
            val body = """{"jsonrpc":"2.0","id":5,"method":"ping"}"""
            var raw = ""
            run {
                repeat(20) {
                    raw = post(port, "/message", body)
                    if (raw.contains("\"result\"")) return@run
                    Thread.sleep(50)
                }
            }
            assertTrue(raw.contains("HTTP/1.1 200 OK"))
        } finally {
            server.stop()
        }
    }

    // ─── 非 POST ──────────────────────────────────────────────────────────

    @Test
    fun `get returns 405 with a hint body`() {
        val server = newServer()
        val port = startOnFreePort(server)
        try {
            var raw = ""
            run {
                repeat(20) {
                    raw = rawRoundTrip(port, "GET /mcp HTTP/1.1\r\nHost: x\r\n\r\n")
                    if (raw.contains("405")) return@run
                    Thread.sleep(50)
                }
            }
            assertTrue(raw.startsWith("HTTP/1.1 405"))
            assertTrue(raw.contains("POST /mcp"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `post to unknown path returns 404`() {
        val server = newServer()
        val port = startOnFreePort(server)
        try {
            var raw = ""
            run {
                repeat(20) {
                    raw = post(port, "/other", """{"method":"ping","id":1}""")
                    if (raw.contains("404")) return@run
                    Thread.sleep(50)
                }
            }
            assertTrue(raw.startsWith("HTTP/1.1 404"))
        } finally {
            server.stop()
        }
    }

    // ─── CORS / 构造响应（纯函数面） ──────────────────────────────────────

    @Test
    fun `cors preflight response carries the allow headers`() {
        val pre = McpServerCore.corsPreflightResponse()
        assertTrue(pre.startsWith("HTTP/1.1 204 No Content\r\n"))
        assertTrue(pre.contains("Access-Control-Allow-Origin: *"))
        assertTrue(pre.contains("Access-Control-Allow-Headers: Content-Type, Authorization"))
        // method not allowed 变体替换了 Allow 头
        val mna = McpServerCore.methodNotAllowedResponse()
        assertTrue(mna.contains("Allow: POST, OPTIONS"))
        assertTrue(mna.startsWith("HTTP/1.1 405 Method Not Allowed"))
    }
}
