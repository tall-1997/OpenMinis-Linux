package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#61/#83/#146][GH#119] The shared preflight layer must behave identically
 * for every execution entry point. These tests pin the contract the three call
 * sites (main loop / SubAgentRunner / group-chat speakModel) now share:
 *  - unparseable argument stream → Rejected, model hears the byte count;
 *  - repairable truncation on a read tool → Ready + truncation tag;
 *  - repairable truncation on ANY write tool (and its aliases) → Rejected;
 *  - length-truncated turn → Rejected for every call;
 *  - valid args → Ready with the repaired serialization.
 */
class ToolCallPreflightTest {

    private fun param(desc: String) = AgentToolParam(type = "string", description = desc)

    private val fileRead = AgentToolDefinition(
        name = "file_read",
        description = "Read a file",
        parameters = mapOf("path" to param("File path")),
        required = listOf("path"),
    )

    private val multiEdit = AgentToolDefinition(
        name = "multi_edit",
        description = "Apply several edits to one file",
        parameters = mapOf(
            "path" to param("File path"),
            "edits" to AgentToolParam("array", "Edits"),
        ),
        required = listOf("path", "edits"),
    )

    private val memoryWrite = AgentToolDefinition(
        name = "memory_write",
        description = "Persist memory",
        parameters = mapOf("content" to param("Memory body")),
        required = listOf("content"),
    )

    /** Tool with NO required fields — the dangerous case from #61: `{}` runs. */
    private val noArgsTool = AgentToolDefinition(
        name = "no_args_tool",
        description = "Runs even with an empty object",
        parameters = emptyMap(),
        required = emptyList(),
    )

    private val tools = listOf(fileRead, multiEdit, memoryWrite, noArgsTool)

    private fun finalize(
        name: String,
        json: String,
        rawTail: String? = null,
        finishReason: String? = null,
    ) = ToolCallPreflight.finalize(
        toolName = name,
        args = JSONObject(json),
        rawArgumentTail = rawTail,
        advertisedTools = tools,
        turnFinishReason = finishReason,
    )

    @Test
    fun `unparseable argument stream is rejected with the byte count`() {
        val rawTail = "this-was-never-json-and-no-suffix-closes-it"
        val outcome = finalize("no_args_tool", "{}", rawTail)
        assertTrue("must reject, got $outcome", outcome is ToolCallPreflight.Outcome.Rejected)
        outcome as ToolCallPreflight.Outcome.Rejected
        assertEquals(rawTail.toByteArray(Charsets.UTF_8).size, outcome.rawBytes)
        assertTrue(outcome.modelMessage.contains("${outcome.rawBytes} bytes received"))
        assertTrue(outcome.modelMessage.contains("was NOT executed"))
        assertTrue(outcome.modelMessage.contains("argument stream"))
        assertTrue(outcome.uiMessage.contains("incomplete or invalid"))
    }

    @Test
    fun `repairable truncation on a read tool is executed with a tag`() {
        val outcome = finalize("file_read", "{}", """{"path":"/tmp/a.txt""")
        assertTrue("must be ready, got $outcome", outcome is ToolCallPreflight.Outcome.Ready)
        outcome as ToolCallPreflight.Outcome.Ready
        assertTrue(outcome.truncationRepairTag?.startsWith("truncation+") == true)
        assertTrue(outcome.argsStr.contains("/tmp/a.txt"))
    }

    @Test
    fun `repairable truncation on multi_edit is rejected`() {
        // The old main-loop check only covered file_write/file_edit; multi_edit
        // would auto-close and write a partial edit list.
        val outcome = finalize("multi_edit", "{}", """{"path":"/tmp/a.txt""")
        assertTrue("must reject, got $outcome", outcome is ToolCallPreflight.Outcome.Rejected)
        outcome as ToolCallPreflight.Outcome.Rejected
        assertTrue(outcome.modelMessage.contains("truncated in transit"))
        assertTrue(outcome.modelMessage.contains("/tmp/a.txt"))
        assertTrue(outcome.uiMessage.contains("truncated"))
    }

    @Test
    fun `truncated write aliases are rejected via canonical name`() {
        // save_memory → memory_write; the repair must find the canonical schema
        // and the write-tool set must reject it, in sub-agents/group chat too.
        val outcome = finalize("save_memory", "{}", """{"content":"half a memory""")
        assertTrue("must reject, got $outcome", outcome is ToolCallPreflight.Outcome.Rejected)
        outcome as ToolCallPreflight.Outcome.Rejected
        assertTrue(outcome.modelMessage.contains("truncated in transit"))
    }

    @Test
    fun `length-truncated turn rejects every call even with valid args`() {
        val outcome = finalize("file_read", """{"path":"/tmp/a.txt"}""", finishReason = "length")
        assertTrue("must reject, got $outcome", outcome is ToolCallPreflight.Outcome.Rejected)
        outcome as ToolCallPreflight.Outcome.Rejected
        assertTrue(outcome.modelMessage.contains("finish_reason=length"))
        assertTrue(outcome.modelMessage.contains("truncated"))
    }

    @Test
    fun `max_tokens finish reason is treated as truncation`() {
        assertTrue(ToolCallPreflight.isTruncatedFinishReason("max_tokens"))
        assertTrue(ToolCallPreflight.isTruncatedFinishReason("length"))
        assertFalse(ToolCallPreflight.isTruncatedFinishReason("stop"))
        assertFalse(ToolCallPreflight.isTruncatedFinishReason(null))
    }

    @Test
    fun `valid args pass through ready and keep the repaired serialization`() {
        val outcome = finalize("file_read", """{"path":"/tmp/a.txt","extra":"x"}""")
        assertTrue("must be ready, got $outcome", outcome is ToolCallPreflight.Outcome.Ready)
        outcome as ToolCallPreflight.Outcome.Ready
        assertEquals(null, outcome.truncationRepairTag)
        assertTrue(outcome.argsStr.contains("/tmp/a.txt"))
    }
}
