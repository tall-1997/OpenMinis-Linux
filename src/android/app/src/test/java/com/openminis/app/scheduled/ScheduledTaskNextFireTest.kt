package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Next-fire computation and the card view-state derivation for the exact
 * branches the task list renders wrong when they regress: a fired-out ONCE
 * task and a repeating task past its end date used to show a bare schedule
 * with an empty next-fire segment (and the run count was invisible).
 */
class ScheduledTaskNextFireTest {

    private fun onceTask(
        hour: Int = 9,
        minute: Int = 0,
        enabled: Boolean = true,
        lastFiredAt: Long? = null,
        endDateMs: Long? = null,
    ) = ScheduledTask(
        id = "t-once",
        label = "once",
        timeOfDayHour = hour,
        timeOfDayMinute = minute,
        repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "p",
        enabled = enabled,
        lastFiredAt = lastFiredAt,
        endDateMs = endDateMs,
    )

    private fun dailyTask(
        enabled: Boolean = true,
        endDateMs: Long? = null,
        hour: Int = 9,
        minute: Int = 0,
    ) = ScheduledTask(
        id = "t-daily",
        label = "daily",
        timeOfDayHour = hour,
        timeOfDayMinute = minute,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "p",
        enabled = enabled,
        endDateMs = endDateMs,
    )

    /** Local start-of-day for the calendar day that [ms] falls in. */
    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun addDays(ms: Long, days: Int): Long = ms + days * 86_400_000L

    @Test
    fun firedOnceTaskHasNoNextTrigger() {
        // A ONCE task that fired is disabled in place by the manager; the
        // next-fire display must be empty, never "tomorrow".
        val firedAt = 1_700_000_000_000L
        val task = onceTask(enabled = false, lastFiredAt = firedAt)
        assertNull(task.nextTriggerMs(firedAt + 60_000L))
    }

    @Test
    fun unfiredOnceTaskStillHasNextTrigger() {
        val now = 1_700_000_000_000L
        val task = onceTask(hour = 23, minute = 59, enabled = true)
        val next = task.nextTriggerMs(now)!!
        assertTrue(next > now)
        // Today at 23:59 (or, if that already passed, tomorrow at 23:59).
        assertTrue(next - startOfDay(now) == (23 * 60L + 59) * 60_000L ||
            next - startOfDay(now) == 86_400_000L + (23 * 60L + 59) * 60_000L)
    }

    @Test
    fun dailyPastEndDateHasNoNextTrigger() {
        val now = 1_700_000_000_000L
        val task = dailyTask(endDateMs = startOfDay(now) - 86_400_000L)
        assertNull(task.nextTriggerMs(now))
    }

    @Test
    fun dailyWithinEndDateStillFires() {
        val now = 1_700_000_000_000L
        val task = dailyTask(endDateMs = addDays(startOfDay(now), 30))
        assertTrue(task.nextTriggerMs(now) != null)
    }

    @Test
    fun dailyEndOfDayBoundaryIsInclusive() {
        // endDateMs is the START of the last allowed day; the task may still
        // fire on that day (until end-of-day), only the day after kills it.
        val now = 1_700_000_000_000L
        val lastDay = startOfDay(now) - 86_400_000L // yesterday
        val task = dailyTask(hour = 23, minute = 50, endDateMs = lastDay)
        // Last allowed fire was yesterday 23:50 → nothing left today.
        assertNull(task.nextTriggerMs(now))
    }

    @Test
    fun disabledTaskHasNoNextTrigger() {
        assertNull(dailyTask(enabled = false).nextTriggerMs())
    }

    @Test
    fun customWithEmptyDaySetHasNoNextTrigger() {
        val task = ScheduledTask(
            id = "t-custom",
            label = "custom",
            timeOfDayHour = 9,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.CUSTOM,
            prompt = "p",
            customDays = emptySet(),
        )
        assertNull(task.nextTriggerMs())
    }

    @Test
    fun viewStateActiveTaskShowsNextFire() {
        val now = 1_700_000_000_000L
        val task = dailyTask().copy(runHistory = listOf(ScheduledRun(now - 1, "s", null, true)))
        val s = task.viewState(now)
        assertEquals(1, s.runCount)
        assertTrue(s.nextFireMs != null)
        assertFalse(s.ended)
        assertFalse(s.paused)
        assertTrue(s.isActive)
    }

    @Test
    fun viewStateFiredOnceIsEndedNotPaused() {
        val firedAt = 1_700_000_000_000L
        val task = onceTask(enabled = false, lastFiredAt = firedAt)
            .copy(runHistory = listOf(ScheduledRun(firedAt, "s", null, true)))
        val s = task.viewState(firedAt + 60_000L)
        assertNull(s.nextFireMs)
        assertTrue(s.ended)
        assertFalse(s.paused)
        assertEquals(1, s.runCount)
    }

    @Test
    fun viewStateDisabledWithoutFireIsPaused() {
        val s = dailyTask(enabled = false).viewState()
        assertNull(s.nextFireMs)
        assertFalse(s.ended)
        assertTrue(s.paused)
        assertEquals(0, s.runCount)
    }

    @Test
    fun viewStateEnabledPastWindowIsEnded() {
        // Active per the toggle, but the window closed: the card must show
        // the terminal badge instead of an empty next segment.
        val now = 1_700_000_000_000L
        val task = dailyTask(enabled = true, endDateMs = startOfDay(now) - 86_400_000L)
        val s = task.viewState(now)
        assertNull(s.nextFireMs)
        assertTrue(s.ended)
        assertFalse(s.paused)
    }

    @Test
    fun viewStateEnabledCustomEmptyIsEnded() {
        val task = ScheduledTask(
            id = "t-custom",
            label = "custom",
            timeOfDayHour = 9,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.CUSTOM,
            prompt = "p",
            customDays = emptySet(),
        )
        val s = task.viewState()
        assertTrue(s.ended)
        assertFalse(s.paused)
    }
}
