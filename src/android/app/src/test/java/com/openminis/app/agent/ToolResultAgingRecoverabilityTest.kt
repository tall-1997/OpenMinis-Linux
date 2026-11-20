package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.tools.ToolOutputSpill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultAgingRecoverabilityTest {
    private val path = "/var/minis/workspace/tool-spill/tool-1.txt"
    private val full = "start\n" + "x".repeat(20_000) + "\nfinal-status"

    private fun result(content: String, isError: Boolean = false, name: String = "shell_execute") =
        AgentContentPart.ToolResult(id = "call-1", name = name, content = content, isError = isError)

    private fun age(part: AgentContentPart.ToolResult, verifiedPaths: Set<String> = emptySet()): ToolResultAging.Plan =
        ToolResultAging.age(
            listOf(LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = listOf(part))),
            keepRecent = 0,
            verifiedSpillPaths = verifiedPaths,
        )

    private fun rendered(plan: ToolResultAging.Plan): String =
        (plan.messages.single().contentParts.single() as AgentContentPart.ToolResult).content

    @Test
    fun `errors and medium unbacked results remain verbatim`() {
        val error = result(full, isError = true)
        val medium = result("x".repeat(3_000))
        for (part in listOf(error, medium)) {
            val plan = age(part)
            assertEquals(0, plan.agedCount)
            assertSame(part, plan.messages.single().contentParts.single())
        }
    }

    @Test
    fun `a declared spill is linked only when verified by the caller`() {
        val preview = ToolOutputSpill.formatPreview("shell_execute", path, full)
        val part = result(preview)
        assertEquals(path, ToolResultProjection.spillPathForRecovery(preview))
        val linked = age(part, setOf(path))
        assertTrue(linked.savedChars > 0)
        assertTrue(rendered(linked).contains("full output: $path"))
        assertTrue(rendered(linked).contains("final-status"))
        val missing = rendered(age(part))
        assertTrue(missing.startsWith(ToolResultAging.MARKER))
        assertTrue(missing.contains("no verified recovery path"))
        assertFalse(missing.contains(path))
        assertFalse(missing.contains("re-run"))
        assertEquals(preview, part.content)
    }

    @Test
    fun `bare paths and readback envelopes do not claim to hold the entire result`() {
        val arbitrary = "full output: $path\n$full"
        val readback = ToolOutputSpill.alreadySpilledPreview("file_read", path, full)
        assertNull(ToolResultProjection.spillPathForRecovery(arbitrary))
        assertNull(ToolResultProjection.spillPathForRecovery(readback))
        for (content in listOf(arbitrary, readback)) {
            val projected = rendered(age(result(content), setOf(path)))
            assertTrue(projected.contains("no verified recovery path"))
            assertFalse(projected.contains("page with file_read"))
        }
    }

    @Test
    fun `whole MCP image descriptions and surrounding text remain verbatim`() {
        val media = result("$full\n[image image/png, 34 b64 chars — not inlined]", name = "mcp")
        assertSame(media, age(media).messages.single().contentParts.single())
    }
}
