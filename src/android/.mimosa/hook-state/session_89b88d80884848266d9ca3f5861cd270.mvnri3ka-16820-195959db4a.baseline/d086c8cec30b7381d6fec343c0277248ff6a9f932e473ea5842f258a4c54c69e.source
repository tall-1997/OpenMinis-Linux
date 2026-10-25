package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
