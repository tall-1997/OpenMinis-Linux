package com.openminis.app.tools

import com.openminis.app.harness.effects.RetryPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-recovery-layer] 统一重试层验收：只重试瞬态失败、退避可被虚拟时间跳过、
 * 上限由 policy 决定、非瞬态一次都不重试。
 */
class ToolRetryTest {

    private fun ok() = ToolExecutionResult("done", true)

    private fun transient(code: ToolErrorCode = ToolErrorCode.NETWORK_ERROR) =
        ToolExecutionResult("boom", false, errorCode = code)

    @Test
    fun `transient failures retry until success`() = runTest {
        var attempts = 0
        val result = ToolRetry.run(RetryPolicy(enabled = true, maxRetries = 3, baseDelayMs = 1_000)) {
            attempts++
            if (attempts < 3) transient() else ok()
        }
        assertEquals(3, attempts)
        assertTrue(result.success)
    }

    @Test
    fun `retry cap is honoured`() = runTest {
        var attempts = 0
        val result = ToolRetry.run(RetryPolicy(enabled = true, maxRetries = 2, baseDelayMs = 10)) {
            attempts++
            transient()
        }
        assertEquals("maxAttempts = maxRetries + 1", 3, attempts)
        assertTrue(!result.success)
    }

    @Test
    fun `non transient errors are not retried`() = runTest {
        var attempts = 0
        ToolRetry.run {
            attempts++
            ToolExecutionResult("bad args", false, errorCode = ToolErrorCode.INVALID_ARGUMENTS)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `disabled policy means a single attempt`() = runTest {
        var attempts = 0
        ToolRetry.run(RetryPolicy(enabled = false, maxRetries = 5, baseDelayMs = 10)) {
            attempts++
            transient()
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `timeout counts as transient`() {
        assertTrue(ToolRetry.isTransient(transient(ToolErrorCode.TIMEOUT)))
        assertTrue(!ToolRetry.isTransient(ok()))
        assertTrue(!ToolRetry.isTransient(transient(ToolErrorCode.PERMISSION_DENIED)))
    }

    @Test
    fun `web fetch failure sites classify transient causes`() {
        // [T-retry-error-codes] P1-7：web_fetch 失败路径此前全部 errorCode=null，
        // ToolRetry.isTransient（只认 NETWORK_ERROR/TIMEOUT）从未触发。超时/IO
        // 必须被归为瞬态，参数错与执行失败不重试。
        assertEquals(
            ToolErrorCode.TIMEOUT,
            WebFetchTool.errorCodeFor(java.net.SocketTimeoutException("read timed out")),
        )
        assertEquals(
            ToolErrorCode.NETWORK_ERROR,
            WebFetchTool.errorCodeFor(java.io.IOException("connection reset")),
        )
        assertEquals(
            ToolErrorCode.INVALID_ARGUMENTS,
            WebFetchTool.errorCodeFor(IllegalArgumentException("bad url")),
        )
        assertEquals(
            ToolErrorCode.EXECUTION_FAILED,
            WebFetchTool.errorCodeFor(IllegalStateException("unexpected")),
        )
    }

    @Test
    fun `web fetch bad url result is not transient`() {
        // [T-retry-error-codes] 畸形 URL 被 SSRF guard 拒绝（无网络 I/O）→
        // PERMISSION_DENIED，弹回模型自查，统一重试层不重试。
        val result = WebFetchTool.execute("""{"url":"not a url"}""")
        assertEquals(ToolErrorCode.PERMISSION_DENIED, result.errorCode)
        assertTrue(!ToolRetry.isTransient(result))
    }
}
