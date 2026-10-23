package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Board lifecycle: a finished board retires whether or not a UI is mounted,
 * one session has exactly one board no matter which id a caller holds, and
 * the prompt always says something true about it.
 */
class AgentPlanBoardLifecycleTest {

    private val sid = "lifecycle-chat"
    private val owner = "lifecycle-owner"
    private var clock = FIXED

    @Before
    fun freezeClock() {
        clock = FIXED
        AgentPlanStore.nowMs = { clock }
        AgentPlanStore.clear(sid, null)
        AgentPlanStore.clear(owner, null)
    }

    @After
    fun restoreClock() {
        AgentPlanStore.nowMs = { System.currentTimeMillis() }
        AgentPlanStore.clear(sid, null)
        AgentPlanStore.clear(owner, null)
    }

    // ── retirement ──

    @Test
    fun aFinishedBoardRetiresWithoutAnyUiMounted() {
        val p = AgentPlanStore.add(sid, null, "ship it")
        AgentPlanStore.update(sid, null, p.id, status = "done")
        // Inside the linger window the finished board is still readable: the
        // card is holding its green state.
        assertEquals(1, AgentPlanStore.list(sid, null).size)
        clock += AgentPlanStore.DONE_LINGER_MS
        assertTrue(
            "a finished board must retire on access, not on a Composable's lifecycle",
            AgentPlanStore.list(sid, null).isEmpty(),
        )
    }

    @Test
    fun aBoardWithOpenWorkNeverRetires() {
        val done = AgentPlanStore.add(sid, null, "one")
        AgentPlanStore.add(sid, null, "two")
        AgentPlanStore.update(sid, null, done.id, status = "done")
        clock += AgentPlanStore.DONE_LINGER_MS * 10
        assertEquals(2, AgentPlanStore.list(sid, null).size)
    }

    @Test
    fun reopeningAFinishedBoardCancelsRetirement() {
        val p = AgentPlanStore.add(sid, null, "one", status = "done")
        clock += AgentPlanStore.DONE_LINGER_MS - 1
        AgentPlanStore.update(sid, null, p.id, status = "active")
        clock += AgentPlanStore.DONE_LINGER_MS * 10
        assertEquals(1, AgentPlanStore.list(sid, null).size)
    }

    @Test
    fun anEmptyBoardCarriesNoStaleStamp() {
        val p = AgentPlanStore.add(sid, null, "one")
        AgentPlanStore.update(sid, null, p.id, status = "done")
        clock += AgentPlanStore.DONE_LINGER_MS
        AgentPlanStore.list(sid, null)
        val fresh = AgentPlanStore.add(sid, null, "two", status = "done")
        // The new item finished at the CURRENT clock, so a full window has to
        // elapse again: a leftover stamp would retire it instantly.
        assertEquals(1, AgentPlanStore.list(sid, null).size)
        assertNotNull(fresh)
    }

    // ── one board per owner session ──

    @Test
    fun aLaneIdReachesTheOwnerBoard() {
        val lane = "subagent:$owner:7"
        val p = AgentPlanStore.add(owner, null, "wave item")
        val updated = AgentPlanStore.update(lane, null, p.id, status = "done")
        assertNotNull("a lane and its owner hold one board, not two", updated)
        assertEquals("done", AgentPlanStore.list(owner, null).single().status)
        assertEquals(1, AgentPlanStore.list(lane, null).size)
    }

    @Test
    fun aLaneCannotOrphanTheOwnerItems() {
        val lane = "subagent:$owner:9"
        AgentPlanStore.add(owner, null, "first")
        AgentPlanStore.add(lane, null, "second")
        val board = AgentPlanStore.list(owner, null)
        assertEquals(
            "writes from a lane must not drop the owner's items",
            listOf("first", "second"),
            board.map { it.title },
        )
    }

    // ── the prompt always says something true ──

    @Test
    fun exactlyOneOfSnapshotOrGuidanceSpeaks() {
        assertAnnounced(AgentPlanStore.list(sid, null), empty = true)
        val p = AgentPlanStore.add(sid, null, "one")
        assertAnnounced(AgentPlanStore.list(sid, null), empty = false)
        AgentPlanStore.update(sid, null, p.id, status = "done")
        // Finished: neither speaks. The snapshot would announce work that
        // already landed, and "create a board" is wrong while the card is
        // still holding its green state.
        val finished = AgentPlanStore.list(sid, null)
        assertNull(AgentPlanStore.boardSnapshotPrompt(finished))
        assertNull(PlanBoardGuidance.fragment(finished.isEmpty()))
    }

    @Test
    fun theGuidanceNamesTheToolAndTheTrigger() {
        val guidance = PlanBoardGuidance.fragment(boardEmpty = true)
        assertNotNull(guidance)
        assertTrue(guidance!!.contains("agent_plan"))
        assertTrue(guidance.contains("<plan_board>"))
    }

    @Test
    fun theEmptyRenderNamesTheGapInsteadOfReadingAsFine() {
        val text = AgentPlanStore.render(sid, null)
        assertTrue(text, text.contains("no board yet"))
    }

    private fun assertAnnounced(plans: List<AgentPlanStore.Plan>, empty: Boolean) {
        val snapshot = AgentPlanStore.boardSnapshotPrompt(plans)
        val guidance = PlanBoardGuidance.fragment(plans.isEmpty())
        if (empty) {
            assertNull("an empty board has no snapshot", snapshot)
            assertNotNull("an empty board must still be announced", guidance)
        } else {
            assertNotNull("open work carries the snapshot", snapshot)
            assertNull("a board with items needs no guidance", guidance)
        }
    }

    private companion object {
        const val FIXED = 1_700_000_000_000L
    }
}
