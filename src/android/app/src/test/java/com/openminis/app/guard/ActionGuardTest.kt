package com.openminis.app.guard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActionGuardTest {

    private val realRandom = ActionGuard.randomSource
    private val realSleeper = ActionGuard.sleeper
    private val realClock = ActionGuard.clock

    private var nowMs = 0L
    private val slept = mutableListOf<Long>()

    @Before
    fun setUp() {
        ActionGuard.resetAll()
        nowMs = 1_000_000L
        slept.clear()
        ActionGuard.clock = { nowMs }
        ActionGuard.randomSource = { lo, _ -> lo } // always the lower bound: deterministic gaps
        ActionGuard.sleeper = { slept.add(it) }
    }

    @After
    fun tearDown() {
        ActionGuard.randomSource = realRandom
        ActionGuard.sleeper = realSleeper
        ActionGuard.clock = realClock
        ActionGuard.resetAll()
    }

    @Test
    fun firstOutwardCallProceedsThenSecondWaitsThroughTheInjectedSleeper() {
        val first = ActionGuard.beforeCall("s1", "ui_action", """{"text":"send it","package":"com.x"}""")
        assertEquals(GuardVerdict.Proceed, first)
        ActionGuard.afterCall("s1", "ui_action", """{"text":"send it","package":"com.x"}""", ok = true)

        val second = ActionGuard.beforeCall("s1", "ui_action", """{"text":"send it again","package":"com.x"}""")
        assertTrue(second is GuardVerdict.Wait)
        val wait = second as GuardVerdict.Wait
        assertEquals(8_000L, wait.millis)
        assertEquals(listOf(8_000L), slept) // the facade itself slept, via the injected sleeper
        assertTrue(wait.notice.contains("Waited 8s"))
    }

    @Test
    fun pacingCreditsTimeAlreadySpentSinceTheLastOutwardAction() {
        val args = """{"text":"send","package":"com.x"}"""
        ActionGuard.afterCall("s1", "ui_action", args, ok = true)
        nowMs += 6_000L // six seconds already passed since the send
        val verdict = ActionGuard.beforeCall("s1", "ui_action", args)
        assertEquals(2_000L, (verdict as GuardVerdict.Wait).millis) // 8000 - 6000
    }

    @Test
    fun plainActionsNeverWait() {
        ActionGuard.afterCall("s1", "ui_action", """{"text":"send"}""", ok = true)
        assertEquals(GuardVerdict.Proceed, ActionGuard.beforeCall("s1", "ui_action", """{"target":"btn"}"""))
        assertEquals(0, slept.size)
    }

    @Test
    fun remindersAreReturnedFromAfterCallAtTheRepeatRungs() {
        val args = """{"target":"btn"}"""
        assertNull(ActionGuard.afterCall("s1", "ui_action", args, ok = true))
        assertNull(ActionGuard.afterCall("s1", "ui_action", args, ok = true))
        val third = ActionGuard.afterCall("s1", "ui_action", args, ok = true)
        assertTrue(third!!.contains("Notice"))
        assertTrue(third.contains("repeated 3 times"))
        assertNull(ActionGuard.afterCall("s1", "ui_action", args, ok = true))
        assertNull(ActionGuard.afterCall("s1", "ui_action", args, ok = true))
        val sixth = ActionGuard.afterCall("s1", "ui_action", args, ok = true)
        assertTrue(sixth!!.contains("repeated 6 times"))
    }

    @Test
    fun twelfthIdenticalCallIsRefusedBeforeExecution() {
        val args = """{"target":"btn"}"""
        repeat(11) { ActionGuard.afterCall("s1", "ui_action", args, ok = true) }
        val verdict = ActionGuard.beforeCall("s1", "ui_action", args)
        val reason = (verdict as GuardVerdict.Refuse).reason
        assertTrue(reason.contains("Refused"))
        assertTrue(reason.contains("12 times"))
    }

    @Test
    fun loopRefusalWinsOverThePacingSleep() {
        val args = """{"text":"发送"}"""
        repeat(11) { ActionGuard.afterCall("s1", "ui_action", args, ok = true) }
        val verdict = ActionGuard.beforeCall("s1", "ui_action", args) // would be the 12th identical call
        assertTrue(verdict is GuardVerdict.Refuse)
        assertEquals(0, slept.size) // never sleeps for a call it refuses
    }

    @Test
    fun noEffectStopsAt12ThenRefusesEverything() {
        var last: String? = null
        for (i in 1..12) {
            val args = """{"x":${i * 100}}""" // distinct targets, so Repeat never fires
            assertEquals(GuardVerdict.Proceed, ActionGuard.beforeCall("s1", "ui_action", args))
            last = ActionGuard.afterCall("s1", "ui_action", args, ok = true, noEffect = true)
        }
        assertTrue(last!!.contains("Refused"))
        assertTrue(last.contains("12 actions in a row"))
        assertTrue(ActionGuard.beforeCall("s1", "ui_action", """{"x":1300}""") is GuardVerdict.Refuse)
    }

    @Test
    fun rereadStopsAt12AndThenRefusesReadsToo() {
        var last: String? = null
        repeat(12) { last = ActionGuard.afterCall("s1", "ui_read", "{}", ok = true, unchanged = true) }
        assertTrue(last!!.contains("Refused"))
        assertTrue(last.contains("12 times"))
        // a reread stop refuses reads as well: re-reading was the loop
        assertTrue(ActionGuard.beforeCall("s1", "ui_read", "{}") is GuardVerdict.Refuse)
    }

    @Test
    fun failureStopsAt8ThenRefusesEverything() {
        var last: String? = null
        for (i in 1..8) {
            val args = """{"x":${i * 50}}"""
            last = ActionGuard.afterCall("s1", "ui_action", args, ok = false)
        }
        assertTrue(last!!.contains("Refused"))
        assertTrue(last.contains("8 calls in a row failed"))
        assertTrue(ActionGuard.beforeCall("s1", "ui_action", """{"x":500}""") is GuardVerdict.Refuse)
    }

    @Test
    fun unchangedScreenOnAnActionCountsAsNoEffect() {
        val first = ActionGuard.afterCall("s1", "ui_action", """{"x":10}""", ok = true, unchanged = true)
        assertNull(first)
        val second = ActionGuard.afterCall("s1", "ui_action", """{"x":20}""", ok = true, unchanged = true)
        assertNull(second)
        val third = ActionGuard.afterCall("s1", "ui_action", """{"x":30}""", ok = true, unchanged = true)
        assertTrue(third!!.contains("3 actions in a row"))
    }

    @Test
    fun onUserTurnClearsTheWholeSessionState() {
        val args = """{"target":"btn"}"""
        repeat(11) { ActionGuard.afterCall("s1", "ui_action", args, ok = true) }
        assertTrue(ActionGuard.beforeCall("s1", "ui_action", args) is GuardVerdict.Refuse)
        ActionGuard.onUserTurn("s1")
        assertEquals(GuardVerdict.Proceed, ActionGuard.beforeCall("s1", "ui_action", args))
        assertNull(ActionGuard.afterCall("s1", "ui_action", args, ok = true))
        assertNull(ActionGuard.afterCall("s1", "ui_action", args, ok = true))
        assertTrue(ActionGuard.afterCall("s1", "ui_action", args, ok = true)!!.contains("repeated 3 times"))
    }

    @Test
    fun stoppedSessionStillLetsReadsThroughWithANotice() {
        val args = """{"target":"btn"}"""
        repeat(11) { ActionGuard.afterCall("s1", "ui_action", args, ok = true) }
        assertTrue(ActionGuard.beforeCall("s1", "ui_action", args) is GuardVerdict.Refuse) // arms the stop
        val readVerdict = ActionGuard.beforeCall("s1", "ui_read", "{}")
        assertTrue(readVerdict is GuardVerdict.ProceedWithNotice)
        // but an outward action is refused, and refused calls never sleep
        assertTrue(ActionGuard.beforeCall("s1", "ui_action", """{"text":"发送"}""") is GuardVerdict.Refuse)
        assertEquals(0, slept.size)
    }

    @Test
    fun afterCallIsIgnoredWhileTheSessionIsStopped() {
        val args = """{"target":"btn"}"""
        repeat(11) { ActionGuard.afterCall("s1", "ui_action", args, ok = true) }
        assertTrue(ActionGuard.beforeCall("s1", "ui_action", args) is GuardVerdict.Refuse) // arms the stop
        // the host may still report the (not executed) call; nothing may be counted or appended
        assertNull(ActionGuard.afterCall("s1", "ui_action", args, ok = false))
        assertNull(ActionGuard.afterCall("s1", "ui_action", args, ok = true))
    }

    @Test
    fun signaturesIgnoreArgumentOrderAndVolatileKeys() {
        val a = ActionGuard.signature("ui_action", """{"x":10,"y":20,"durationMs":300}""")
        val b = ActionGuard.signature("ui_action", """{"durationMs":999,"y":20,"x":10}""")
        assertEquals(a, b)
        val c = ActionGuard.signature("ui_action", """{"x":10,"y":20,"timeoutMs":5000}""")
        assertEquals(a, c)
    }

    @Test
    fun signaturesSnapCoordinatesToThe8pxGrid() {
        val base = ActionGuard.signature("ui_action", """{"x":100,"y":200}""")
        assertEquals(base, ActionGuard.signature("ui_action", """{"x":103,"y":203}"""))
        assertFalse(base == ActionGuard.signature("ui_action", """{"x":120,"y":200}"""))
    }

    @Test
    fun signaturesRoundCoordinatePairArrays() {
        val a = ActionGuard.signature("ui_action", """{"point":[100,200]}""")
        assertEquals(a, ActionGuard.signature("ui_action", """{"point":[102,196]}"""))
        assertFalse(a == ActionGuard.signature("ui_action", """{"point":[120,200]}"""))
    }

    @Test
    fun readsHaveNoRepeatSignature() {
        assertNull(ActionGuard.signature("ui_read", """{"x":1}"""))
        assertTrue(ActionGuard.isRead("ui_read"))
        assertFalse(ActionGuard.isRead("ui_action"))
        assertTrue(ActionGuard.isOutward("send_message", "{}"))
    }

    @Test
    fun blankSessionIdsShareTheDefaultSession() {
        ActionGuard.afterCall(null, "ui_action", """{"t":"a"}""", ok = false)
        val second = ActionGuard.afterCall("default", "ui_action", """{"t":"b"}""", ok = false)
        assertTrue(second!!.contains("2 calls in a row failed"))
    }

    @Test
    fun appKeyedPacingIsSharedAcrossSessionsAndSurvivesUserTurns() {
        val args = """{"package":"com.twitter.android","text":"发送"}"""
        ActionGuard.afterCall("s1", "ui_action", args, ok = true)
        // a different session targeting the same app waits too
        assertTrue(ActionGuard.beforeCall("s2", "ui_action", args) is GuardVerdict.Wait)
        ActionGuard.onUserTurn("s1")
        assertTrue(ActionGuard.beforeCall("s2", "ui_action", args) is GuardVerdict.Wait)
    }

    @Test
    fun sessionKeyedPacingIsClearedOnUserTurn() {
        val args = """{"text":"send"}""" // no app in the args: pacing key falls back to the session
        ActionGuard.afterCall("s1", "ui_action", args, ok = true)
        assertTrue(ActionGuard.beforeCall("s1", "ui_action", args) is GuardVerdict.Wait)
        ActionGuard.onUserTurn("s1")
        assertEquals(GuardVerdict.Proceed, ActionGuard.beforeCall("s1", "ui_action", args))
    }

    @Test
    fun malformedArgsJsonIsTreatedAsEmptyAndNeverCrashes() {
        assertEquals(GuardVerdict.Proceed, ActionGuard.beforeCall("s1", "ui_action", "not json"))
        assertNull(ActionGuard.afterCall("s1", "ui_action", "not json", ok = true))
        assertEquals("ui_action()", ActionGuard.signature("ui_action", "not json"))
    }
}
