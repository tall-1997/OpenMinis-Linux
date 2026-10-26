package com.openminis.app.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * [T-scheduled-end-window-autodisable] Pins the active-window contract on
 * [ScheduledTaskManager.rescheduleNext]: a repeating task whose end date has
 * fully passed (or whose CUSTOM day set drained) must be disabled in-place
 * instead of staying an enabled row with no alarm and no next occurrence —
 * the exact state that made the task-list card show a live toggle with an
 * empty next-fire segment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScheduledTaskManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: ScheduledTaskManager

    @Before
    fun setUp() {
        ScheduledTaskStore(context).clear()
        manager = ScheduledTaskManager(context)
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun daily(id: String, endDateMs: Long? = null) = ScheduledTask(
        id = id,
        label = id,
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "p",
        endDateMs = endDateMs,
    )

    @Test
    fun pastEndWindowDisablesRepeatingTask() {
        val ended = startOfDay(System.currentTimeMillis()) - 86_400_000L
        manager.create(daily("expired", endDateMs = ended))
        manager.rescheduleNext("expired")
        assertFalse(manager.get("expired")!!.enabled)
    }

    @Test
    fun liveWindowKeepsRepeatingTaskEnabled() {
        manager.create(daily("alive"))
        manager.rescheduleNext("alive")
        assertTrue(manager.get("alive")!!.enabled)
    }

    @Test
    fun customWithEmptyDaySetDisables() {
        val dead = ScheduledTask(
            id = "dead-custom",
            label = "dead",
            timeOfDayHour = 9,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.CUSTOM,
            prompt = "p",
            customDays = emptySet(),
        )
        manager.create(dead)
        manager.rescheduleNext("dead-custom")
        assertFalse(manager.get("dead-custom")!!.enabled)
    }

    @Test
    fun onceTaskStillDisablesAfterFire() {
        manager.create(daily("one-shot").copy(repeatMode = ScheduledRepeatMode.ONCE))
        manager.rescheduleNext("one-shot")
        assertFalse(manager.get("one-shot")!!.enabled)
    }

    @Test
    fun intervalTaskStaysEnabledWithAdvancedFireAt() {
        val before = System.currentTimeMillis()
        val t = ScheduledTask(
            id = "tick",
            label = "tick",
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.INTERVAL,
            prompt = "p",
            intervalMinutes = 30,
            fireAtMs = before,
        )
        manager.create(t)
        manager.rescheduleNext("tick")
        val after = manager.get("tick")!!
        assertTrue(after.enabled)
        assertTrue(after.fireAtMs!! > before)
        assertEquals(30L, after.intervalMinutes)
    }
}
