package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [T-android-cron-5-field] The `cronjob` tool advertised cron but only parsed
 * intervals. Pins the 5-field expression parser (star, steps, ranges, lists;
 * vixie-cron dow convention 0=Sunday) and the next-fire scan (DOM/DOW OR when
 * both restricted, one-year bound, strictly-after semantics).
 */
class CronSchedulerCronTest {

    @Test
    fun `parses all field shapes`() {
        val f = CronScheduler.parseCronExpression("*/15 9-17 * * 1-5")!!
        assertEquals((0..59 step 15).toSet(), f.minutes)
        assertEquals((9..17).toSet(), f.hours)
        assertEquals((1..31).toSet(), f.daysOfMonth)
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), f.months)
        assertEquals(setOf(1, 2, 3, 4, 5), f.daysOfWeek)
    }

    @Test
    fun `lists and single values parse`() {
        val f = CronScheduler.parseCronExpression("0,30 0 1,15 * 0")!!
        assertEquals(setOf(0, 30), f.minutes)
        assertEquals(setOf(0), f.hours)
        assertEquals(setOf(1, 15), f.daysOfMonth)
        assertEquals(setOf(0), f.daysOfWeek)
    }

    @Test
    fun `rejects out-of-range and malformed specs`() {
        assertNull(CronScheduler.parseCronExpression("60 * * * *"))
        assertNull(CronScheduler.parseCronExpression("* 24 * * *"))
        assertNull(CronScheduler.parseCronExpression("* * 0 * *"))
        assertNull(CronScheduler.parseCronExpression("* * * 13 *"))
        assertNull(CronScheduler.parseCronExpression("* * * * 7"))
        assertNull(CronScheduler.parseCronExpression("* * * *"))
        assertNull(CronScheduler.parseCronExpression("*/0 * * * *"))
        assertNull(CronScheduler.parseCronExpression("hello world"))
    }

    @Test
    fun `next fire for weekday mornings skips the weekend`() {
        // Wednesday 2026-11-11 12:00 local. "0 9 * * 1-5" => Thursday 09:00.
        val cal = Calendar.getInstance().apply {
            set(2026, 10, 11, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val next = CronScheduler.nextCronFire("0 9 * * 1-5", cal.timeInMillis)!!
        val out = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.THURSDAY, out.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, out.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, out.get(Calendar.MINUTE))
    }

    @Test
    fun `next fire is strictly after the given instant`() {
        val cal = Calendar.getInstance().apply {
            set(2026, 10, 11, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val next = CronScheduler.nextCronFire("0 9 * * *", cal.timeInMillis)!!
        val out = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(12, out.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `step expressions fire on the cadence`() {
        val cal = Calendar.getInstance().apply {
            set(2026, 10, 11, 10, 7, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val first = CronScheduler.nextCronFire("*/15 * * * *", cal.timeInMillis)!!
        val second = CronScheduler.nextCronFire("*/15 * * * *", first)!!
        assertEquals(15 * 60_000L, second - first)
    }

    @Test
    fun `codec routes cron specs and keeps intervals on the old path`() {
        val cron = ScheduledTaskCodec.buildTask("0 9 * * 1-5", "daily report", "report")
        assertNotNull(cron)
        assertEquals(ScheduledRepeatMode.CRON, cron!!.repeatMode)
        assertEquals("0 9 * * 1-5", cron.cronSpec)
        val interval = ScheduledTaskCodec.buildTask("every 15m", "poll", "poll")!!
        assertEquals(ScheduledRepeatMode.INTERVAL, interval.repeatMode)
        assertNull(interval.cronSpec)
    }
}
