package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-parallel-tool-calls] Registry contract for [ToolCallLanes].
 *
 * The lane partition is load-bearing: a CONCURRENT call overlaps its
 * siblings, so admitting a tool that mutates shared state would race.
 * Unknown names default to BARRIER (conservative), which means a typo in
 * the CONCURRENT set does not fail loudly — it silently demotes a read to
 * a barrier (harmless) or, worse, a renamed tool keeps its old hardcoded
 * string in the set while the real tool falls to the default (also
 * harmless). The dangerous direction is the opposite: a name that is NOT
 * a real tool staying in the set, or a write tool being listed. These
 * tests pin both directions mechanically.
 */
class ToolCallLanesTest {

    /** Every CONCURRENT entry must be a real tool the schema can emit. */
    @Test
    fun concurrentSetContainsOnlyRegisteredToolNames() {
        val registry = AgentTools.makeAgentTools(
            supportsImageInput = true,
            visionGroupConfigured = true,
            memoryEnabled = true,
            subAgentEnabled = true,
            codeGraphEnabled = true,
            mcpNativeEnabled = true,
            includeLongTail = true,
        ).map { it.name }.toSet()

        val concurrent = ToolCallLanes.concurrentToolsForTest()
        assertTrue("concurrent set must not be empty", concurrent.isNotEmpty())
        val dead = concurrent - registry
        assertTrue(
            "CONCURRENT_TOOLS names not in the tool registry (typo or removed tool?): $dead",
            dead.isEmpty(),
        )
    }

    /** Reads are concurrent; everything stateful stays a barrier. */
    @Test
    fun readsAreConcurrentAndWritesAreBarriers() {
        for (name in ToolCallLanes.concurrentToolsForTest()) {
            assertEquals("read tool $name must be CONCURRENT", ToolCallLane.CONCURRENT, ToolCallLanes.laneFor(name))
        }
        val barriers = listOf(
            "shell_execute", "shell_exec", "env_exec", "su_exec",
            "file_write", "file_edit", "multi_edit",
            "spawn_agent", "run_subagent", "check_agent", "dispatch_agents",
            "wolfpack_run", "agent_plan", "cronjob", "minis_scheduled",
            "android_notification", "android_alarm", "memory_write",
            "browser_use", "mcp", "update_goal", "evolution", "ask_user_question",
        )
        for (name in barriers) {
            assertEquals("stateful tool $name must be BARRIER", ToolCallLane.BARRIER, ToolCallLanes.laneFor(name))
        }
    }

    /** Unknown names never inherit concurrency — they get the barrier. */
    @Test
    fun unknownToolIsBarrier() {
        assertEquals(ToolCallLane.BARRIER, ToolCallLanes.laneFor("brand_new_tool"))
        assertEquals(ToolCallLane.BARRIER, ToolCallLanes.laneFor(""))
    }

    /** Local command-template tools materialize into shells. */
    @Test
    fun localToolIsBarrierEvenWhenNamedLikeARead() {
        assertEquals(ToolCallLane.BARRIER, ToolCallLanes.laneFor("file_read", isLocalTool = true))
    }

    /** The shell family chains even when aliased. */
    @Test
    fun shellFamilyIsBarrier() {
        for (name in listOf("shell_execute", "shell_exec", "env_exec", "su_exec", "execute_code")) {
            assertEquals("shell family $name must be BARRIER", ToolCallLane.BARRIER, ToolCallLanes.laneFor(name))
        }
    }
}
