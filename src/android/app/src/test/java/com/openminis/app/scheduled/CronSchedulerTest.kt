package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun parseAnyDurationUnits() {
        val s = CronScheduler.parseSchedule("90s")!!
        assertEquals("once", s.kind)
        assertEquals(90 * 1000L, s.firstDelayMs)

        val combo = CronScheduler.parseSchedule("1h30m")!!
        assertEquals(90 * 60_000L, combo.firstDelayMs)

        val spaced = CronScheduler.parseSchedule("2 h 15 m")!!
        assertEquals(135 * 60_000L, spaced.firstDelayMs)

        val every = CronScheduler.parseSchedule("every 7m")!!
        assertEquals("interval", every.kind)
        assertEquals(7L, every.intervalMinutes)
        assertEquals(7 * 60_000L, every.firstDelayMs)

        val sub = CronScheduler.parseSchedule("every 90s")!!
        assertEquals(2L, sub.intervalMinutes) // sub-minute cadence rounds up
    }

    @Test
    fun parseRejectsNonChunkSuffixes() {
        assertNull(CronScheduler.parseSchedule("every 30m later"))
        assertNull(CronScheduler.parseSchedule("1h x"))
        assertNull(CronScheduler.parseSchedule("h1"))
        assertNull(CronScheduler.parseSchedule("99999999999999999999m"))
        assertNull(CronScheduler.parseSchedule("0s"))
    }

    @Test
    fun intervalNextTriggerWalksForward() {
        val now = 1_700_000_000_000L
        val task = ScheduledTask(
            id = "t1",
            label = "tick",
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.INTERVAL,
            prompt = "ping",
            createdAt = now,
            intervalMinutes = 30,
            fireAtMs = now + 30 * 60_000L,
        )
        assertEquals(now + 30 * 60_000L, task.nextTriggerMs(now))
        val later = now + 90 * 60_000L
        val next = task.nextTriggerMs(later)!!
        assertTrue(next > later)
        assertEquals(0L, (next - task.fireAtMs!!) % (30 * 60_000L))
    }

    @Test
    fun onceDelayDoesNotRepeatAfterFire() {
        val now = 1_700_000_000_000L
        val task = ScheduledTask(
            id = "t2",
            label = "once",
            timeOfDayHour = 9,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = "once",
            lastFiredAt = now,
            fireAtMs = now - 1000,
        )
        assertNull(task.nextTriggerMs(now))
    }
}
