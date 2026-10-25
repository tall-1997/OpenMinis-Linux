package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchMultiEngineTest {
    @Test
    fun parseSearxJson() {
        val json = """
            {"results":[
              {"title":"Alpha","url":"https://a.example","content":"first"},
              {"title":"Beta","url":"https://b.example","content":"second"}
            ]}
        """.trimIndent()
        val results = WebSearchTool.parseSearxJson(json, max = 8)
        assertEquals(2, results.size)
        assertEquals("https://a.example", results[0].url)
        assertTrue(results[0].snippet.contains("first"))
    }

    @Test
    fun parseBingJson() {
        val json = """
            {"webPages":{"value":[
              {"name":"Gamma","url":"https://g.example","snippet":"bing hit"}
            ]}}
        """.trimIndent()
        val results = WebSearchTool.parseBingJson(json, max = 5)
        assertEquals(1, results.size)
        assertEquals("Gamma", results[0].title)
        assertEquals("https://g.example", results[0].url)
    }

    @Test
    fun engineFromIdDefaultsToDdg() {
        assertEquals(WebSearchSettings.Engine.SEARXNG, WebSearchSettings.Engine.fromId("searxng"))
        assertEquals(WebSearchSettings.Engine.DDG, WebSearchSettings.Engine.fromId("nope"))
        assertEquals(WebSearchSettings.Engine.CUSTOM, WebSearchSettings.Engine.fromId("custom"))
    }

    @Test
    fun expandCustomUrlReplacesQueryAndKey() {
        val url = WebSearchTool.expandCustomUrl(
            "https://api.example/search?q={query}&token={key}",
            "hello world",
            "ab c",
        )
        assertEquals("https://api.example/search?q=hello+world&token=ab+c", url)
    }

    @Test
    fun expandCustomUrlAppendsQueryWhenPlaceholderMissing() {
        val url = WebSearchTool.expandCustomUrl("https://api.example/search", "kotlin")
        assertEquals("https://api.example/search?q=kotlin", url)
    }

    @Test
    fun parseGenericSearchJsonItems() {
        val json = """
            {"items":[
              {"title":"Delta","link":"https://d.example","snippet":"cse hit"}
            ]}
        """.trimIndent()
        val results = WebSearchTool.parseGenericSearchJson(json, max = 5)
        assertEquals(1, results.size)
        assertEquals("Delta", results[0].title)
        assertEquals("https://d.example", results[0].url)
        assertTrue(results[0].snippet.contains("cse"))
    }
}
