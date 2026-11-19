package com.openminis.app.data.instruction

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-p1-instruction-snapshot] Pins what the frozen instruction snapshot may
 * contain: the goal always, the two GLOBAL fragments only when memory is on,
 * and each fragment under the key of its own source file.
 *
 * The distinct-source assertion is the regression pin for the shipped defect —
 * the first cut loaded both GLOBAL keys from the session repository, so
 * `app-global` and `session-global` held the same file's text and an app-wide
 * GLOBAL.md edit produced no delta at all.
 */
class InstructionSnapshotValuesTest {

    private fun goalValues() = InstructionStateStore.goalInstructionValues(
        SessionGoalEntity(
            sessionId = "s1",
            objective = "ship it",
            status = GoalStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 2L,
        ),
    )

    @Test
    fun ruleFragmentsAreRecordedRegardlessOfMemoryToggle() {
        // [T-rules-uncoupled-from-memory-toggle] Rules are instructions, not
        // memory: they inject with the session's memory toggle off too, so the
        // snapshot must record them (freezing nothing = describing an
        // instruction-less prompt that actually carried rules).
        val values = InstructionSnapshotValues.of(
            goalValues(),
            appGlobalFragment = "APP-RULES",
            sessionGlobalFragment = "SESSION-RULES",
        )
        assertEquals(
            setOf(
                InstructionState.KEY_GOAL,
                InstructionState.KEY_APP_GLOBAL,
                InstructionState.KEY_SESSION_GLOBAL,
            ),
            values.keys,
        )
    }

    @Test
    fun appAndSessionGlobalsComeFromDistinctValues() {
        val values = InstructionSnapshotValues.of(
            goalValues = emptyMap(),
            appGlobalFragment = "APP-RULES",
            sessionGlobalFragment = "SESSION-RULES",
        )
        assertEquals("APP-RULES", values.getValue(InstructionState.KEY_APP_GLOBAL))
        assertEquals("SESSION-RULES", values.getValue(InstructionState.KEY_SESSION_GLOBAL))
    }

    @Test
    fun absentFragmentsAreOmittedNotBlanked() {
        val values = InstructionSnapshotValues.of(
            goalValues = emptyMap(),
            appGlobalFragment = null,
            sessionGlobalFragment = "S",
        )
        assertFalse(values.containsKey(InstructionState.KEY_APP_GLOBAL))
        assertEquals("S", values.getValue(InstructionState.KEY_SESSION_GLOBAL))
    }

    @Test
    fun blankFragmentsAreTreatedAsAbsent() {
        val values = InstructionSnapshotValues.of(
            goalValues = emptyMap(),
            appGlobalFragment = "   \n",
            sessionGlobalFragment = "",
        )
        assertTrue(values.isEmpty())
    }

    @Test
    fun everythingOffAndNoGoalIsEmptySoTheStoreSkips() {
        val values = InstructionSnapshotValues.of(
            goalValues = emptyMap(),
            appGlobalFragment = null,
            sessionGlobalFragment = null,
        )
        assertTrue(values.isEmpty())
    }

    @Test
    fun goalIsStillFrozenWithMemoryOff() {
        val values = InstructionSnapshotValues.of(
            goalValues(),
            appGlobalFragment = "APP-RULES",
            sessionGlobalFragment = null,
        )
        assertTrue(values.getValue(InstructionState.KEY_GOAL).contains("ship it"))
    }

    // ── [T-memory-project-rules] the third instruction layer ────────────────

    @Test
    fun projectRulesAreFrozenUnderTheirOwnKey() {
        val values = InstructionSnapshotValues.of(
            goalValues = emptyMap(),
            appGlobalFragment = "APP",
            sessionGlobalFragment = "SESSION",
            projectRulesFragment = "PROJECT",
        )
        assertEquals("PROJECT", values.getValue(InstructionState.KEY_PROJECT_RULES))
        assertEquals("APP", values.getValue(InstructionState.KEY_APP_GLOBAL))
        assertEquals("SESSION", values.getValue(InstructionState.KEY_SESSION_GLOBAL))
    }

    @Test
    fun allThreeRuleLayersAreRecordedTogether() {
        val values = InstructionSnapshotValues.of(
            goalValues = emptyMap(),
            appGlobalFragment = "APP",
            sessionGlobalFragment = "SESSION",
            projectRulesFragment = "PROJECT",
        )
        assertEquals(
            setOf(
                InstructionState.KEY_APP_GLOBAL,
                InstructionState.KEY_PROJECT_RULES,
                InstructionState.KEY_SESSION_GLOBAL,
            ),
            values.keys,
        )
    }
}
