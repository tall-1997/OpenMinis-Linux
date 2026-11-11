package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-plan-step-execution] Step-execution semantics on the board: the active
 * step flipping to done walks the board to the next pending step in the same
 * save, a FAILED step can be retried back to active by the user, and none of
 * this leaks into the plain update()/replace() contracts the older tests pin.
 */
class AgentPlanStepExecutionTest {

    private val sid = "step-exec-chat"

    @Before
    fun setUp() {
        AgentPlanStore.nowMs = { FIXED_NOW }
        AgentPlanStore.clear(sid, null)
    }

    @After
    fun tearDown() {
        AgentPlanStore.nowMs = { System.currentTimeMillis() }
        AgentPlanStore.clear(sid, null)
    }

    // ── auto-advance ──

    @Test
    fun activeStepDonePromotesTheNextPending() {
        val a = AgentPlanStore.add(sid, null, "recon", status = "active")
        AgentPlanStore.add(sid, null, "implement")
        AgentPlanStore.add(sid, null, "ship")
        val (updated, promoted) = AgentPlanStore.updateWithAdvance(sid, null, a.id, status = "done")!!
        assertEquals("done", updated.status)
        assertNotNull("the next pending step must auto-activate", promoted)
        assertEquals("implement", promoted!!.title)
        val board = AgentPlanStore.list(sid, null)
        assertEquals(listOf("done", "active", "pending"), board.map { it.status })
    }

    @Test
    fun pendingStepDoneDoesNotPromoteAnything() {
        // The plain-update contract the existing tests pin: marking a
        // NON-active item done must leave every other item alone.
        val a = AgentPlanStore.add(sid, null, "first")
        AgentPlanStore.add(sid, null, "second")
        val (_, promoted) = AgentPlanStore.updateWithAdvance(sid, null, a.id, status = "done")!!
        assertNull(promoted)
        assertEquals(
            listOf("done", "pending"),
            AgentPlanStore.list(sid, null).map { it.status },
        )
    }

    @Test
    fun failedStepNeverAdvancesTheBoard() {
        val a = AgentPlanStore.add(sid, null, "stuck", status = "active")
        AgentPlanStore.add(sid, null, "after")
        val (_, promoted) = AgentPlanStore.updateWithAdvance(sid, null, a.id, status = "failed")!!
        assertNull("a failure must not walk the board past itself", promoted)
        assertEquals(listOf("failed", "pending"), AgentPlanStore.list(sid, null).map { it.status })
    }

    @Test
    fun promotePicksTheLowestPositionWhenRowsWereReordered() {
        val a = AgentPlanStore.add(sid, null, "work", status = "active")
        val b = AgentPlanStore.add(sid, null, "later")
        val c = AgentPlanStore.add(sid, null, "sooner")
        // Push "sooner" to the front: it must win the promotion despite
        // sitting last in insertion order.
        AgentPlanStore.update(sid, null, c.id, position = 0)
        val (_, promoted) = AgentPlanStore.updateWithAdvance(sid, null, a.id, status = "done")!!
        assertEquals("sooner", promoted!!.title)
    }

    @Test
    fun advanceIsANoOpWithoutPendingRows() {
        val a = AgentPlanStore.add(sid, null, "only", status = "active")
        AgentPlanStore.add(sid, null, "other", status = "active")
        val (_, promoted) = AgentPlanStore.updateWithAdvance(sid, null, a.id, status = "done")!!
        assertNull(promoted)
    }

    @Test
    fun plainUpdateStillReturnsJustTheUpdatedRow() {
        val a = AgentPlanStore.add(sid, null, "recon", status = "active")
        AgentPlanStore.add(sid, null, "implement")
        val updated = AgentPlanStore.update(sid, null, a.id, status = "done")
        assertEquals("done", updated!!.status)
        // The advance still happened (shared save), the single-value API just
        // does not surface it.
        assertEquals("active", AgentPlanStore.list(sid, null)[1].status)
    }

