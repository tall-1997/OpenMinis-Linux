package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.tools.ToolCallLane
import com.openminis.app.tools.ToolCallLanes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-parallel-tool-calls] The system prompt's parallel-call capability
 * declaration must match the REAL lane table.
 *
 * Two failure modes this pins:
 *  - prompt drift: the prompt names a tool as parallel-safe while
 *    [ToolCallLanes] classifies it BARRIER (the model batches calls the host
 *    then serializes, so the promised speed-up never materializes);
 *  - silent regression: the declaration is deleted, and the model falls back
 *    to one tool per turn (the 1289-turns × 1-call shape measured in the
 *    reported session).
 */
class ParallelToolsPromptTest {

    private val prompt = ProductionSources.read("ui/chat/ChatViewModelPromptExt.kt")

    private fun declaredParallelTools(): List<String> =
        Regex("""Read-only tools are parallel-safe: ([^\n.]+)""")
            .find(prompt)
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.map { it.trim().removeSuffix(".") }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun declaredBarriers(): List<String> =
        Regex("""serial barriers[^\n]*?: ([^\n.]+)""")
            .find(prompt)
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.map { it.trim().removeSuffix(".") }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    @Test
    fun `the prompt declares the parallel-call capability`() {
        assertTrue(
            "the prompt must tell the model independent calls can be batched",
            prompt.contains("Parallel tool calls (capability, not advice)"),
        )
        assertTrue(
            "the declaration must name recon tools concretely",
            declaredParallelTools().isNotEmpty(),
        )
    }

    @Test
    fun `every tool declared parallel-safe is CONCURRENT in the lane table`() {
        val declared = declaredParallelTools()
        assertTrue(declared.size >= 8)
        for (name in declared) {
            assertEquals(
                "prompt promises '$name' runs concurrently but the lane table says BARRIER",
                ToolCallLane.CONCURRENT,
                ToolCallLanes.laneFor(name),
            )
        }
    }

    @Test
    fun `every tool declared a barrier is BARRIER in the lane table`() {
        val declared = declaredBarriers()
        assertTrue("the declaration must name write/stateful barriers", declared.isNotEmpty())
        for (name in declared) {
            assertEquals(
                "prompt calls '$name' a serial barrier but the lane table says CONCURRENT",
                ToolCallLane.BARRIER,
                ToolCallLanes.laneFor(name),
            )
        }
    }
}
