package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CronSchedulerTest {

    @Test
    fun parseOnceMinutesHoursDays() {
        val m = CronScheduler.parseSchedule("30m")!!
        assertEquals("once", m.kind)
        assertEquals(0L, m.intervalMinutes)
        assertEquals(30 * 60_000L, m.firstDelayMs)

        val h = CronScheduler.parseSchedule("2h")!!
        assertEquals("once", h.kind)
        assertEquals(2 * 60 * 60_000L, h.firstDelayMs)

        val d = CronScheduler.parseSchedule("1d")!!
        assertEquals(1440 * 60_000L, d.firstDelayMs)
    }

    @Test
    fun parseEveryInterval() {
        val p = CronScheduler.parseSchedule("every 2h")!!
        assertEquals("interval", p.kind)
        assertEquals(120L, p.intervalMinutes)
        assertEquals(120 * 60_000L, p.firstDelayMs)
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(CronScheduler.parseSchedule(""))
        assertNull(CronScheduler.parseSchedule("cron"))
        assertNull(CronScheduler.parseSchedule("every"))
        assertNull(CronScheduler.parseSchedule("0m"))
    }

    @Test
    fun intervalNextTriggerCountsFromItsAnchor() {
        val now = 1_700_000_000_000L
        val task = ScheduledTask(
            id = "t1",
            label = "tick",
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            // [T-android-scheduled-triggers] A repeat-every-N is an INTERVAL
            // trigger now; repeatMode only ever described calendar rows.
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = "ping",
            createdAt = now,
            triggerKind = ScheduledTriggerKind.INTERVAL,
            intervalSec = 30 * 60L,
            anchorMs = now,
            triggeredCount = 0,
        )
        assertEquals(now + 30 * 60_000L, task.nextTriggerMs(now))
        // Overdue (phone off, process dead) answers `now`: the alarm fires once,
        // late, and the fire re-anchors the countdown.
        val later = now + 90 * 60_000L
        assertEquals(later, task.nextTriggerMs(later))
    }

    @Test
    fun afterDelayNeverRepeatsOnceFired() {
        val now = 1_700_000_000_000L
        val task = ScheduledTask(
            id = "t2",
            label = "once",
            timeOfDayHour = 9,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = "once",
            createdAt = now,
            triggerKind = ScheduledTriggerKind.AFTER,
            delaySec = 1800,
            anchorMs = now,
            triggeredCount = 0,
        )
        assertEquals(now + 1_800_000L, task.nextTriggerMs(now))
        assertNull(task.copy(triggeredCount = 1).nextTriggerMs(now))
    }
}
