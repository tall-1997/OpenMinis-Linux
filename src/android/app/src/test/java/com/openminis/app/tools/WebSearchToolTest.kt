package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class WebSearchToolTest {

    @Test
    fun parseDuckDuckGoResultCards() {
        val html = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs">Example Docs</a>
              <a class="result__snippet">Official documentation for Example.</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://kotlinlang.org/docs/home.html">Kotlin docs</a>
              <a class="result__snippet">Language reference.</a>
            </div>
        """.trimIndent()
        val results = WebSearchTool.parseHtml(html, max = 8)
        assertEquals(2, results.size)
        assertEquals("https://example.com/docs", results[0].url)
        assertEquals("Example Docs", results[0].title)
        assertTrue(results[0].snippet.contains("Official documentation"))
        assertEquals("https://kotlinlang.org/docs/home.html", results[1].url)
    }

    @Test
    fun decodeDuckLinkUnwrapsUddg() {
        val raw = "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2F"
        assertEquals("https://developer.android.com/", WebSearchTool.decodeDuckLink(raw))
    }

    @Test
    fun schemaIsRegistered() {
        assertEquals("web_search", WebSearchTool.definition().name)
        assertTrue(WebSearchTool.definition().required.contains("query"))
    }

    @Test
    fun emptyQueryReportsInvalidArguments() {
        // [T-retry-error-codes] P1-7：失败路径补机器可读错误码——参数错不是
        // 瞬态，统一重试层不重试，弹回模型自我纠正。（无 context、无网络 I/O）
        val result = WebSearchTool.execute("""{"tool_title":"x"}""", context = null)
        assertTrue(!result.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode)
    }

    @Test
    fun transientEngineCausesClassifyAsRetryable() {
        // [T-retry-error-codes] P1-7：引擎级超时/IO 上报瞬态码，配置缺失不标——
        // 「引擎没配 key」不该被退避重试一万次。
        assertEquals(ToolErrorCode.TIMEOUT, WebSearchTool.transientErrorCode(SocketTimeoutException("t")))
        assertEquals(ToolErrorCode.NETWORK_ERROR, WebSearchTool.transientErrorCode(IOException("io")))
        assertEquals(null, WebSearchTool.transientErrorCode(IllegalStateException("no results")))
        assertEquals(ToolErrorCode.TIMEOUT, WebSearchTool.errorCodeFor(SocketTimeoutException("t")))
        assertEquals(ToolErrorCode.NETWORK_ERROR, WebSearchTool.errorCodeFor(IOException("io")))
        assertEquals(ToolErrorCode.EXECUTION_FAILED, WebSearchTool.errorCodeFor(IllegalStateException("weird")))
    }
}
