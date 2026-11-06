package com.openminis.app.tools

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
    fun `tool_title is the fallback when no objective is stated`() {
        val args = JSONObject("""{"op":"add","tool_title":"Release work","title":"build"}""")
        assertEquals("Release work", PlanGoalSync.objectiveFrom(args))
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
}
