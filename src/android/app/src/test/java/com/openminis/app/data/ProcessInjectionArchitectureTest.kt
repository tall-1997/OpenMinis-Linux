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
        assertTrue(
            "the loop must build the request history from history + first-mount only",
            loop.contains("withGoalFirstMount("),
        )
        assertTrue(
            "the process tail must be injected through the system prompt",
            loop.contains("processInjectedSystemPrompt("),
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
    fun `goal context has a single-request path, not a per-turn graft`() {
        val goal = ProductionSources.read("goal/GoalContextInjection.kt")
        assertTrue("first-mount helper must exist", goal.contains("internal fun withGoalFirstMount("))
        assertTrue(
            "the first-mount helper must carry the process-tail-injection rationale",
            goal.contains("T-process-tail-injection"),
        )
        val context = ProductionSources.read("ui/chat/ChatViewModelPlanContextExt.kt")
        assertTrue(
            "the legacy history graft must be marked LEGACY",
            context.contains("LEGACY"),
        )
    }
}
