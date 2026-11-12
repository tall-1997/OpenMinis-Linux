package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-process-tail-injection] Architecture guard: process blocks must ride the
 * SYSTEM PROMPT's dynamic tail, never the conversation history.
 *
 * Regression this pins (the reported "non-stop repeated injection"): the agent
 * loop used to call `withGoalAndPlanContext(...)` on every turn, which grafted
 * stage/lanes/jobs/plan-board/recall onto the LAST user message — inside the
 * loop that is the tool_result carrier, so the model read process narration as
 * tool output and every request re-injected the same blocks. Source-grep guards
 * follow the repo's existing drift-test style (see ProductionSources).
 */
class ProcessInjectionArchitectureTest {

    @Test
    fun `the agent loop no longer grafts process blocks onto history`() {
        val loop = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        assertFalse(
            "process blocks must not be grafted onto the tool_result carrier",
            loop.contains("withGoalAndPlanContext("),
        )
        assertFalse(
            "goal context must ride the prompt tail only — a first-mount history row " +
                "would duplicate the objective (history row + tail) after turn 0",
            loop.contains("withGoalFirstMount("),
        )
        assertTrue(
            "the loop must build the request history from the history itself",
            loop.contains("effectiveAgentHistory().let { history ->"),
        )
        assertTrue(
            "the process tail must be injected through the system prompt",
            loop.contains("processInjectedSystemPrompt("),
        )
        assertTrue(
            "goal context must be passed to the tail on every turn",
            loop.contains("goalMount.prompt,"),
        )
    }

    @Test
    fun `the tail path carries the cache contract`() {
        val ext = ProductionSources.read("ui/chat/ChatViewModelPlanContextExt.kt")
        assertTrue("processInjectionTail must exist", ext.contains("internal fun ChatViewModel.processInjectionTail("))
        assertTrue(
            "the tail must be deduped content-wise",
            ext.contains("processTailCache"),
        )
        assertTrue(
            "the injected prompt must keep the stable-prefix contract documented",
            ext.contains("systemPromptStablePrefixLen"),
        )
    }

    @Test
    fun `goal context has a single injection path, not a history row plus a tail copy`() {
        val goal = ProductionSources.read("goal/GoalContextInjection.kt")
        assertFalse(
            "the removed first-mount helper must not come back",
            goal.contains("internal fun withGoalFirstMount("),
        )
        assertTrue(
            "the legacy history graft must be marked LEGACY",
            ProductionSources.read("ui/chat/ChatViewModelPlanContextExt.kt").contains("LEGACY"),
        )
    }
}
