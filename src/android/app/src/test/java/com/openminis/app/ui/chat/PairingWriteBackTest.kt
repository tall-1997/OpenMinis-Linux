package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.partsJsonHasUseId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-pairing-writeback] The repair must surface DB-row ids alongside the use
 * identity, so the write-back can persist a neutral result next to its use.
 * The persistence itself needs Room (see ChatRepositoryWriteBack); what is
 * testable on the JVM is that the repair's material is complete, correct, and
 * empty when nothing is interrupted.
 */
class PairingWriteBackTest {

    private fun assistantWithUse(id: String, dbId: String?, useId: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolUse(id = useId, name = "shell_execute", input = JSONObject()),
        ),
        dbMessageId = dbId,
    )

    @Test
    fun `repair surfaces db row ids for interrupted uses`() {
        val history = listOf(
            assistantWithUse("a1", "row-1", "call_1"),
            // The tail assistant+tool_use is EXEMPT ("legitimately awaiting
            // execution"); the use above it, mid-history with no result, is
            // the interrupted one this repair must surface.
            LLMMessage(role = LLMMessage.Role.USER, content = "done"),
        )
        val repair = repairToolPairing(history)
        assertEquals(1, repair.interruptedUses)
        assertEquals(
            listOf(
                InterruptedUseRef(assistantRowId = "row-1", toolUseId = "call_1", toolName = "shell_execute"),
            ),
            repair.interruptedUseRefs,
        )
    }

    @Test
    fun `repair without a db row id yields no writeback refs`() {
        // In-memory rows (dbMessageId=null) have nothing to write back to —
        // the refs stay empty while the counts still report the repair.
        val history = listOf(
            assistantWithUse("a1", null, "call_1"),
            LLMMessage(role = LLMMessage.Role.USER, content = "done"),
        )
        val repair = repairToolPairing(history)
        assertEquals(1, repair.interruptedUses)
        assertTrue(repair.interruptedUseRefs.isEmpty())
    }

    @Test
    fun `matched pairs are not written back`() {
        val use = assistantWithUse("a1", "row-1", "call_1")
        val result = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(id = "call_1", name = "shell_execute", content = "ok", isError = false),
            ),
            dbMessageId = "row-2",
        )
        val repair = repairToolPairing(listOf(use, result))
        assertEquals(0, repair.interruptedUses)
        assertTrue(repair.interruptedUseRefs.isEmpty())
    }

    @Test
    fun `exact token matching beats substring hits`() {
        // The scan matches "toolUseId":"<token>" (legacy "id" also accepted)
        // exactly: an id inside another row's input/output text is NOT a
        // persisted result (no false positive), and a needle that merely
        // prefixes a longer id does not hide it (no false negative).
        val json = """{"type":"toolResult","value":{"toolUseId":"call_123","name":"s","output":"ref call_9"}}"""
        assertTrue(partsJsonHasUseId(json, "call_123"))
        assertTrue(!partsJsonHasUseId(json, "call_12"))
        assertTrue(!partsJsonHasUseId(json, "call_1234"))
        assertTrue(!partsJsonHasUseId(json, "call_9"))
    }
}
