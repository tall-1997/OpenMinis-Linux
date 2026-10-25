package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the supervisor UI projection host (PR#3, spec §3).
 *
 * The status bar renders [SupervisorUiHost.uiState] and gates on the
 * foregrounded session id, so these semantics are load-bearing for "a live
 * bar never follows the user into another conversation":
 *  - update publishes only for non-blank session ids
 *  - reset clears the slot AND the flow (when the flow shows that session)
 *  - reset for a different session leaves the flow alone (isolation)
 *  - republish restores a stored state without re-writing it
 *  - republish for an unknown session is a no-op (flow untouched)
 *  - isLive covers exactly the three live phases
 */
class SupervisorUiHostTest {

    private fun clean(vararg ids: String) {
        ids.forEach { SupervisorUiHost.reset(it) }
    }

    @Test
    fun updatePublishesStateAndStoresItPerSession() {
        clean("ui-1")
        SupervisorUiHost.update("ui-1", SupervisorUiHost.Phase.INSTRUCTING, "objective", 0)
        val state = SupervisorUiHost.uiState.value
        assertNotNull(state)
        assertEquals("ui-1", state!!.sessionId)
        assertEquals(SupervisorUiHost.Phase.INSTRUCTING, state.phase)
        assertEquals("objective", state.objective)
        assertEquals(0, state.workerTurns)
        assertTrue(state.isLive)
        clean("ui-1")
    }

    @Test
    fun updateWithBlankSessionIdIsIgnored() {
        val before = SupervisorUiHost.uiState.value
        SupervisorUiHost.update("", SupervisorUiHost.Phase.EXECUTING, "nope", 1)
        assertEquals(before, SupervisorUiHost.uiState.value)
    }

    @Test
    fun resetClearsBothSlotAndFlowForThatSession() {
        clean("ui-2")
        SupervisorUiHost.update("ui-2", SupervisorUiHost.Phase.REVIEWING, "objective", 3)
        SupervisorUiHost.reset("ui-2")
        assertNull(SupervisorUiHost.uiState.value)
        // The slot is gone too: republish cannot resurrect it.
        SupervisorUiHost.republish("ui-2")
        assertNull(SupervisorUiHost.uiState.value)
    }

    @Test
    fun resetForAnotherSessionLeavesTheFlowAlone() {
        clean("ui-3a", "ui-3b")
        SupervisorUiHost.update("ui-3a", SupervisorUiHost.Phase.EXECUTING, "a", 1)
        SupervisorUiHost.update("ui-3b", SupervisorUiHost.Phase.EXECUTING, "b", 2)
        // The flow now shows ui-3b (last writer). Resetting ui-3a must not
        // clear the visible bar for ui-3b.
        SupervisorUiHost.reset("ui-3a")
        val state = SupervisorUiHost.uiState.value
        assertNotNull(state)
        assertEquals("ui-3b", state!!.sessionId)
        // But ui-3a's slot is gone: switching back to it shows nothing.
        SupervisorUiHost.republish("ui-3a")
        assertEquals("ui-3b", SupervisorUiHost.uiState.value!!.sessionId)
        clean("ui-3b")
    }

    @Test
    fun republishRestoresStoredStateAfterAnotherSessionTookTheFlow() {
        clean("ui-4a", "ui-4b")
        SupervisorUiHost.update("ui-4a", SupervisorUiHost.Phase.INSTRUCTING, "first", 0)
        SupervisorUiHost.update("ui-4b", SupervisorUiHost.Phase.DONE, "second", 5)
        // User switches back to ui-4a: loadSession calls republish.
        SupervisorUiHost.republish("ui-4a")
        val state = SupervisorUiHost.uiState.value
        assertNotNull(state)
        assertEquals("ui-4a", state!!.sessionId)
        assertEquals("first", state.objective)
        clean("ui-4a", "ui-4b")
    }

    @Test
    fun republishForUnknownSessionIsANoOp() {
        clean("ui-5")
        SupervisorUiHost.update("ui-5", SupervisorUiHost.Phase.EXECUTING, "known", 2)
        SupervisorUiHost.republish("ui-5-unknown")
        val state = SupervisorUiHost.uiState.value
        assertNotNull(state)
        assertEquals("ui-5", state!!.sessionId)
        clean("ui-5")
    }

    @Test
    fun isLiveCoversExactlyTheThreeLivePhases() {
        val livePhases = listOf(
            SupervisorUiHost.Phase.INSTRUCTING,
            SupervisorUiHost.Phase.EXECUTING,
            SupervisorUiHost.Phase.REVIEWING,
        )
        val terminalPhases = listOf(
            SupervisorUiHost.Phase.DONE,
            SupervisorUiHost.Phase.HALTED,
            SupervisorUiHost.Phase.FAILED,
            SupervisorUiHost.Phase.INTERRUPTED,
        )
        clean("ui-6")
        livePhases.forEach { phase ->
            SupervisorUiHost.update("ui-6", phase, "objective", 0)
            assertTrue("phase $phase must be live", SupervisorUiHost.uiState.value!!.isLive)
        }
        terminalPhases.forEach { phase ->
            SupervisorUiHost.update("ui-6", phase, "objective", 0)
            assertFalse("phase $phase must not be live", SupervisorUiHost.uiState.value!!.isLive)
        }
        clean("ui-6")
    }

    @Test
    fun flowEmitsLatestStateForCollectors() = runBlocking {
        clean("ui-7")
        SupervisorUiHost.update("ui-7", SupervisorUiHost.Phase.EXECUTING, "objective", 4)
        val state = SupervisorUiHost.uiState.filterNotNull().first()
        assertEquals("ui-7", state.sessionId)
        assertEquals(4, state.workerTurns)
        clean("ui-7")
    }
}
