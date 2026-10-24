package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the supervisor-worker turn state machine.
 *
 * The turn order is fixed: supervisor instructs, worker executes,
 * supervisor reviews, repeat. A review carrying a DONE verdict ends the
 * session; a rubber-stamped DONE gets one strike and a second one fails
 * the session; the worker-turn cap force-closes it; a user halt closes
 * it from any live phase.
 */
class SupervisorChatTurnTest {

    private val acceptance = listOf("scan completes under 5s", "no ANR in stall detector")

    private fun machine(cap: Int = 50) = SupervisorChat.TurnMachine(maxWorkerTurns = cap)

    @Test
    fun freshMachineStartsInstructing() {
        assertEquals(SupervisorChat.Phase.INSTRUCTING, machine().phase)
    }

    @Test
    fun supervisorMessageWithoutDoneStartsWorkerTurn() {
        val m = machine()
        m.onSupervisorMessage("## Task\n- fix it\n\n## Expected result\n- scan completes under 5s", acceptance)
        assertEquals(SupervisorChat.Phase.EXECUTING, m.phase)
        assertEquals(1, m.workerTurns)
    }

    @Test
    fun workerReportMovesToReviewing() {
        val m = machine()
        m.onSupervisorMessage("## Task\n- fix it\n\n## Expected result\n- scan completes under 5s", acceptance)
        m.onWorkerReport()
        assertEquals(SupervisorChat.Phase.REVIEWING, m.phase)
    }

    @Test
    fun doneVerdictEndsSession() {
        val m = machine()
        m.onSupervisorMessage("## Task\n- fix it\n\n## Expected result\n- scan completes under 5s", acceptance)
        m.onWorkerReport()
        val phase = m.onSupervisorMessage("DONE: scan completes under 5s, no ANR in stall detector", acceptance)
        assertEquals(SupervisorChat.Phase.DONE, phase)
    }

    @Test
    fun rubberStampDoneGetsOneStrikeThenFailsOnSecond() {
        val m = machine()
        m.onSupervisorMessage("## Task\n- fix it\n\n## Expected result\n- scan completes under 5s", acceptance)
        m.onWorkerReport()
        val first = m.onSupervisorMessage("DONE: all good, ship it", acceptance)
        assertEquals(SupervisorChat.Phase.REVIEWING, first)
        assertEquals(1, m.rubberStampStrikes)
        m.onWorkerReport()
        val second = m.onSupervisorMessage("DONE: looks fine to me", acceptance)
        assertEquals(SupervisorChat.Phase.FAILED, second)
    }

    @Test
    fun workerTurnCapForceClosesSession() {
        val m = machine(cap = 2)
        m.onSupervisorMessage("## Task\n- fix it\n\n## Expected result\n- scan completes under 5s", acceptance)
        m.onWorkerReport()
        m.onSupervisorMessage("still wrong, retry", acceptance)
        m.onWorkerReport()
        // Third worker turn would exceed cap=2: the next instruction attempt force-closes.
        val phase = m.onSupervisorMessage("retry once more", acceptance)
        assertEquals(SupervisorChat.Phase.FAILED, phase)
        assertTrue(m.forceClosed())
    }

    @Test
    fun capWinsOverPendingDone() {
        val m = machine(cap = 1)
        m.onSupervisorMessage("## Task\n- fix it\n\n## Expected result\n- scan completes under 5s", acceptance)
        m.onWorkerReport()
        // Worker turns exhausted; even a valid DONE cannot start a new worker round-trip.
        val phase = m.onSupervisorMessage("DONE: scan completes under 5s", acceptance)
        assertEquals(SupervisorChat.Phase.DONE, phase)
        // But a non-DONE message at cap force-closes instead of starting a worker turn.
        val m2 = machine(cap = 1)
        m2.onSupervisorMessage("## Task\n- fix it\n\n## Expected result\n- scan completes under 5s", acceptance)
        m2.onWorkerReport()
        assertEquals(SupervisorChat.Phase.FAILED, m2.onSupervisorMessage("keep going", acceptance))
        assertTrue(m2.forceClosed())
    }

    @Test
    fun userHaltClosesFromAnyLivePhase() {
        for (phase in listOf(SupervisorChat.Phase.INSTRUCTING, SupervisorChat.Phase.EXECUTING, SupervisorChat.Phase.REVIEWING)) {
            val m = machine()
            while (m.phase != phase) {
                when (m.phase) {
                    SupervisorChat.Phase.INSTRUCTING -> m.onSupervisorMessage("## Task\n- fix\n\n## Expected result\n- scan completes under 5s", acceptance)
                    SupervisorChat.Phase.EXECUTING -> m.onWorkerReport()
                    else -> {}
                }
            }
            assertEquals(SupervisorChat.Phase.USER_HALTED, m.onUserHalt())
            assertFalse(m.forceClosed())
        }
    }

    @Test
    fun terminalPhasesRejectFurtherTransitions() {
        val m = machine()
        m.onSupervisorMessage("## Task\n- fix\n\n## Expected result\n- scan completes under 5s", acceptance)
        m.onWorkerReport()
        m.onSupervisorMessage("DONE: scan completes under 5s", acceptance)
        assertEquals(SupervisorChat.Phase.DONE, m.onSupervisorMessage("DONE: scan completes under 5s", acceptance))
        assertEquals(SupervisorChat.Phase.DONE, m.onWorkerReport())
    }

    @Test
    fun capOfOneAllowsExactlyOneWorkerTurn() {
        val m = machine(cap = 1)
        m.onSupervisorMessage("## Task\n- fix\n\n## Expected result\n- scan completes under 5s", acceptance)
        assertEquals(1, m.workerTurns)
        assertEquals(SupervisorChat.Phase.EXECUTING, m.phase)
    }
}
