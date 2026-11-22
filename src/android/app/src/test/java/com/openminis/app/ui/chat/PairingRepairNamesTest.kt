package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-pairing-repair-names] A repair must say WHICH results it dropped.
 *
 * The 2026-11-22 field log showed `droppedResults=15` once, which reads the
 * same whether a parallel batch was legitimately trimmed or a pairing bug ate
 * real results. Names make the two distinguishable without a repro.
 */
class PairingRepairNamesTest {

    private fun assistant(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList())

    private fun toolResult(id: String, name: String) =
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult(id = id, name = name, content = "ok")),
        )

    @Test
    fun aDroppedResultIsNamedInTheRepair() {
        // A result with no matching use is the orphan case the sweep drops.
        val history = listOf(
            assistant(AgentContentPart.ToolUse.fromInputJson(id = "call_1", name = "shell_exec", inputJson = "{}")),
            toolResult("call_1", "shell_exec"),
            toolResult("call_ORPHAN", "file_write"),
        )
        val repair = repairToolPairing(history)
        assertEquals(1, repair.droppedResults)
        // [T-pairing-repair-names] The id is DIGESTED, not echoed: it comes from
        // an external tool_call response and the journal contract is
        // digests-only. Tool name is kept (closed enum) so the count stays
        // diagnosable.
        assertEquals(1, repair.droppedNames.size)
        assertTrue(
            "name first, digest second: ${repair.droppedNames}",
            repair.droppedNames.single().startsWith("file_write/"),
        )
        assertFalse(
            "the raw external id must never reach the journal",
            repair.droppedNames.single().contains("call_ORPHAN"),
        )
    }

    @Test
    fun aCleanHistoryNamesNothing() {
        val history = listOf(
            assistant(AgentContentPart.ToolUse.fromInputJson(id = "call_1", name = "shell_exec", inputJson = "{}")),
            toolResult("call_1", "shell_exec"),
        )
        val repair = repairToolPairing(history)
        assertTrue(repair.droppedNames.isEmpty())
    }
}
