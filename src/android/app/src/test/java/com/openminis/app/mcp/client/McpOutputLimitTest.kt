package com.openminis.app.mcp.client

import com.openminis.app.tools.SpillPlan
import com.openminis.app.tools.ToolOutputSpill
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the MCP output limit — port of pi `mcp/src/output-limit.ts`.
 * Pins: short output untouched, long output middle-cut with the token-estimate
 * warning and the full-output pointer, CJK/emoji boundary safety, image blocks
 * preserved, save-failure honesty, and the single-mechanism contract (the
 * bounded preview is recognized by ToolOutputSpill as already offloaded).
 */
class McpOutputLimitTest {

    private val guestPath = "/var/minis/workspace/tool-spill/mcp-srv-tool.txt"

    @Test
    fun `short output passes through untouched and never calls the saver`() {
        var saverCalled = false
        val out = McpOutputLimit.limit(
            serverName = "srv",
            toolName = "tool",
            combinedText = "hello world",
            saveOutput = { saverCalled = true; guestPath },
        )
        assertFalse(out.truncated)
        assertNull(out.fullOutputPath)
        assertEquals("hello world", out.text)
        assertFalse("saver must only run past the limit", saverCalled)
    }

    @Test
    fun `long output is middle cut with warning line and full-output pointer`() {
        val text = "A".repeat(30_000)
        val out = McpOutputLimit.limit("srv", "tool", text, saveOutput = { guestPath })
        assertTrue(out.truncated)
        assertEquals(guestPath, out.fullOutputPath)
        assertTrue("placeholder prefix for the shared spill gate", out.text.startsWith("[tool-output-spill]"))
        assertTrue(
            "intro names server and tool and the original byte size",
            out.text.startsWith("[tool-output-spill] mcp 'srv' tool 'tool' produced 30000 bytes."),
        )
        // Token estimate = ceil(bytes / 4) — pi output-limit.ts.
        assertTrue(out.text.contains("Warning: truncated output (original token count: 7500)"))
        assertTrue(out.text.contains("Total output lines: 1"))
        assertTrue(out.text.contains("…9520 chars truncated…"))
        assertTrue(out.text.contains("[Full output: $guestPath (read it with offset/limit)]"))
        // Head and tail of the original survive the cut.
        assertTrue(out.text.contains("A".repeat(256)))
        assertTrue("result must stay bounded", out.text.length < 30_000)
    }

    @Test
    fun `save failure is admitted instead of pointing at a missing file`() {
        val out = McpOutputLimit.limit(
            "srv", "tool", "B".repeat(30_000),
            saveOutput = { throw IllegalStateException("disk full") },
        )
        assertTrue(out.truncated)
        assertNull(out.fullOutputPath)
        assertTrue(out.text.startsWith("[tool-output-spill]"))
        assertTrue(out.text.contains("[Could not save the full output: disk full]"))
        assertFalse(out.text.contains("[Full output:"))
    }

    @Test
    fun `no saver wired still bounds the preview without a pointer`() {
        val out = McpOutputLimit.limit("srv", "tool", "C".repeat(30_000), saveOutput = null)
        assertTrue(out.truncated)
        assertNull(out.fullOutputPath)
        assertTrue(out.text.startsWith("[tool-output-spill]"))
        assertTrue(out.text.contains("could not be saved"))
        assertFalse(out.text.contains("[Full output:"))
        assertTrue(out.text.contains("…"))
    }

    @Test
    fun `token estimate is byte based for CJK output`() {
        val text = "\u4F60".repeat(10_000) // 30000 UTF-8 bytes
        val out = McpOutputLimit.limit("srv", "tool", text, saveOutput = { guestPath })
        assertTrue(out.text.contains("Warning: truncated output (original token count: 7500)"))
        // 3413 CJK chars per half — exact boundary-safe result from the truncator.
        assertTrue(out.text.contains("\u4F60".repeat(3413) + "…3174 chars truncated…" + "\u4F60".repeat(3413)))
    }

    @Test
    fun `emoji output keeps surrogate pairs intact`() {
        val text = "\uD83D\uDE00".repeat(6_000) // 24000 bytes
        val out = McpOutputLimit.limit("srv", "tool", text, saveOutput = { guestPath })
        assertTrue(out.truncated)
        assertTrue(out.text.contains("\uD83D\uDE00".repeat(2560) + "…880 chars truncated…" + "\uD83D\uDE00".repeat(2560)))
        assertFalse(out.text.contains("\uFFFD"))
    }

    @Test
    fun `preserved blocks follow the truncated text like pi image blocks`() {
        val placeholder = "[image image/png, 10 b64 chars — not inlined]"
        val out = McpOutputLimit.limit(
            "srv", "tool", "D".repeat(30_000),
            preservedBlocks = listOf(placeholder),
            saveOutput = { guestPath },
        )
        assertTrue(out.text.endsWith(placeholder))
    }

    // ── integration with McpJsonRpc.formatCallResult ─────────────────────────

    @Test
    fun `formatCallResult bounds big text and keeps the image placeholder outside the cut`() {
        val result = JSONObject()
            .put("isError", false)
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "A".repeat(30_000)))
                    .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "0123456789")),
            )
        val outcome = McpJsonRpc.formatCallResult(
            result,
            McpJsonRpc.McpResultContext("srv", "tool") { guestPath },
        )
        assertFalse(outcome.isError)
        assertTrue(outcome.text.startsWith("[tool-output-spill] mcp 'srv' tool 'tool'"))
        assertTrue(outcome.text.contains("…9520 chars truncated…"))
        assertTrue(outcome.text.endsWith("[image image/png, 10 b64 chars — not inlined]"))
    }

    @Test
    fun `formatCallResult also bounds the structuredContent fallback`() {
        val result = JSONObject()
            .put(
                "structuredContent",
                JSONObject().put("blob", "S".repeat(30_000)),
            )
        val outcome = McpJsonRpc.formatCallResult(
            result,
            McpJsonRpc.McpResultContext("srv", "tool") { guestPath },
        )
        assertTrue(outcome.text.startsWith("[tool-output-spill]"))
        assertTrue(outcome.text.contains("chars truncated"))
    }

    @Test
    fun `legacy formatCallResult without context is unchanged`() {
        val result = JSONObject()
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "plain"))
                    .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "0123456789")),
            )
        val outcome = McpJsonRpc.formatCallResult(result)
        assertEquals("plain\n[image image/png, 10 b64 chars — not inlined]", outcome.text)
    }

    // ── single-mechanism contract with ToolOutputSpill ────────────────────────

    @Test
    fun `a bounded MCP preview is recognized as already offloaded downstream`() {
        val out = McpOutputLimit.limit("srv", "tool", "E".repeat(30_000), saveOutput = { guestPath })
        // The downstream ToolOutputSpill pass must NOT re-spill this preview:
        // the MCP layer is the single writer of the spill file.
        assertEquals(SpillPlan.PASSTHROUGH, ToolOutputSpill.planFor(out.text, hasSession = true, sourcePath = null))
        // And the UI link resolver finds the guest path inside the preview.
        assertEquals(guestPath, ToolOutputSpill.parseGuestPath(out.text))
    }

    @Test
    fun `a save-failed preview is still recognized as offloaded`() {
        val out = McpOutputLimit.limit(
            "srv", "tool", "F".repeat(30_000),
            saveOutput = { throw java.io.IOException("no space") },
        )
        assertEquals(SpillPlan.PASSTHROUGH, ToolOutputSpill.planFor(out.text, hasSession = true, sourcePath = null))
    }
}
