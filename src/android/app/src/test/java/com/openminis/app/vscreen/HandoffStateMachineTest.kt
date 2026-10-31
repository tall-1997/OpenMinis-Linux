package com.openminis.app.vscreen

import com.openminis.app.vscreen.HandoffStateMachine.Action
import com.openminis.app.vscreen.HandoffStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vscreen2] JVM tests for the pure hand_to_user three-state
 * machine: the START gate, the three user choices (处理好了 / 我自己做完了 /
 * 不做了), the move-back-failure hold, and the model-facing guidance text.
 */
class HandoffStateMachineTest {

    @Test
    fun `start moves idle to waiting`() {
        val t = HandoffStateMachine.transition(State.IDLE, Action.START)
        assertTrue(t is HandoffStateMachine.Transition.Allowed)
        assertEquals(State.WAITING, (t as HandoffStateMachine.Transition.Allowed).state)
    }

    @Test
    fun `a successful move back resolves to returned`() {
        val t = HandoffStateMachine.transition(State.WAITING, Action.MOVE_BACK_DONE, moveBackOk = true)
        assertEquals(State.RETURNED, (t as HandoffStateMachine.Transition.Allowed).state)
        assertNull(t.note)
    }

    @Test
    fun `a failed move back keeps waiting and carries a note`() {
        val t = HandoffStateMachine.transition(State.WAITING, Action.MOVE_BACK_DONE, moveBackOk = false)
        val allowed = t as HandoffStateMachine.Transition.Allowed
        assertEquals(State.WAITING, allowed.state)
        assertTrue(allowed.note!!.contains("failed"))
    }

    @Test
    fun `keep and abort resolve from waiting`() {
        assertEquals(
            State.KEPT,
            (HandoffStateMachine.transition(State.WAITING, Action.KEEP) as HandoffStateMachine.Transition.Allowed).state,
        )
        assertEquals(
            State.ABORTED,
            (HandoffStateMachine.transition(State.WAITING, Action.ABORT) as HandoffStateMachine.Transition.Allowed).state,
        )
    }

    @Test
    fun `a second start while waiting is rejected`() {
        val t = HandoffStateMachine.transition(State.WAITING, Action.START)
        assertTrue(t is HandoffStateMachine.Transition.Rejected)
        assertTrue((t as HandoffStateMachine.Transition.Rejected).reason.contains("already"))
    }

    @Test
    fun `resolution actions without a waiting handoff are rejected`() {
        for (state in listOf(State.IDLE, State.RETURNED, State.KEPT, State.ABORTED)) {
            for (action in listOf(Action.MOVE_BACK_DONE, Action.KEEP, Action.ABORT)) {
                val t = HandoffStateMachine.transition(state, action)
                assertTrue("state=$state action=$action must reject", t is HandoffStateMachine.Transition.Rejected)
            }
        }
    }

    @Test
    fun `a new handoff can start from any terminal state`() {
        for (state in listOf(State.RETURNED, State.KEPT, State.ABORTED)) {
            val t = HandoffStateMachine.transition(state, Action.START)
            assertEquals(State.WAITING, (t as HandoffStateMachine.Transition.Allowed).state)
        }
    }

    @Test
    fun `guidance text tells the model what each state means`() {
        assertNull(HandoffStateMachine.guidance(State.IDLE, "com.a", "login"))
        assertTrue(HandoffStateMachine.guidance(State.WAITING, "com.a", "login")!!.contains("WAITING"))
        assertTrue(HandoffStateMachine.guidance(State.RETURNED, "com.a", "login")!!.contains("re-read"))
        assertTrue(HandoffStateMachine.guidance(State.KEPT, "com.a", "login")!!.contains("do not operate"))
        assertTrue(HandoffStateMachine.guidance(State.ABORTED, "com.a", "login")!!.contains("declined"))
    }

    @Test
    fun `parseKind accepts the wire kinds defaults to other and rejects junk`() {
        assertEquals("other", HandoffStateMachine.parseKind(null))
        assertEquals("other", HandoffStateMachine.parseKind(""))
        assertEquals("login", HandoffStateMachine.parseKind("LOGIN"))
        assertEquals("secure_page", HandoffStateMachine.parseKind(" secure_page "))
        assertEquals("captcha", HandoffStateMachine.parseKind("captcha"))
        assertNull(HandoffStateMachine.parseKind("root_my_phone"))
    }
}
