package com.openminis.app.mcp.client

import com.openminis.app.tools.SpillPlan
import com.openminis.app.tools.ToolOutputSpill
import com.openminis.app.tools.ToolOutputTruncator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOutputLimitTest {
    private val guestPath = "/var/minis/workspace/tool-spill/mcp-srv-tool.txt"
    private val half = ToolOutputSpill.PREVIEW_BODY_CHARS / 2

    @Test
    fun `text at the shared character threshold passes through without writing`() {
        val text = "a".repeat(ToolOutputSpill.LIMIT)
        val out = McpOutputLimit.limit("srv", "tool", text, saveOutput = { error("saver ran early") })
        assertFalse(out.truncated)
        assertNull(out.fullOutputPath)
        assertEquals(text, out.text)
    }

    @Test
    fun `above the shared threshold and below the byte ceiling saves the entire text and shows a short preview`() {
        val text = "A".repeat(half) + "B".repeat(ToolOutputSpill.LIMIT + 1 - 2 * half) + "C".repeat(half)
        var saved: String? = null
        val out = McpOutputLimit.limit("srv", "tool", text, saveOutput = { saved = it; guestPath })
        assertEquals(text, saved)
        assertTrue(out.truncated)
        assertEquals(guestPath, out.fullOutputPath)
        assertTrue(out.text.startsWith("[tool-output-spill] mcp 'srv' tool 'tool' produced 16001 chars."))
        assertTrue(out.text.contains("Warning: truncated output (original token count: 4001)"))
        assertTrue(out.text.contains("Total output lines: 1"))
        assertTrue(out.text.contains("…(13001 chars omitted)…"))
        assertTrue(ToolOutputSpill.previewBody(out.text).startsWith("A".repeat(half)))
        assertTrue(out.text.endsWith("C".repeat(half)))
        assertTrue(out.text.length < 3_500)
        assertEquals(SpillPlan.PASSTHROUGH, ToolOutputSpill.planFor(out.text, hasSession = true, sourcePath = null))
        assertEquals(guestPath, ToolOutputSpill.parseGuestPath(out.text))
    }

    @Test
    fun `failed absent or invalid save cannot advertise a path`() {
        val savers = listOf<((String) -> String?)?>(
            { throw IllegalStateException("disk full") },
            null,
            { "/var/minis/workspace/not-a-spill.txt" },
            { null },
        )
        for (save in savers) {
            val out = McpOutputLimit.limit("srv", "tool", "B".repeat(30_000), saveOutput = save)
            assertTrue(out.truncated)
            assertNull(out.fullOutputPath)
            assertNull(ToolOutputSpill.parseGuestPath(out.text))
            assertFalse(out.text.contains("minis://workspace"))
            assertTrue(out.text.contains("could not be saved"))
            assertTrue(out.text.length < 3_500)
        }
    }

    @Test
    fun `byte ceiling still spills CJK text below the shared character threshold`() {
        val text = "\u4F60".repeat(10_000)
        val out = McpOutputLimit.limit("srv", "tool", text, saveOutput = { guestPath })
        assertTrue(out.truncated)
        assertTrue(out.text.contains("Warning: truncated output (original token count: 7500)"))
        assertEquals("\u4F60".repeat(half) + "\n\n…(7000 chars omitted)…\n\n" + "\u4F60".repeat(half),
            ToolOutputSpill.previewBody(out.text))
        assertTrue(ToolOutputTruncator.utf8ByteLength(out.text) < McpOutputLimit.MCP_OUTPUT_MAX_BYTES)
    }

    @Test
    fun `emoji output never splits a pair at either preview boundary`() {
        val emoji = "\uD83D\uDE00"
        val text = "H".repeat(half - 1) + emoji + emoji.repeat(6_000) + "T".repeat(half - 1)
        val out = McpOutputLimit.limit("srv", "tool", text, saveOutput = { guestPath })
        assertTrue(out.truncated)
        assertEquals(out.text, out.text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        val body = ToolOutputSpill.previewBody(out.text)
        assertTrue(body.startsWith("H".repeat(half - 1) + "\n"))
        assertTrue(body.endsWith("\n" + "T".repeat(half - 1)))
    }

    @Test
    fun `formatCallResult bounds text and keeps error status and the whole image description`() {
        val text = "begin\n" + "A".repeat(30_000) + "\nerror detail"
        val media = "[image image/png, 10 b64 chars — not inlined]"
        val result = JSONObject()
            .put("isError", true)
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", text))
                    .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "0123456789")),
            )
        var saved: String? = null
        val outcome = McpJsonRpc.formatCallResult(result, McpJsonRpc.McpResultContext("srv", "tool") {
            saved = it
            guestPath
        })
        assertTrue(outcome.isError)
        assertTrue(outcome.text.startsWith("[tool-output-spill] mcp 'srv' tool 'tool'"))
        assertTrue(outcome.text.contains("error detail"))
        assertTrue(outcome.text.endsWith(media))
        assertEquals("$text\n$media", saved)
    }

    @Test
    fun `formatCallResult also bounds the structuredContent fallback`() {
        val result = JSONObject().put("structuredContent", JSONObject().put("blob", "S".repeat(30_000)))
        val outcome = McpJsonRpc.formatCallResult(result, McpJsonRpc.McpResultContext("srv", "tool") { guestPath })
        assertEquals(guestPath, ToolOutputSpill.parseGuestPath(outcome.text))
        assertTrue(outcome.text.contains("chars omitted"))
    }

    @Test
    fun `legacy formatting without context is unchanged`() {
        val result = JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "plain"))
            .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "0123456789")))
        assertEquals("plain\n[image image/png, 10 b64 chars — not inlined]", McpJsonRpc.formatCallResult(result).text)
    }

    @Test
    fun `too many media descriptions still produce one bounded envelope and are saved in full`() {
        val media = "[image image/png, 10 b64 chars — not inlined]"
        val descriptions = List(1_000) { media }
        var saved: String? = null
        val out = McpOutputLimit.limit("srv", "tool", "small", descriptions) { saved = it; guestPath }
        assertTrue(out.truncated)
        assertEquals("small\n" + descriptions.joinToString("\n"), saved)
        assertTrue(out.text.contains("\n$media\n"))
        assertTrue(out.text.contains("more MCP media descriptions omitted"))
        assertTrue(out.text.length < ToolOutputSpill.LIMIT)
    }
}
