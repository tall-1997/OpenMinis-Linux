package com.openminis.app.mcp.client

import com.openminis.app.mcp.client.BuiltinLoopbackResolver.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-loopback-wiring] 纯逻辑面：内置服务器 loopback 接线判定。
 * 覆盖：端口顺延重写、localhost 匹配、路径/查询保留、非 loopback /
 * 非配置端口 / 无端口 / https / IPv6 不动、未运行的快速失败语义。
 */
class BuiltinLoopbackResolverTest {

    private fun resolve(
        url: String,
        configured: Int = 8765,
        actual: Int? = 8766,
        running: Boolean = true,
    ) = BuiltinLoopbackResolver.resolve(url, configured, actual, running)

    @Test
    fun `rewrites port when the server fell back`() {
        val w = resolve("http://127.0.0.1:8765/mcp")
        assertTrue("expected Wired, got $w", w is Outcome.Wired)
        assertEquals("http://127.0.0.1:8766/mcp", (w as Outcome.Wired).effectiveUrl)
    }

    @Test
    fun `keeps url when actual port equals configured`() {
        val w = resolve("http://127.0.0.1:8765/mcp", actual = 8765)
        assertEquals("http://127.0.0.1:8765/mcp", (w as Outcome.Wired).effectiveUrl)
    }

    @Test
    fun `localhost host matches and rewrites`() {
        val w = resolve("http://localhost:8765/mcp", actual = 8767)
        assertEquals("http://localhost:8767/mcp", (w as Outcome.Wired).effectiveUrl)
    }

    @Test
    fun `path and query survive the rewrite`() {
        val w = resolve("http://127.0.0.1:8765/mcp?x=1", actual = 8770)
        assertEquals("http://127.0.0.1:8770/mcp?x=1", (w as Outcome.Wired).effectiveUrl)
    }

    @Test
    fun `not running reports ServerNotRunning`() {
        assertTrue(resolve("http://127.0.0.1:8765/mcp", running = false) is Outcome.ServerNotRunning)
    }

    @Test
    fun `different port is not builtin`() {
        assertTrue(resolve("http://127.0.0.1:9999/mcp") is Outcome.NotBuiltin)
    }

    @Test
    fun `non loopback host is not builtin`() {
        assertTrue(resolve("http://example.com:8765/mcp") is Outcome.NotBuiltin)
    }

    @Test
    fun `https scheme is not builtin`() {
        assertTrue(resolve("https://127.0.0.1:8765/mcp") is Outcome.NotBuiltin)
    }

    @Test
    fun `url without a port is not builtin`() {
        assertTrue(resolve("http://127.0.0.1/mcp") is Outcome.NotBuiltin)
    }

    @Test
    fun `ipv6 loopback is not matched`() {
        assertTrue(resolve("http://[::1]:8765/mcp") is Outcome.NotBuiltin)
    }

    @Test
    fun `garbage url is not builtin`() {
        assertTrue(resolve("not a url at all") is Outcome.NotBuiltin)
    }

    @Test
    fun `actual port null while running keeps url`() {
        val w = resolve("http://127.0.0.1:8765/mcp", actual = null)
        assertEquals("http://127.0.0.1:8765/mcp", (w as Outcome.Wired).effectiveUrl)
    }
}
