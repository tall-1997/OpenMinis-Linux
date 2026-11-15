package com.openminis.app.mcp.client

import com.openminis.app.mcp.client.McpJsonRpc.McpError
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-mcp-list-all] Contract tests for the pagination core — the port of pi
 * client.ts listAll (client.ts:357-376): pages join in order, the walk is
 * bounded at MAX_LIST_PAGES, a repeating cursor refuses, and the null/empty
 * cursor end-of-list quirk some servers have is tolerated.
 */
class McpListPagerTest {

    private fun page(vararg names: String, nextCursor: Any? = null): JSONObject {
        val items = JSONArray()
        for (n in names) items.put(JSONObject().put("name", n))
        val result = JSONObject().put("tools", items)
        when (nextCursor) {
            null -> Unit
            JSONObject.NULL -> result.put("nextCursor", JSONObject.NULL)
            else -> result.put("nextCursor", nextCursor)
        }
        return result
    }

    private fun names(arr: JSONArray): List<String> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }

    /**
     * McpError wraps every message in the repo's "[CODE] " prefix
     * (McpJsonRpc.kt McpError, CODE = the error code) — the pi-pinned text is
     * the suffix. Assert the code and the pi message separately.
     */
    private fun piMessage(e: McpError): String = e.message!!.removePrefix("[${e.code}] ")

    @Test
    fun `single page without a cursor is returned as-is`() {
        val arr = McpListPager.collect("tools/list", "tools") { page("a", "b") }
        assertEquals(listOf("a", "b"), names(arr))
    }

    @Test
    fun `pages join in cursor order until the cursor ends`() {
        val pages = listOf(
            page("a1", "a2", nextCursor = "c1"),
            page("b1", nextCursor = "c2"),
            page("c1"),
        )
        var index = 0
        val arr = McpListPager.collect("tools/list", "tools") { cursor ->
            assertEquals("fetch sees the previous nextCursor", if (index == 0) null else "c$index", cursor)
            pages[index++]
        }
        assertEquals(listOf("a1", "a2", "b1", "c1"), names(arr))
    }

    @Test
    fun `explicit null or empty cursor ends pagination (server quirk parity)`() {
        val arrNull = McpListPager.collect("tools/list", "tools") { page("x", nextCursor = JSONObject.NULL) }
        assertEquals(listOf("x"), names(arrNull))
        val arrEmpty = McpListPager.collect("tools/list", "tools") { page("x", nextCursor = "") }
        assertEquals(listOf("x"), names(arrEmpty))
    }

    @Test
    fun `duplicate cursor refuses with the pi message`() {
        // Two pages that both hand back cursor "loop" — a server bug that
        // would otherwise repeat the same page forever.
        val page = page("x", nextCursor = "loop")
        try {
            McpListPager.collect("tools/list", "tools") { page }
            fail("duplicate cursor must refuse")
        } catch (e: McpError) {
            assertEquals(McpError.PROTOCOL_ERROR, e.code)
            assertEquals("MCP tools/list returned duplicate cursor: loop", piMessage(e))
        }
    }

    @Test
    fun `walk is bounded at MAX_LIST_PAGES pages`() {
        var calls = 0
        try {
            McpListPager.collect("tools/list", "tools") { c ->
                calls++
                page("item$calls", nextCursor = "c$calls")
            }
            fail("unbounded pagination must refuse")
        } catch (e: McpError) {
            assertEquals(McpListPager.MAX_LIST_PAGES, calls)
            assertEquals(McpError.PROTOCOL_ERROR, e.code)
            assertEquals("MCP tools/list exceeded 1000 pages", piMessage(e))
        }
    }

    @Test
    fun `non string cursor is a protocol error`() {
        val bad = page("x", nextCursor = 5)
        try {
            McpListPager.collect("resources/list", "tools") { bad }
            fail("non-string cursor must refuse")
        } catch (e: McpError) {
            assertEquals(McpError.PROTOCOL_ERROR, e.code)
            assertEquals("Invalid MCP resources/list cursor", piMessage(e))
        }
    }

    @Test
    fun `missing items array is a protocol error`() {
        try {
            McpListPager.collect("prompts/list", "prompts") { JSONObject().put("other", 1) }
            fail("missing items must refuse")
        } catch (e: McpError) {
            assertEquals(McpError.PROTOCOL_ERROR, e.code)
            assertTrue(piMessage(e).startsWith("Invalid MCP prompts/list result"))
        }
    }
}
