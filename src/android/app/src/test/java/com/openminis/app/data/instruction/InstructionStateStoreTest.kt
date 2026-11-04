package com.openminis.app.data.instruction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-p1-instruction-snapshot] InstructionStateStore against a REAL in-memory
 * Room database: freeze/delta commit-or-skip, seq monotonicity, and the
 * nested goal rendering all hit real SQLite so upsert and insertUpdate
 * contracts are exercised rather than assumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class InstructionStateStoreTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun store() = InstructionStateStore(db)

    @Test
    fun emptyObservationWithoutStoredStateIsSkipped() = runBlocking {
        assertFalse(store().observeAndCommit("s1", emptyMap()))
        assertNull(db.instructionStateDao().stateFor("s1"))
        assertTrue(db.instructionStateDao().updatesFor("s1", 10).isEmpty())
    }

    @Test
    fun firstNonEmptyObservationFreezes() = runBlocking {
        assertTrue(store().observeAndCommit("s1", mapOf(InstructionState.KEY_GOAL to "g1")))
        val state = db.instructionStateDao().stateFor("s1")!!
        val parsed = InstructionState.parseValues(state.currentValuesJson)
        assertEquals("g1", parsed[InstructionState.KEY_GOAL])
        assertEquals(state.epochStartSeq, state.throughSeq)
        val updates = db.instructionStateDao().updatesFor("s1", 10)
        assertEquals(1, updates.size)
        assertTrue(updates[0].isInitial)
        assertNull(updates[0].deltaJson)
    }

    @Test
    fun identicalObservationCommitsNothing() = runBlocking {
        val store = store()
        store.observeAndCommit("s1", mapOf(InstructionState.KEY_GOAL to "g1"))
        assertFalse(store.observeAndCommit("s1", mapOf(InstructionState.KEY_GOAL to "g1")))
        assertEquals(1, db.instructionStateDao().updatesFor("s1", 10).size)
    }

    @Test
    fun changedObservationAppendsDeltaWithMonotonicSeq() = runBlocking {
        val store = store()
        store.observeAndCommit("s1", mapOf(InstructionState.KEY_GOAL to "g1"))
        assertTrue(store.observeAndCommit("s1", mapOf(InstructionState.KEY_GOAL to "g2")))
        val updates = db.instructionStateDao().updatesFor("s1", 10)
        assertEquals(2, updates.size)
        assertFalse(updates[1].isInitial)
        assertTrue(updates[1].seq > updates[0].seq)
        val delta = InstructionState.parseDelta(requireNotNull(updates[1].deltaJson))
        assertEquals(mapOf(InstructionState.KEY_GOAL to "g2"), delta.changes)
    }

    @Test
    fun emptyObservationWithStoredStateClearsEverything() = runBlocking {
        val store = store()
        store.observeAndCommit("s1", mapOf(InstructionState.KEY_GOAL to "g1"))
        assertTrue(store.observeAndCommit("s1", emptyMap()))
        val state = db.instructionStateDao().stateFor("s1")!!
        assertTrue(InstructionState.parseValues(state.currentValuesJson).isEmpty())
        val delta = InstructionState.parseDelta(
            requireNotNull(db.instructionStateDao().updatesFor("s1", 10).last().deltaJson),
        )
        assertEquals(mapOf(InstructionState.KEY_GOAL to null), delta.changes)
    }

    @Test
    fun goalInstructionValuesRendersNestedGoalJson() {
        val goal = SessionGoalEntity(
            sessionId = "s1",
            objective = "ship it",
            status = GoalStatus.ACTIVE,
            tokenBudget = 8000L,
            blockedCondition = null,
            blockedCount = 0,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val values = InstructionStateStore.goalInstructionValues(goal)
        val parsed = InstructionState.parseValues(values.getValue(InstructionState.KEY_GOAL))
        assertEquals("ship it", parsed["objective"])
        assertEquals(GoalStatus.ACTIVE, parsed["status"])
        assertEquals("8000", parsed["tokenBudget"])
        assertEquals("0", parsed["blockedCount"])
        assertFalse(parsed.containsKey("blockedCondition"))
    }

    @Test
    fun nullGoalYieldsEmptyValues() {
        assertTrue(InstructionStateStore.goalInstructionValues(null).isEmpty())
    }
}
