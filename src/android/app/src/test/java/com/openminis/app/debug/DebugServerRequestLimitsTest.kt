package com.openminis.app.debug

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * [T-debugserver-request-limits] Request-size caps on the debug JSON-RPC
 * server. A declared gigabyte Content-Length and an endless header line must
 * both be refused with 413/431 BEFORE any large allocation, and normal
 * requests must still round trip. Real sockets on 127.0.0.1, mirroring
 * McpHttpServerTest's wire-level style.
 *
 * Robolectric supplies a real Context so the token lazy (filesDir) and
 * lanListen prefs resolve in a sandboxed dir instead of the test cwd.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DebugServerRequestLimitsTest {

    /** 取一个空闲端口（探测完立刻释放，竞态概率低且仅影响本测试重试）。 */
    private fun freePort(): Int =
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun newServerOnFreePort(): Pair<DebugServer, Int> {
        val port = freePort()
        return DebugServer(ApplicationProvider.getApplicationContext(), port) to port
    }

    /** start() 的 accept 循环是异步的：轮询直到端口可连。 */
    private fun awaitConnectable(port: Int) {
        repeat(50) {
            try {
                Socket("127.0.0.1", port).close()
                return
            } catch (_: java.io.IOException) {
                Thread.sleep(100)
            }
        }
        error("debug server never became connectable on port $port")
    }

    /** 原始字节往返，返回完整响应文本。 */
    private fun rawRoundTrip(port: Int, request: ByteArray): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            try {
                s.getOutputStream().apply {
                    write(request)
                    flush()
                }
            } catch (_: java.io.IOException) {
                // 限额命中后服务器先响应并关闭连接：客户端残余写入可能断管，
                // 响应仍应可读。
            }
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line).append('\n')
                }
            }
        }

    private fun get(port: Int, path: String): String {
        val request = "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.UTF_8)
        return rawRoundTrip(port, request)
    }

    // ─── 常量契约 ─────────────────────────────────────────────────────────

    @Test
    fun `body and header caps mirror McpHttpServer`() {
        // 同一套上限数值（4 MB / 64 KiB）：两个本机 server 的拒绝行为一致，
        // 不会出现"同一个 payload 在 McpHttpServer 被拒、在 DebugServer 通过"。
        assertEquals(com.openminis.app.mcp.server.McpHttpServer.MAX_BODY_BYTES, DebugServer.MAX_BODY_BYTES)
        assertEquals(com.openminis.app.mcp.server.McpHttpServer.MAX_HEADER_BYTES, DebugServer.MAX_HEADER_BYTES)
    }

    // ─── body 上限：413 ───────────────────────────────────────────────────

    @Test
    fun `oversized declared content length answers 413 without allocating it`() {
        val (server, port) = newServerOnFreePort()
        server.start()
        try {
            awaitConnectable(port)
            val request = (
                "POST / HTTP/1.1\r\n" +
                    "Content-Type: application/json\r\n" +
                    // 声明约 1 GB：修复前 ByteArray(contentLength) 直接 OOM。
                    "Content-Length: 999999999\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.UTF_8)
            val response = rawRoundTrip(port, request)
            assertTrue("expected 413 in [$response]", response.startsWith("HTTP/1.1 413"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `body just over the cap is refused before being read`() {
        val (server, port) = newServerOnFreePort()
        server.start()
        try {
            awaitConnectable(port)
            // 只声明不发送整个 body：413 必须在任何 body 字节被读取之前发出 ——
            // 这正是上限的语义（客户端不需要先灌满 4 MB 才能被拒）。
            val declared =
                "POST / HTTP/1.1\r\nContent-Length: ${DebugServer.MAX_BODY_BYTES + 1}\r\n\r\n"
            val refused = rawRoundTrip(port, declared.toByteArray(Charsets.UTF_8))
            assertTrue("expected 413 in [$refused]", refused.startsWith("HTTP/1.1 413"))
            // 上限之下正常进入 RPC（未知方法回 JSON-RPC 错误，HTTP 仍是 200）。
            val body = "0123456789abcdef"
            val normal =
                "POST / HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body"
            val ok = rawRoundTrip(port, normal.toByteArray(Charsets.UTF_8))
            assertTrue("expected 200 in [${ok.take(120)}…]", ok.startsWith("HTTP/1.1 200"))
        } finally {
            server.stop()
        }
    }

    // ─── header 上限：431 ─────────────────────────────────────────────────

    @Test
    fun `single oversized header line answers 431 mid-line`() {
        val (server, port) = newServerOnFreePort()
        server.start()
        try {
            awaitConnectable(port)
            // 一条 70 KB 的头：readLine 必须在预算命中处截断并 431，而不是把
            // 无界行缓冲到底再让调用方检查。
            val request = (
                "POST / HTTP/1.1\r\n" +
                    "X-Bloat: ${"a".repeat(70 * 1024)}\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.UTF_8)
            val response = rawRoundTrip(port, request)
            assertTrue("expected 431 in [${response.take(120)}…]", response.startsWith("HTTP/1.1 431"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `excessive header count answers 431`() {
        val (server, port) = newServerOnFreePort()
        server.start()
        try {
            awaitConnectable(port)
            val headers = (1..150).joinToString("") { "X-Pad-$it: v\r\n" }
            val request = "POST / HTTP/1.1\r\n$headers\r\n".toByteArray(Charsets.UTF_8)
            val response = rawRoundTrip(port, request)
            assertTrue("expected 431 in [${response.take(120)}…]", response.startsWith("HTTP/1.1 431"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `header cap counts header lines only - 100 pass and 101 refuse`() {
        val (server, port) = newServerOnFreePort()
        server.start()
        try {
            awaitConnectable(port)
            // 上限只数真正的 header 行：请求行和终止空行不是 header，不能
            // 侵占预算（与 nginx 的 100 条 header 语义一致）。修复前把这两
            // 行也计入，实际只放行 98 条。恰好 100 条 header 必须往返成功
            //（GET /schema 无需 body，避免 Content-Length 占用预算）。
            val headers100 = (1..100).joinToString("") { "X-Pad-$it: v\r\n" }
            val ok = rawRoundTrip(
                port,
                "GET /schema HTTP/1.1\r\n$headers100\r\n".toByteArray(Charsets.UTF_8),
            )
            assertTrue("expected 200 with 100 headers in [${ok.take(120)}…]", ok.startsWith("HTTP/1.1 200"))
            // 第 101 条 header 立即 431。
            val headers101 = (1..101).joinToString("") { "X-Pad-$it: v\r\n" }
            val refused = rawRoundTrip(
                port,
                "GET /schema HTTP/1.1\r\n$headers101\r\n".toByteArray(Charsets.UTF_8),
            )
            assertTrue("expected 431 with 101 headers in [${refused.take(120)}…]", refused.startsWith("HTTP/1.1 431"))
        } finally {
            server.stop()
        }
    }

    // ─── 正常请求不受影响 ─────────────────────────────────────────────────

    @Test
    fun `normal request still round trips within the caps`() {
        val (server, port) = newServerOnFreePort()
        server.start()
        try {
            awaitConnectable(port)
            val response = get(port, "/schema")
            assertTrue("expected 200 in [${response.take(120)}…]", response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("\"rpc\":\"jsonrpc-2.0\""))
        } finally {
            server.stop()
        }
    }
}
