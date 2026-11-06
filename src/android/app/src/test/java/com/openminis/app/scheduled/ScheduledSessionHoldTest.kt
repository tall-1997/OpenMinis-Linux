package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-active-session-android, port upstream 1.16 ScheduledSessionHold]
 * Pure contract of the hold derivation: which chats a scheduled task keeps
 * ACTIVE between its fires, and how an in-flight fire extends that hold.
 *
 * Everything under test is a pure function of (tasks, runningIds, now), so a
 * whole fire sequence replays without Android.
 */
class ScheduledSessionHoldTest {

    private val now: Long = 1_700_000_000_000L

    /** A DAILY 09:00 task — with [enabled]=false for the disabled cases. */
    private fun daily(
        id: String,
        target: ScheduledTargetMode,
        enabled: Boolean = true,
    ) = ScheduledTask(
        id = id,
        label = id,
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "p",
        targetMode = target,
        enabled = enabled,
    )

    private val appendTarget = ScheduledTargetMode.AppendToSession("sess-1")

    // ── heldSession ─────────────────────────────────────────────────

    @Test
    fun `append task with a pending occurrence holds its target chat`() {
        assertEquals("sess-1", ScheduledSessionHold.heldSession(daily("t1", appendTarget), now))
    }

    @Test
    fun `rerun task also holds its target chat`() {
        val task = daily("t2", ScheduledTargetMode.RerunMessage("sess-2", "m1"))
        assertEquals("sess-2", ScheduledSessionHold.heldSession(task, now))
    }

    @Test
    fun `new-session task holds nothing before its first fire`() {
        assertNull(ScheduledSessionHold.heldSession(daily("t3", ScheduledTargetMode.NewSession), now))
    }

    @Test
    fun `disabled task holds nothing`() {
        assertNull(ScheduledSessionHold.heldSession(daily("t4", appendTarget, enabled = false), now))
    }

    @Test
    fun `exhausted task (no next occurrence) holds nothing`() {
        // A ONCE task whose fireAt is in the past has no pending slot.
        val spent = ScheduledTask(
            id = "t5",
            label = "t5",
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            fireAtMs = now - 10_000,
            lastFiredAt = now - 5_000,
            prompt = "p",
            targetMode = appendTarget,
        )
        assertNull(ScheduledSessionHold.heldSession(spent, now))
        assertFalse(ScheduledSessionHold.isLive(spent, now))
    }

    // ── heldNow: schedule holds + in-flight run holds ───────────────

    @Test
    fun `heldNow unions the scheduled holds with the running holds`() {
        val tasks = listOf(
            daily("sched", appendTarget),                        // holds sess-1
            daily("running", ScheduledTargetMode.AppendToSession("sess-2")),
            daily("off", ScheduledTargetMode.AppendToSession("sess-3"), enabled = false),
        )
        // "running" is mid-fire (its alarm already disabled it — the task is
        // disabled but the run still holds its chat).
        val running = setOf("running")
        val tasksMidFire = tasks.map { if (it.id == "running") it.copy(enabled = false) else it }
        assertEquals(
            setOf("sess-1", "sess-2"),
            ScheduledSessionHold.heldNow(tasksMidFire, running, now),
        )
        // Without a run in flight the disabled task holds nothing.
        assertEquals(
            setOf("sess-1"),
            ScheduledSessionHold.heldNow(tasksMidFire, emptySet(), now),
        )
    }

    // ── run bookkeeping ─────────────────────────────────────────────

    @Test
    fun `beginRun and endRun count per task`() {
        assertEquals(emptyMap<String, Int>(), ScheduledSessionHold.runningTaskIds.value)
        ScheduledSessionHold.beginRun("a")
        ScheduledSessionHold.beginRun("a")
        ScheduledSessionHold.beginRun("b")
        assertEquals(mapOf("a" to 2, "b" to 1), ScheduledSessionHold.runningTaskIds.value)
        ScheduledSessionHold.endRun("a")
        assertEquals(mapOf("a" to 1, "b" to 1), ScheduledSessionHold.runningTaskIds.value)
        ScheduledSessionHold.endRun("a")
        ScheduledSessionHold.endRun("b")
        assertEquals(emptyMap<String, Int>(), ScheduledSessionHold.runningTaskIds.value)
    }

    @Test
    fun `endRun of an unknown task is a no-op`() {
        ScheduledSessionHold.endRun("ghost")
        assertEquals(emptyMap<String, Int>(), ScheduledSessionHold.runningTaskIds.value)
    }

    @Test
    fun `isLive mirrors enabled with a pending slot`() {
        assertTrue(ScheduledSessionHold.isLive(daily("live", appendTarget), now))
        assertFalse(ScheduledSessionHold.isLive(daily("dead", appendTarget, enabled = false), now))
    }
}
