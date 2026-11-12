package com.openminis.app.goal

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-goal-continuation-round] The structured continuation round: every
 * goal-driven synthetic round carries the
 * `<system-reminder source="goal-continuation">` marker (ZCode shape), and
 * detection is a startsWith check on the opening tag — the marker is the
 * first thing the round carries, so detection never depends on what the
 * goal's prompt body happens to quote inside.
 */
class GoalContinuationTest {

    private fun goal(
        objective: String = "ship the release",
        status: String = GoalStatus.ACTIVE,
    ): SessionGoalEntity = GoalStateMachine.create("s1", objective, 500, 1)
        .copy(status = status)

    @Test fun `note is framed as a system reminder with the goal-continuation source`() {
        val note = GoalContinuation.note(goal())
        assertTrue(note.startsWith(GoalContinuation.OPEN_TAG))
        assertTrue(note.contains("source=\"goal-continuation\""))
        assertTrue(note.trimEnd().endsWith("</system-reminder>"))
    }

    @Test fun `note carries the goal context block and the objective`() {
        val note = GoalContinuation.note(goal(objective = "ship the release"))
        assertTrue(note.contains("<goal_context>"))
        assertTrue(note.contains("</goal_context>"))
        assertTrue(note.contains("ship the release"))
        assertTrue(note.contains("status=active"))
    }

    @Test fun `reason rides the note verbatim for verifier feed-back`() {
        val reason = "Host verification rejected the completion: plan board is 1/3 done."
        val note = GoalContinuation.note(goal(), reason = reason)
        assertTrue(note.contains(reason))
        // Reason sits between the goal block and the closing tag.
        assertTrue(note.indexOf(reason) > note.indexOf("</goal_context>"))
        assertTrue(note.indexOf(reason) < note.lastIndexOf("</system-reminder>"))
    }

    @Test fun `blank reason is dropped`() {
        val plain = GoalContinuation.note(goal())
        assertEquals(plain, GoalContinuation.note(goal(), reason = "   "))
    }

    @Test fun `round trip write then recognize`() {
        val note = GoalContinuation.note(goal())
        assertTrue(GoalContinuation.isGoalContinuation(note))
    }

    @Test fun `recognize tolerates leading whitespace`() {
        val note = "\n" + GoalContinuation.note(goal())
        assertTrue(GoalContinuation.isGoalContinuation(note))
    }

    @Test fun `ordinary user speech and other reminders are not continuation rounds`() {
        assertFalse(GoalContinuation.isGoalContinuation("please ship the release"))
        // A generic reminder without the source marker is not a continuation.
        assertFalse(GoalContinuation.isGoalContinuation("<system-reminder>\nsomething else\n</system-reminder>"))
        // The one-shot goal notice uses the bare reminder framing.
        assertFalse(GoalContinuation.isGoalContinuation("<system-reminder>\nCurrent objective\n</system-reminder>"))
        assertFalse(GoalContinuation.isGoalContinuation(""))
    }

    @Test fun `continuation rounds differ per status so re-render is visible`() {
        val active = GoalContinuation.note(goal(status = GoalStatus.ACTIVE))
        val blocked = GoalContinuation.note(goal(status = GoalStatus.BLOCKED))
        assertTrue(active.contains("status=active"))
        assertTrue(blocked.contains("status=blocked"))
        // Both are still continuation rounds — the marker is the frame, the
        // body carries the state.
        assertTrue(GoalContinuation.isGoalContinuation(active))
        assertTrue(GoalContinuation.isGoalContinuation(blocked))
    }
}
