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
    fun thePromptNeverAnnouncesAFinishedOrEmptyBoard() {
        // Empty: no snapshot fragment. The board's absence is still covered —
        // the agent_plan tool description carries the "create it when the work
        // spans 3+ steps" contract and ships in the schema every turn, so the
        // announcement channel moved from a prompt fragment into the schema
        // where it can no longer vanish exactly when the board is missing.
        assertNull(AgentPlanStore.boardSnapshotPrompt(AgentPlanStore.list(sid, null)))
        val p = AgentPlanStore.add(sid, null, "one")
        assertNotNull(AgentPlanStore.boardSnapshotPrompt(AgentPlanStore.list(sid, null)))
        AgentPlanStore.update(sid, null, p.id, status = "done")
        // Finished: the snapshot would announce work that already landed,
        // while the card is still holding its green state.
        assertNull(AgentPlanStore.boardSnapshotPrompt(AgentPlanStore.list(sid, null)))
    }

    @Test
    fun theToolDescriptionCarriesTheWhenToCreateContract() {
        val description = AgentPlanTool.definition().description
        assertTrue(description, description.contains("agent_plan") || description.contains("plan"))
        assertTrue(description, description.contains("3+ steps"))
        assertTrue(description, description.contains("flip the item you are on to active"))
        assertTrue(description, description.contains("op=replace"))
    }

    @Test
    fun theToolDescriptionNamesTheReplaceContract() {
        val description = AgentPlanTool.definition().description
        assertTrue(description, description.contains("full-list REPLACE"))
        assertTrue(description, description.contains("ids are optional"))
    }

    @Test
    fun theEmptyRenderNamesTheGapInsteadOfReadingAsFine() {
        val text = AgentPlanStore.render(sid, null)
        assertTrue(text, text.contains("no board yet"))
    }

    @Test
    fun theSnapshotTeachesNumberTargeting() {
        AgentPlanStore.add(sid, null, "one")
        AgentPlanStore.add(sid, null, "two")
        val snapshot = AgentPlanStore.boardSnapshotPrompt(AgentPlanStore.list(sid, null))
        assertNotNull(snapshot)
        assertTrue(snapshot!!, snapshot.contains("update id=\"2\""))
        assertTrue(snapshot, snapshot.contains("op=replace"))
    }

    private companion object {
        const val FIXED = 1_700_000_000_000L
    }
}
