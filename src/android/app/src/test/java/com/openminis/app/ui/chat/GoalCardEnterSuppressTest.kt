package com.openminis.app.ui.chat

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-goal-enter-suppress] Pure-decision tests for [goalEntersSuppressed]:
 * re-entering a session must never replay the green linger for a goal that
 * completed in an earlier visit; only goals that finish while their row is
 * already displayed keep the linger.
 */
class GoalCardEnterSuppressTest {

    private fun goal(status: String, updatedAt: Long = 1000L) = SessionGoalEntity(
        sessionId = "s1",
        objective = "ship it",
        status = status,
        tokensUsed = 0,
        elapsedMs = 0,
        createdAt = 0,
        updatedAt = updatedAt,
    )

    @Test
    fun completedGoalOnFreshMountIsSuppressed() {
        // Entry with nothing displayed yet: no in-session completion to
        // celebrate, so the header must stay quiet.
        assertTrue(goalEntersSuppressed(goal(GoalStatus.COMPLETE), displayed = null))
    }

    @Test
    fun completedGoalWhileDisplayedKeepsLinger() {
        // The row was already on screen (e.g. ACTIVE snapshot shown) and the
        // goal then completed: the green linger is the whole point.
        assertFalse(
            goalEntersSuppressed(
                goal(GoalStatus.COMPLETE, updatedAt = 2000L),
                displayed = goal(GoalStatus.ACTIVE, updatedAt = 1000L),
            )
        )
    }

    @Test
    fun activeGoalOnFreshMountIsShown() {
        assertFalse(goalEntersSuppressed(goal(GoalStatus.ACTIVE), displayed = null))
    }

    @Test
    fun blockedGoalOnFreshMountIsShown() {
        // BLOCKED / BUDGET_LIMITED / PAUSED rows carry information the user
        // must see on entry — suppression targets COMPLETE only.
        assertFalse(goalEntersSuppressed(goal(GoalStatus.BLOCKED), displayed = null))
        assertFalse(goalEntersSuppressed(goal(GoalStatus.BUDGET_LIMITED), displayed = null))
        assertFalse(goalEntersSuppressed(goal(GoalStatus.PAUSED), displayed = null))
    }

    @Test
    fun tokenFormatMatchesUsageSheetMagnitude() {
        // [T-goal-token-format-fix] Same k/M scale as TokenUsageSheet /
        // usage footer; the historical 10x inflation (1234 → "12.3k") is gone.
        assertEquals("999", formatGoalTokens(999))
        assertEquals("1k", formatGoalTokens(1000))
        assertEquals("1.2k", formatGoalTokens(1234))
        assertEquals("12.3k", formatGoalTokens(12345))
        assertEquals("1.2M", formatGoalTokens(1234567))
        assertEquals("0", formatGoalTokens(0))
    }
}
