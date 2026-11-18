package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#61/#83/#146/#119] Pins the argument-integrity contract:
 *  - a truncated-but-closable tail is repaired and tagged `truncation+…`;
 *  - a non-blank tail that no closure suffix fixes is reported as
 *    [ToolJsonRepair.INVALID_JSON_MARKER] instead of being treated as `{}`;
 *  - a genuinely absent tail is NOT an error (no-argument tools stay legal).
 */
class ToolJsonRepairTest {

    private fun param(desc: String) = AgentToolParam(type = "string", description = desc)

    private val fileWrite = AgentToolDefinition(
        name = "file_write",
        description = "Write a file",
        parameters = mapOf(
            "path" to param("File path"),
            "content" to param("File content"),
        ),
        required = listOf("path", "content"),
    )
    private val noArgs = AgentToolDefinition(
        name = "list_tools",
        description = "No required args",
        parameters = emptyMap(),
        required = emptyList(),
    )
    private val tools = listOf(fileWrite, noArgs)

    @Test
    fun `unterminated content string is auto-closed and tagged truncation`() {
        val raw = """{"path":"/var/minis/workspace/a.txt","content":"hello world"""
        val args = JSONObject()
        val repairs = ToolJsonRepair.repair("file_write", args, raw, tools)
        assertTrue("expected a truncation repair, got $repairs", repairs.any { it.startsWith("truncation+") })
        assertFalse(
            "a successful repair must not also report invalid-json",
            repairs.contains(ToolJsonRepair.INVALID_JSON_MARKER),
        )
        assertEquals("/var/minis/workspace/a.txt", args.optString("path"))
        assertTrue(args.optString("content").startsWith("hello world"))
    }

    @Test
    fun `garbage tail is reported as invalid json instead of an empty call`() {
        val args = JSONObject()
        val repairs = ToolJsonRepair.repair("file_write", args, "this is not json at all", tools)
        assertTrue(
            "unrepairable non-blank tail must be marked, got $repairs",
            repairs.contains(ToolJsonRepair.INVALID_JSON_MARKER),
        )
        assertEquals(0, args.length())
    }

    @Test
    fun `absent tail is not an error for a no-argument tool`() {
        val args = JSONObject()
        assertTrue(ToolJsonRepair.repair("list_tools", args, null, tools).isEmpty())
        assertTrue(ToolJsonRepair.repair("list_tools", args, "", tools).isEmpty())
    }

    @Test
    fun `unknown tool is left untouched`() {
        val args = JSONObject()
        assertTrue(ToolJsonRepair.repair("nope", args, "garbage", tools).isEmpty())
    }

    // ── [T-preflight-field-alias] model-habit field names ─────────────────

    @Test
    fun `file_path alias is renamed onto the required path field`() {
        // The Claude Code habit: a complete, well-formed payload that the
        // required-field check would otherwise reject as an "invalid tool call".
        val args = JSONObject("""{"file_path":"/tmp/a.txt","content":"x"}""")
        val repairs = ToolJsonRepair.repair("file_write", args, null, tools)
        assertTrue("expected an alias rename, got $repairs", repairs.contains("alias:file_path->path"))
        assertEquals("/tmp/a.txt", args.optString("path"))
        assertFalse("the variant key must be consumed, not duplicated", args.has("file_path"))
        assertEquals("x", args.optString("content"))
    }

    @Test
    fun `alias rename never steals a sibling that is a schema field`() {
        // A tool whose schema really carries both `path` and `file_path` keeps
        // its own vocabulary — the executor may read the variant directly.
        val dual = AgentToolDefinition(
            name = "dual_tool",
            description = "Both fields",
            parameters = mapOf(
                "path" to param("Canonical path"),
                "file_path" to param("Alternate path"),
            ),
            required = listOf("path"),
        )
        val args = JSONObject("""{"file_path":"/tmp/b"}""")
        val repairs = ToolJsonRepair.repair("dual_tool", args, null, listOf(dual))
        assertTrue("schema-field siblings must not be renamed: $repairs", repairs.isEmpty())
        assertEquals("/tmp/b", args.optString("file_path"))
        assertFalse(args.has("path"))
    }

    @Test
    fun `unrelated sibling keys are not renamed`() {
        val args = JSONObject("""{"file":"/tmp/c"}""")
        val repairs = ToolJsonRepair.repair("file_write", args, null, tools)
        assertTrue("`file` is not an enumerated alias: $repairs", repairs.isEmpty())
    }
}
