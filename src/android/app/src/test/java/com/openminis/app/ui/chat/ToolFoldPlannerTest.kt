package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-port-fold-tools] Upstream's `ToolFoldPlanner`, ported and pinned.
 *
 * The rule decides which runs of consecutive tool calls collapse into one
 * "Ran N tools" row. It is the piece the local (reverted) block-level folding
 * used to guess at, so the boundaries below are the contract that keeps a
 * finished turn's cards and its reasoning reachable.
 */
class ToolFoldPlannerTest {

    private var nextId = 0

    private fun tool(name: String = "shell_execute", status: ToolBlockStatus = ToolBlockStatus.SUCCESS, ms: Long = 0) =
        AssistantBlock(
            id = "t${nextId++}",
            kind = "tool_use",
            toolName = name,
            toolStatus = status,
            durationMs = ms,
        )

    private fun text(value: String) = AssistantBlock(id = "x${nextId++}", kind = "text", content = value)

    private fun thinking(value: String = "pondering") =
        AssistantBlock(id = "k${nextId++}", kind = "thinking", content = value)

    private fun info() = AssistantBlock(id = "i${nextId++}", kind = "info")

    @Test
    fun `a run of two finished tools before a reply folds`() {
        val blocks = listOf(tool(), tool(), text("done"))
        val runs = ToolFoldPlanner.foldedRuns(blocks)
        assertEquals(1, runs.size)
        assertEquals(2, runs[0].memberIds.size)
    }

    @Test
    fun `a single tool does not fold`() {
        val blocks = listOf(tool(), text("done"))
        assertTrue(ToolFoldPlanner.foldedRuns(blocks).isEmpty())
    }

    @Test
    fun `an unfinished tool ends the run and nothing folds`() {
        val blocks = listOf(
            tool(),
            tool(status = ToolBlockStatus.RUNNING),
            text("still going"),
        )
        assertTrue(ToolFoldPlanner.foldedRuns(blocks).isEmpty())
    }

    @Test
    fun `thinking between calls folds along with the run`() {
        val blocks = listOf(tool(), thinking(), tool(), text("done"))
        val runs = ToolFoldPlanner.foldedRuns(blocks)
        assertEquals(1, runs.size)
        // Two tools counted, the thinking block carried inside the run.
        assertEquals(3, runs[0].memberIds.size)
    }

    @Test
    fun `an info block is a barrier`() {
        val first = tool()
        val second = tool()
        val afterBarrier = tool()
        val last = tool()
        val blocks = listOf(first, second, info(), afterBarrier, last, text("done"))
        val runs = ToolFoldPlanner.foldedRuns(blocks)
        // A run must END at non-empty text to fold, so the pair before the info
        // block stays open (visible); the pair after it folds.
        assertEquals(1, runs.size)
        assertEquals(afterBarrier.id, runs[0].key)
    }

    @Test
    fun `sub-agent and user-ask blocks are never folded away`() {
        val subAgent = tool(name = "dispatch_agents")
        val ask = tool(name = "ask_user_question")
        val blocks = listOf(tool(), subAgent, text("hi"), ask, text("done"))
        // Both exceptions are barriers, so neither neighbouring pair folds.
        assertTrue(ToolFoldPlanner.foldedRuns(blocks).isEmpty())
    }

    @Test
    fun `the fold carries the summed duration of its calls`() {
        val blocks = listOf(tool(ms = 1_000), tool(ms = 2_500), text("done"))
        val runs = ToolFoldPlanner.foldedRuns(blocks)
        assertEquals(3_500L, runs[0].durationMs)
    }

    @Test
    fun `the run key is the first tool id so it survives a reload`() {
        val first = tool()
        val blocks = listOf(thinking(), first, tool(), text("done"))
        val runs = ToolFoldPlanner.foldedRuns(blocks)
        assertEquals(first.id, runs[0].key)
    }
}