    @Test
    fun replaceNeverAutoAdvances() {
        AgentPlanStore.replace(sid, null, listOf(
            AgentPlanStore.PlanInput(title = "one", status = "active"),
            AgentPlanStore.PlanInput(title = "two"),
        ))
        // Rebuilding the board with the first row done must leave the rest
        // exactly as written — replace is explicit, full-list, no cursor.
        val rebuilt = AgentPlanStore.replace(sid, null, listOf(
            AgentPlanStore.PlanInput(title = "one", status = "done"),
            AgentPlanStore.PlanInput(title = "two"),
        ))
        assertEquals(listOf("done", "pending"), rebuilt.map { it.status })
    }

    // ── retry ──

    @Test
    fun retryResetsAFailedStepToActive() {
        val a = AgentPlanStore.add(sid, null, "flaky", status = "failed")
        val retried = AgentPlanStore.retryStep(sid, null, a.id)
        assertNotNull(retried)
        assertEquals("active", retried!!.status)
        assertEquals("active", AgentPlanStore.list(sid, null).single().status)
    }

    @Test
    fun retryRefusesNonFailedSteps() {
        val pending = AgentPlanStore.add(sid, null, "pending")
        val active = AgentPlanStore.add(sid, null, "active", status = "active")
        val done = AgentPlanStore.add(sid, null, "done", status = "done")
        assertNull(AgentPlanStore.retryStep(sid, null, pending.id))
        assertNull(AgentPlanStore.retryStep(sid, null, active.id))
        assertNull(AgentPlanStore.retryStep(sid, null, done.id))
        assertEquals(
            listOf("pending", "active", "done"),
            AgentPlanStore.list(sid, null).map { it.status },
        )
    }

    @Test
    fun retryResolvesByNumberAndTitleLikeUpdate() {
        AgentPlanStore.add(sid, null, "alpha", status = "failed")
        AgentPlanStore.add(sid, null, "beta", status = "failed")
        assertNotNull(AgentPlanStore.retryStep(sid, null, "1"))
        assertNotNull(AgentPlanStore.retryStep(sid, null, "beta"))
        assertEquals(
            listOf("active", "active"),
            AgentPlanStore.list(sid, null).map { it.status },
        )
    }

    @Test
    fun retryRejectsUnknownRef() {
        AgentPlanStore.add(sid, null, "a", status = "failed")
        assertNull(AgentPlanStore.retryStep(sid, null, "nope"))
    }

    // ── receipt / prompt shapes ──

    @Test
    fun updateReceiptNamesTheAutoActivatedStep() {
        val a = AgentPlanStore.add(sid, null, "recon", status = "active")
        AgentPlanStore.add(sid, null, "implement")
        val result = AgentPlanTool.execute(
            """{"op":"update","id":"${a.id}","status":"done"}""",
            sid, null,
        )
        assertTrue(result.success)
        assertTrue(result.output, result.output.contains("Auto-activated next step"))
        assertTrue(result.output, result.output.contains("implement"))
        // Still compact: no board echo, no descriptions.
        assertFalse(result.output, result.output.contains("plans: "))
    }

    @Test
    fun plainUpdateReceiptHasNoAdvanceNote() {
        val a = AgentPlanStore.add(sid, null, "first")
        AgentPlanStore.add(sid, null, "second")
        val result = AgentPlanTool.execute(
            """{"op":"update","id":"${a.id}","status":"done"}""",
            sid, null,
        )
        assertTrue(result.success)
        assertFalse(result.output, result.output.contains("Auto-activated"))
    }

    @Test
    fun retryPromptLineNamesTheStepNumberAndTitle() {
        val line = com.openminis.app.ui.chat.PlanStepRetryRequest(
            planId = "id-1",
            title = "wire the retries",
            position = 2,
        ).promptLine()
        assertTrue(line, line.contains("step 3"))
        assertTrue(line, line.contains("wire the retries"))
        assertTrue(line, line.contains("failed earlier"))
        assertTrue(line, line.contains("done"))
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
