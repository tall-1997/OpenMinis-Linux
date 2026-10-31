package com.openminis.app.mcp.client

import com.openminis.app.provider.ssePayloads
import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [P2-convergence] McpJsonRpc.parseSseEvents moved onto the provider-side
 * SseEventParser assembly; these tests pin the semantics that had to survive
 * the merge — `data:` lines joined with '\n' inside one event, `:` comments
 * skipped, EOF flush without a trailing blank line — plus the requirement
 * that MCP and the streaming providers agree on the SAME body.
 */
class McpJsonRpcSseTest {

    private fun payloadsOf(body: String): List<String> =
        ssePayloads(BufferedReader(StringReader(body))).toList()

    @Test
    fun multiLineDataIsJoinedWithNewline() {
        val body = "data: {\"jsonrpc\":\"2.0\",\ndata: \"id\":1}\n\n"
        val events = McpJsonRpc.parseSseEvents(body)
        assertEquals(1, events.size)
        assertEquals("{\"jsonrpc\":\"2.0\",\n\"id\":1}", payloadsOf(body).single())
    }

    @Test
    fun commentsKeepalivesAndUnknownFieldsCarryNoPayload() {
        val body = ": ping\nretry: 100\nevent: message\nid: 7\ndata: {\"ok\":true}\n\n"
        assertEquals(1, McpJsonRpc.parseSseEvents(body).size)
        assertEquals(listOf("{\"ok\":true}"), payloadsOf(body))
    }

    @Test
    fun streamEndingWithoutTrailingBlankLineStillFlushes() {
        // An event after the last blank line is flushed at EOF…
        assertEquals(2, McpJsonRpc.parseSseEvents("data: {\"a\":1}\n\ndata: {\"b\":2}").size)
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), payloadsOf("data: {\"a\":1}\n\ndata: {\"b\":2}"))
        // …and consecutive data: lines with NO blank line are ONE event whose
        // payloads join with '\n' (SSE spec; identical to the old MCP loop,
        // which joined the same lines and fed the same parser).
        assertEquals(1, McpJsonRpc.parseSseEvents("data: {\"a\":1}\ndata: {\"b\":2}").size)
        assertEquals(listOf("{\"a\":1}\n{\"b\":2}"), payloadsOf("data: {\"a\":1}\ndata: {\"b\":2}"))
        assertEquals(1, McpJsonRpc.parseSseEvents("data: {\"a\":1}").size)
    }

    @Test
    fun nonJsonDataIsSkippedWithoutDroppingTheRest() {
        val body = "data: [DONE]\n\ndata: {\"a\":1}\n\ndata: \n\n"
        val events = McpJsonRpc.parseSseEvents(body)
        assertEquals(1, events.size)
        assertEquals(1, events[0].optInt("a"))
    }

    @Test
    fun onlyOneLeadingSpaceIsStrippedAfterDataColon() {
        assertEquals(listOf(" two"), payloadsOf("data:  two\n\n"))
    }

    @Test
    fun crlfTerminatedBodiesParseTheSame() {
        val body = "data: {\"a\":1}\r\n\r\ndata: {\"b\":2}\r\n\r\n"
        val events = McpJsonRpc.parseSseEvents(body)
        assertEquals(listOf(1, 2), events.map { it.optInt("a", it.optInt("b")) })
    }
}
