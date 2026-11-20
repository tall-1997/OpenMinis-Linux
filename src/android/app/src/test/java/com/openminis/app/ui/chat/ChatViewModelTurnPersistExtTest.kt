package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-retry-rebase] Pins for the pure turn-parts slice builder behind
 * [ChatViewModel.buildTurnParts] (ChatViewModelTurnPersistExt). The agent
 * loop itself is not JVM-testable, so the contract the compact-and-retry
 * rebase depends on is pinned HERE, on the pure slice function:
 * ChatViewModelAgentLoopExt.compactAndRetryForOverflow's seal+swap clears
 * allToolBlocks and rebases turnStartBlockIndex to 0 — a stale pre-clear
 * bound would slice from beyond the rewound list and the empty-slice guard
 * would silently DROP the whole retried turn at persist.
 */
class ChatViewModelTurnPersistExtTest {

    private fun text(id: String, content: String) =
        AssistantBlock(id = id, kind = "text", content = content)

    private fun toolUse(id: String, name: String) =
        AssistantBlock(id = id, kind = "tool_use", toolName = name)

    @Test
    fun `stale pre-clear bound beyond the rewound list yields an empty slice`() {
        // The regression shape: after the seal+swap the list was cleared but the
        // bound still held the pre-clear size — buildTurnParts returned nothing
        // and the retried turn was dropped at persist with no error anywhere.
        val blocks = listOf(text("t1", "retry body"), toolUse("u1", "file_read"))
        val parts = turnPartsFromBlocks(blocks, turnStartBlockIndex = 5, toolCallInputs = emptyMap())
        assertTrue("stale bound must not fabricate parts", parts.isEmpty())
    }

    @Test
    fun `rebased bound zero over the rewound list keeps the whole retried turn`() {
        // The contract the rebase restores: after the seal+swap the list holds
        // ONLY the retried attempt's blocks, so bound 0 carries all of them.
        val blocks = listOf(text("t1", "retry body"), toolUse("u1", "file_read"))
        val parts = turnPartsFromBlocks(
            blocks,
            turnStartBlockIndex = 0,
            toolCallInputs = mapOf("u1" to "{\"path\":\"/a\"}"),
        )
        assertEquals(2, parts.size)
        assertEquals("retry body", (parts[0] as AgentContentPart.Text).text)
        val use = parts[1] as AgentContentPart.ToolUse
        assertEquals("u1", use.id)
        assertEquals("file_read", use.name)
        assertEquals("/a", use.input.optString("path"))
    }

    @Test
    fun `mid-run bound slices only the current turn's blocks`() {
        // Normal multi-turn shape: earlier turns' blocks stay below the bound
        // (they were persisted by their own turns) and must not leak in again.
        val blocks = listOf(
            text("t0", "earlier turn"),
            toolUse("u0", "shell"),
            text("t1", "this turn"),
        )
        val parts = turnPartsFromBlocks(blocks, turnStartBlockIndex = 2, toolCallInputs = emptyMap())
        assertEquals(listOf("this turn"), parts.map { (it as AgentContentPart.Text).text })
    }

    @Test
    fun `thinking and info blocks and empty text are not persisted`() {
        val blocks = listOf(
            AssistantBlock(id = "th1", kind = "thinking", content = "secret chain"),
            AssistantBlock(id = "i1", kind = "info", content = "switched model"),
            text("t1", ""),
            text("t2", "visible"),
        )
        val parts = turnPartsFromBlocks(blocks, turnStartBlockIndex = 0, toolCallInputs = emptyMap())
        assertEquals(listOf("visible"), parts.map { (it as AgentContentPart.Text).text })
    }

    @Test
    fun `tool_use without a name is skipped, missing args default to empty object`() {
        val blocks = listOf(
            toolUse("u1", ""),
            toolUse("u2", "file_read"),
        )
        val parts = turnPartsFromBlocks(blocks, turnStartBlockIndex = 0, toolCallInputs = emptyMap())
        assertEquals(1, parts.size)
        val use = parts[0] as AgentContentPart.ToolUse
        assertEquals("u2", use.id)
        assertEquals(0, use.input.length())
    }

    @Test
    fun `repaired inputs form a detached snapshot and survive persistence`() {
        val rawDraft = """{"query":"partial"}"""
        val repaired = """{"query":"complete","more":{"key":"value"}}"""
        val block = toolUse("u1", "find").copy(toolArgs = rawDraft, thoughtSignature = "signature")
        val parts = turnPartsFromBlocks(listOf(block), 0, mapOf("u1" to repaired))
        val tool = parts.single() as AgentContentPart.ToolUse
        assertEquals(repaired, tool.inputJsonSnapshot)
        tool.input.getJSONObject("more").put("key", "changed")
        assertEquals("value", tool.input.getJSONObject("more").getString("key"))
        val row = JSONArray(buildAssistantPartsJson(parts, mapOf("u1" to block)))
        val stored = row.getJSONObject(0).getJSONObject("value")
        assertEquals(repaired, stored.getString("input"))
        val restored = readTranscriptToolUse(stored)
        assertEquals("complete", restored.input.getString("query"))
        assertEquals("signature", restored.thoughtSignature)
        assertEquals(restored.input.toString(), restored.inputJsonSnapshot)
    }

