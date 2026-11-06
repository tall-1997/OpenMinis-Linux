package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [T-sched-inapp-timer, port upstream 1.16 ScheduledInAppTimer] Pure planning
 * contract of the in-app firer: given the persisted task list, which run fires
 * next, at what time, which attempts are already spent — and how the run-now
 * claim records its slot so the timer and the alarm cannot both take a run.
 *
 * All times are built from LOCAL start-of-day, so the tests are timezone
 * independent (same technique as ScheduledTaskNextFireTest).
 */
class ScheduledInAppTimerTest {

    /** Local 10:00 of "today" — before 23:00, after 09:00. */
    private val now: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** A DAILY task at [hour]:00 local. */
    private fun daily(id: String, hour: Int, enabled: Boolean = true) = ScheduledTask(
        id = id,
        label = id,
        timeOfDayHour = hour,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "p",
        enabled = enabled,
    )

    // ── nextDue planning ────────────────────────────────────────────

    @Test
    fun `nextDue picks the soonest slot`() {
        // At local 10:00 the 23:00 slot (13h away) is nearer than tomorrow's
        // 09:00 (23h away).
        val due = ScheduledInAppTimer.nextDue(
            listOf(daily("morning", hour = 9), daily("evening", hour = 23)),
            now,
            emptySet(),
        )
        assertEquals("evening", due?.task?.id)
        assertTrue("due must be in the future", due != null && due.at > now)
    }

    @Test
    fun `nextDue skips disabled tasks`() {
        assertNull(
            ScheduledInAppTimer.nextDue(
                listOf(daily("off", hour = 23, enabled = false)),
                now,
                emptySet(),
            ),
        )
    }

    @Test
    fun `nextDue skips already-tried run keys`() {
        val tasks = listOf(daily("evening", hour = 23))
        val first = ScheduledInAppTimer.nextDue(tasks, now, emptySet())!!
        // This (task, runKey) pair was already refused or fired: the planner
        // must not hand it out again (it would spin on a run it cannot start).
        assertNull(ScheduledInAppTimer.nextDue(tasks, now, setOf(first.tryKey)))
    }

    @Test
    fun `pendingRunKey names the slot it guards`() {
        val task = daily("keyed", hour = 23)
        val slot = task.nextTriggerMs(now)!!
        assertEquals("slot:$slot", ScheduledTaskManager.pendingRunKey(task, now))
        assertNull(ScheduledTaskManager.pendingRunKey(task.copy(enabled = false), now))
    }

    // ── run-now claim transition (the double-take guard) ────────────

    @Test
    fun `claimed once task is disabled with its slot recorded`() {
        val task = daily("once", hour = 23).copy(repeatMode = ScheduledRepeatMode.ONCE)
        val claimed = ScheduledTaskManager.runNowTransition(task, now)!!
        assertFalse("fired-out ONCE task must be disabled", claimed.enabled)
        assertEquals(task.nextTriggerMs(now), claimed.runNowSlotMs)
    }

    @Test
    fun `claimed daily task stays enabled and turns its alarm away`() {
        val task = daily("daily", hour = 23)
        val slot = task.nextTriggerMs(now)!!
        val claimed = ScheduledTaskManager.runNowTransition(task, now)!!
        assertTrue(claimed.enabled)
        assertEquals(slot, claimed.runNowSlotMs)
        // The alarm for the taken slot delivers around `slot`; run-now took it.
        assertTrue(ScheduledTaskManager.calendarSlotTakenByRunNow(claimed, slot))
        // An alarm for the NEXT slot (a day later) is not the taken one.
        assertFalse(ScheduledTaskManager.calendarSlotTakenByRunNow(claimed, slot + 86_400_000L))
        // A task never claimed turns nothing away.
        assertFalse(ScheduledTaskManager.calendarSlotTakenByRunNow(task, slot))
    }

    @Test
    fun `claimed interval task re-anchors its schedule at slot plus interval`() {
        val task = daily("iv", hour = 23).copy(
            repeatMode = ScheduledRepeatMode.INTERVAL,
            intervalMinutes = 30,
            fireAtMs = now + 60_000,
        )
        val slot = task.nextTriggerMs(now)!!
        val claimed = ScheduledTaskManager.runNowTransition(task, now)!!
        assertEquals(slot, claimed.runNowSlotMs)
        assertEquals(slot + 30 * 60_000L, claimed.fireAtMs)
    }

    @Test
    fun `runNowTransition refuses disabled and spent tasks`() {
        assertNull(ScheduledTaskManager.runNowTransition(daily("off", hour = 23, enabled = false), now))
        // A fired ONCE task (lastFiredAt set) has no pending slot to take.
        val spent = daily("spent", hour = 23).copy(
            repeatMode = ScheduledRepeatMode.ONCE,
            fireAtMs = now - 60_000,
            lastFiredAt = now - 30_000,
        )
        assertNull(ScheduledTaskManager.runNowTransition(spent, now))
    }
}
