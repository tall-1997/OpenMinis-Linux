package com.openminis.app.goal

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-goal-contract] The contract fields render verbatim into every request
 * prompt, so updateContract must clamp them — the P0 plan's 800-char ceiling.
 * Driven through the [GoalPersistence] seam, same as GoalManagerTest.
 */
class GoalManagerContractTest {

    private class FakePersistence : GoalPersistence {
        var stored: SessionGoalEntity? = null
        var savedAcceptance: String? = null
        var savedConstraints: String? = null
        var savedNextStep: String? = null

        override suspend fun load(sessionId: String): SessionGoalEntity? = stored
        override suspend fun insert(goal: SessionGoalEntity) { stored = goal }
        override suspend fun save(goal: SessionGoalEntity) { stored = goal }
        override suspend fun saveContract(
            sessionId: String,
            acceptance: String?,
            constraints: String?,
            nextStep: String?,
            updatedAt: Long,
        ) {
            savedAcceptance = acceptance
            savedConstraints = constraints
            savedNextStep = nextStep
            stored = stored?.copy(
                acceptance = acceptance,
                constraints = constraints,
                nextStep = nextStep,
            )
        }
        override suspend fun delete(sessionId: String) { stored = null }
    }

    private fun entity(objective: String = "build the thing") = SessionGoalEntity(
        sessionId = "s1",
        objective = objective,
        status = GoalStatus.ACTIVE,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun shortFieldsPassThroughUnchanged() = runTest {
        val persistence = FakePersistence()
        val manager = GoalManager(persistence)
        persistence.stored = entity()
        manager.updateContract("s1", acceptance = "compiles and tests green", nextStep = "run tests")
        assertEquals("compiles and tests green", persistence.savedAcceptance)
        assertEquals("run tests", persistence.savedNextStep)
        assertEquals(null, persistence.savedConstraints)
    }

    @Test
    fun oversizedFieldsAreClampedToTheContractCeiling() = runTest {
        val persistence = FakePersistence()
        val manager = GoalManager(persistence)
        persistence.stored = entity()
        val huge = "x".repeat(GoalManager.CONTRACT_FIELD_MAX_CHARS + 500)
        manager.updateContract("s1", acceptance = huge, constraints = huge, nextStep = huge)
        for (saved in listOf(persistence.savedAcceptance, persistence.savedConstraints, persistence.savedNextStep)) {
            assertEquals(GoalManager.CONTRACT_FIELD_MAX_CHARS + "…[clamped]".length, saved!!.length)
            assertTrue(saved.endsWith("…[clamped]"))
        }
    }

    @Test
    fun omittedFieldsKeepTheirOldValues() = runTest {
        val persistence = FakePersistence()
        val manager = GoalManager(persistence)
        persistence.stored = entity().copy(acceptance = "keep me")
        manager.updateContract("s1", nextStep = "only this")
        assertEquals("keep me", persistence.stored!!.acceptance)
        assertEquals("only this", persistence.savedNextStep)
    }
}