    @Test
    fun `only completed calls enter the pair even when the provider omits the start event`() {
        val calls = listOf(Triple("complete", "file_read", JSONObject().put("path", "before")))
        validateCompletedToolCalls(calls)
        val parts = completedToolTurnParts(
            renderedParts = listOf(AgentContentPart.Text("reply"), AgentContentPart.ToolUse("started", "file_read", JSONObject())),
            toolCalls = calls,
            inputs = mapOf("complete" to """{"path":"after"}"""),
            signatures = mapOf("complete" to "provider-signature"),
            text = "reply",
        )
        val use = parts.filterIsInstance<AgentContentPart.ToolUse>().single()
        assertEquals("complete", use.id)
        assertEquals("after", use.input.getString("path"))
        assertEquals("provider-signature", use.thoughtSignature)
        assertEquals("reply", (parts.first() as AgentContentPart.Text).text)
        val plan = ToolTurnPlan(emptyList(), emptyMap(), emptyMap())
        val results = assembleTurnResultParts(calls, plan, ToolTurnExecution(java.util.concurrent.ConcurrentHashMap()))
        val result = results.single() as AgentContentPart.ToolResult
        assertTrue(result.isError)
        assertTrue(result.content.contains("outcome is unknown"))
        assertNull(result.receiptProof)
        assertEquals("complete", JSONArray(completeToolTurnResultJson(parts, results)).getJSONObject(0)
            .getJSONObject("value").getString("toolUseId"))
        assertTrue(runCatching { completeToolTurnResultJson(parts, emptyList()) }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `conflicting provider identities are rejected before planning a tool turn`() {
        val call = Triple("same|first", "file_read", JSONObject())
        val different = Triple("same|second", "file_read", JSONObject())
        assertTrue(runCatching { validateCompletedToolCalls(listOf(call, different)) }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `invalid persisted tool arguments are not published as raw JSON`() {
        val restored = readTranscriptToolUse(JSONObject().put("input", "{bad").put("thoughtSignature", JSONObject.NULL))
        assertEquals("{}", restored.inputJsonSnapshot)
        assertNull(restored.thoughtSignature)
    }

    @Test
    fun `receipt and image path round trip without changing success and dedupe semantics`() {
        val proof = "a".repeat(64)
        val first = AgentContentPart.ToolResult(
            "u1", "write", "accepted but failed", isError = true,
            imageLinuxPath = "/tmp/path with spaces.png", receiptProof = proof,
        )
        val second = AgentContentPart.ToolResult("u2", "read", "ok")
        val duplicate = first.copy(content = "duplicate", isError = false, receiptProof = null)
        val partsJson = checkNotNull(buildToolResultPartsJson(listOf(first, second, duplicate)))
        val rows = JSONArray(partsJson)
        assertEquals(2, rows.length())
        val mapped = mapStoredLLMMessage(
            MessageEntity("result-row", "session", "user", partsJson, createdAt = 0L, sortOrder = 1),
            mediaFile = { error("Unexpected media reference: $it") },
        )
        assertEquals(listOf(first, second), mapped.contentParts)
        val failedRow = rows.getJSONObject(0).getJSONObject("value")
        assertFalse(failedRow.getBoolean("success"))
        assertEquals(first, readTranscriptToolResult(failedRow))
        val successRow = rows.getJSONObject(1).getJSONObject("value")
        assertTrue(successRow.getBoolean("success"))
        assertFalse(successRow.has("receiptProof"))
        assertFalse(successRow.has("imageLinuxPath"))
        assertEquals(second, readTranscriptToolResult(successRow))
    }

    @Test
    fun `legacy successful output never manufactures a host proof`() {
        val oldStyle = JSONObject().put("output", "ok")
        for (success in listOf(JSONObject.NULL, true, "true", "yes")) {
            val result = readTranscriptToolResult(oldStyle.put("success", success))
            assertFalse(result.isError)
            assertNull(result.receiptProof)
        }
        assertTrue(readTranscriptToolResult(oldStyle.put("success", "false")).isError)
        oldStyle.remove("success")
        assertFalse(readTranscriptToolResult(oldStyle).isError)
        assertNull(readTranscriptToolResult(oldStyle.put("receiptProof", true)).receiptProof)
    }

    @Test
    fun `receipt origin host marks the row and a real user turn stays an anchor`() {
        val reminder = "<system-reminder>\ncontinue where you left off\n</system-reminder>"
        val host = mapStoredLLMMessage(
            MessageEntity("n1", "session", "user",
                hostReminderPartsJson(JSONObject.quote(reminder)), createdAt = 0L, sortOrder = 1),
            mediaFile = { error("Unexpected media reference: $it") },
        )
        assertTrue(host.isHostGenerated)
        assertFalse(receiptUserRow(host))
        val real = mapStoredLLMMessage(
            MessageEntity("n2", "session", "user", """[{"type":"text","value":"run the tests"}]""",
                createdAt = 0L, sortOrder = 2),
            mediaFile = { error("Unexpected media reference: $it") },
        )
        assertFalse(real.isHostGenerated)
        assertTrue(receiptUserRow(real))
    }
}
