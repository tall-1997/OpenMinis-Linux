package com.openminis.app.tools

import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.goal.GoalManager
import com.openminis.app.goal.GoalPersistence
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-goal-created-with-plan] Which `agent_plan` calls establish the Goal, and
 * what objective they record.
 *
 * The board is rewritten wholesale on every change, so the sync must fire on
 * the writes that DEFINE the work and stay out of the way of `list`,
 * `update` and `clear` — otherwise marking an item done would mint a Goal from
 * a title, or resurrect one the user cleared.
 *
 * [T-goal-not-evicted-by-fallback] `tool_title` names THIS CALL's action, not
 * the session's work: it is never an objective source, and a fallback may
 * only fill an empty seat — an objective-less board rewrite keeps the
 * standing Goal. Only an explicit `objective` replaces one.
 */
class PlanGoalSyncTest {

    @Test
    fun `board writes establish the goal`() {
        assertTrue(PlanGoalSync.applies("""{"op":"add","title":"step"}"""))
        assertTrue(PlanGoalSync.applies("""{"op":"replace","plans":[]}"""))
    }

    @Test
    fun `board reads and item edits do not`() {
        assertFalse(PlanGoalSync.applies("""{"op":"list"}"""))
        assertFalse(PlanGoalSync.applies("""{"op":"update","id":"1","status":"done"}"""))
        assertFalse(PlanGoalSync.applies("""{"op":"remove","id":"1"}"""))
        assertFalse(PlanGoalSync.applies("""{"op":"clear"}"""))
        assertFalse(PlanGoalSync.applies("not json"))
    }

    @Test
    fun `the explicit objective wins`() {
        val args = JSONObject(
            """{"op":"replace","objective":"ship the release","tool_title":"Release work",
               "plans":[{"title":"build"},{"title":"tag"}]}""",
        )
        assertEquals("ship the release", PlanGoalSync.objectiveFrom(args))
    }

    @Test
    fun `tool_title never becomes the objective`() {
        val args = JSONObject("""{"op":"add","tool_title":"Release work","title":"build"}""")
        assertEquals("build", PlanGoalSync.objectiveFrom(args))
    }

    @Test
    fun `the first board item is the last fallback`() {
        val args = JSONObject("""{"op":"replace","plans":[{"title":"build the APK"},{"title":"tag"}]}""")
        assertEquals("build the APK", PlanGoalSync.objectiveFrom(args))
    }

    @Test
    fun `blank arguments never mint an empty goal`() {
        assertNull(PlanGoalSync.objectiveFrom(JSONObject("""{"op":"add","objective":"   ","tool_title":"  "}""")))
        assertNull(PlanGoalSync.objectiveFrom(JSONObject("""{"op":"replace","plans":[{"title":"  "}]}""")))
    }

    @Test
    fun `a plans array sent as a JSON string is still searched`() {
        val args = JSONObject("""{"op":"replace","plans":"[{\"title\":\"stringified step\"}]"}""")
        assertEquals("stringified step", PlanGoalSync.objectiveFrom(args))
    }

    /**
     * [T-goal-not-evicted-by-fallback] The ensureGoal layering needs a real
     * [GoalManager]; driven through the same in-memory [GoalPersistence] fake
     * the goal-package tests use, because JVM tests must not drag Room in.
     */
    private class FakePersistence : GoalPersistence {
        val rows = mutableMapOf<String, SessionGoalEntity>()
        override suspend fun load(sessionId: String): SessionGoalEntity? = rows[sessionId]
        override suspend fun insert(goal: SessionGoalEntity) { rows[goal.sessionId] = goal }
        override suspend fun save(goal: SessionGoalEntity) { rows[goal.sessionId] = goal }
        override suspend fun delete(sessionId: String) { rows.remove(sessionId) }
    }

    @Test
    fun `a fallback title fills an empty seat only`() = runTest {
        val manager = GoalManager(FakePersistence())
        manager.createByModel("s1", "ship the release")
        val kept = PlanGoalSync.ensureGoal(
            """{"op":"replace","plans":[{"title":"step A"}]}""",
            "s1",
            manager,
        )
        assertEquals("ship the release", kept?.objective)
        assertEquals("ship the release", manager.get("s1")?.objective)
    }

    @Test
    fun `an objective-less single-item board write no longer mints a Goal`() = runTest {
        // [T-goal-mint-guard] A single-item write is progress logging, not
        // goal-setting — minting from a step title created objectives that
        // could never complete.
        val manager = GoalManager(FakePersistence())
        val minted = PlanGoalSync.ensureGoal(
            """{"op":"add","tool_title":"Release work","title":"build"}""",
            "s1",
            manager,
        )
        assertNull(minted)
        assertNull(manager.get("s1"))
    }

    @Test
    fun `an objective-less multi-item board still fills an empty seat`() = runTest {
        // [T-goal-mint-guard] A board that LOOKS like a plan (≥2 items) keeps
        // the fill-from-first-title fallback.
        val manager = GoalManager(FakePersistence())
        val minted = PlanGoalSync.ensureGoal(
            """{"op":"add","tool_title":"Release work","plans":[{"title":"build"},{"title":"test"}]}""",
            "s1",
            manager,
        )
        assertEquals("build", minted?.objective)
        assertEquals("build", manager.get("s1")?.objective)
    }

    @Test
    fun `an explicit objective still replaces the standing goal`() = runTest {
        val manager = GoalManager(FakePersistence())
        manager.createByModel("s1", "ship the release")
        val replaced = PlanGoalSync.ensureGoal(
            """{"op":"replace","objective":"pivot to hotfix"}""",
            "s1",
            manager,
        )
        assertEquals("pivot to hotfix", replaced?.objective)
        assertEquals("pivot to hotfix", manager.get("s1")?.objective)
    }
}
