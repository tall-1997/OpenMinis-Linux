package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.ToolCallLane
import com.openminis.app.tools.ToolCallLanes
import com.openminis.app.ui.chat.parallelToolCallsPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-parallel-tool-calls] The parallel-call declaration is built from the LIVE
 * tool schema, not a hardcoded list.
 *
 * Why: the announced-tool gate (`check_announced_tools.py`) rejects a prompt
 * that names a tool the core schema hides. A hardcoded list announced
 * `memory_get` with memory disabled, `web_search` with the feature off, and
 * long-tail tools before `find_tools` surfaced them. The declaration now
 * filters the request's own tool list through [ToolCallLanes], so it can only
 * name tools the model can actually call.
 */
class ParallelToolsPromptTest {

    private fun tool(name: String) = AgentToolDefinition(name = name, description = "", parameters = emptyMap())

    @Test
    fun `no declaration when fewer than two parallel-safe tools are present`() {
        assertNull(parallelToolCallsPrompt(emptyList()))
        assertNull(
            "a single read-only tool has nothing to batch with",
            parallelToolCallsPrompt(listOf(tool("file_read"), tool("shell_execute"))),
        )
    }

    @Test
    fun `the declaration lists only live parallel-safe tools`() {
        val prompt = parallelToolCallsPrompt(
            listOf(tool("file_read"), tool("grep"), tool("web_fetch"), tool("shell_execute")),
        )
        requireNotNull(prompt)
        assertTrue(prompt.contains("Parallel tool calls (capability, not advice)"))
        assertTrue("parallel-safe names must appear", prompt.contains("file_read"))
        assertTrue(prompt.contains("grep"))
        assertTrue(prompt.contains("web_fetch"))
        assertFalse("barriers must never be announced as parallel-safe", prompt.contains("shell_execute"))
    }

    @Test
    fun `every announced parallel tool is CONCURRENT in the lane table`() {
        val names = listOf("file_read", "grep", "glob", "list_dir", "web_fetch", "read_image")
        val prompt = requireNotNull(parallelToolCallsPrompt(names.map { tool(it) }))
        val declared = prompt.substringAfter("Parallel-safe this turn: ").substringBefore(".")
        for (name in declared.split(",").map { it.trim() }) {
            if (name.isEmpty()) continue
            assertTrue(
                "prompt promised '$name' runs concurrently but the lane table says BARRIER",
                ToolCallLanes.laneFor(name) == ToolCallLane.CONCURRENT,
            )
        }
    }

    @Test
    fun `the base prompt no longer hardcodes a tool-name list`() {
        val src = ProductionSources.read("ui/chat/ChatViewModelPromptExt.kt")
        assertFalse(
            "a hardcoded 'parallel-safe: <names>' line is what the announced-tool gate rejected",
            src.contains("Read-only tools are parallel-safe:"),
        )
        assertTrue("the declaration must be built from the live schema", src.contains("parallelToolCallsPrompt(agentTools)"))
    }
}
