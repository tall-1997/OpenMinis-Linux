package com.openminis.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [T-android-automation-rules] Next-fire computation for Time triggers, the
 * scheduling contract shared with the scheduled-task alarms: wall-clock local
 * time, ISO day filters, interval anchoring to the last fire (staggering a
 * missed run instead of stacking), and one-shot semantics.
 */
class RuleNextFireTest {

    /** 2024-01-01 was a Monday. */
    private val monNoon: Long = run {
        Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun at(hour: Int, minute: Int, dayOffset: Int = 0): Long =
        Calendar.getInstance().apply {
            timeInMillis = monNoon
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun dailySlotLaterTodayWins() {
        val t = Trigger.Time(at = "18:00")
        assertEquals(at(18, 0), t.nextTriggerMs(nowMs = monNoon, anchorMs = 0))
    }

    @Test
    fun dailySlotPastTodayRollsToTomorrow() {
        val t = Trigger.Time(at = "09:00")
        assertEquals(at(9, 0, dayOffset = 1), t.nextTriggerMs(monNoon, 0))
    }

    @Test
    fun daysFilterSkipsNonMatchingDays() {
        // Mon=1: only Tuesday+Friday → from Monday noon the next slot is
        // Tuesday 18:00 (today is Monday, not in the set).
        val t = Trigger.Time(at = "18:00", days = listOf(2, 5))
        assertEquals(at(18, 0, dayOffset = 1), t.nextTriggerMs(monNoon, 0))
    }

    @Test
    fun malformedTimeYieldsNull() {
        assertNull(Trigger.Time(at = "25:00").nextTriggerMs(monNoon, 0))
        assertNull(Trigger.Time(at = "abc").nextTriggerMs(monNoon, 0))
        assertNull(Trigger.Time(at = "7:5").nextTriggerMs(monNoon, 0))
    }

    @Test
    fun intervalAnchorsToLastFired() {
        val t = Trigger.Time(everyMinutes = 30)
        val anchor = monNoon - 10 * 60_000L
        assertEquals(anchor + 30 * 60_000L, t.nextTriggerMs(monNoon, anchor))
    }

    @Test
    fun intervalMissedByMoreThanOnePeriodStaggersToOneMinuteOut() {
        val t = Trigger.Time(everyMinutes = 15)
        val anchor = monNoon - 3 * 60 * 60_000L
        assertEquals(monNoon + 60_000L, t.nextTriggerMs(monNoon, anchor))
    }

    @Test
    fun oneShotInFutureKeptInPastDropped() {
        val t = Trigger.Time(onceAtEpochMs = monNoon + 5_000)
        assertEquals(monNoon + 5_000, t.nextTriggerMs(monNoon, 0))
        assertNull(Trigger.Time(onceAtEpochMs = monNoon - 5_000).nextTriggerMs(monNoon, 0))
    }

    @Test
    fun wallClockParserIsLenientAboutTheHour() {
        assertEquals(7 to 5, parseWallClock("7:05"))
        assertEquals(7 to 30, parseWallClock("07:30"))
        assertEquals(23 to 59, parseWallClock("23:59"))
        assertNull(parseWallClock("24:00"))
        assertNull(parseWallClock("07:60"))
        assertNull(parseWallClock("7:5")) // minutes must be two digits
        assertEquals("07:05", formatWallClock(7, 5))
    }
}
