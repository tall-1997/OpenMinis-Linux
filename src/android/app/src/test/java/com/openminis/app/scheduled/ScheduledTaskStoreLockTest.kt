package com.openminis.app.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-android-scheduled-fire-claim] The task list is one prefs file, but the
 * store is built per call site: the alarm receiver's manager, the model
 * picker's provider repository and the session list drawer each hold their own
 * instance. Every write is a read-whole-list-modify-write-whole-list cycle, so
 * two instances running one at the same time means whichever writes last
 * erases the other's change — a fire count or a freshly moved anchor silently
 * moving back, and an interval then overshooting its `--count`.
 *
 * The lock therefore has to be process-wide. These tests pin that: a second
 * instance must wait, and the row changes of both must survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScheduledTaskStoreLockTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Same file the store opens; a fresh Application per test does not
        // guarantee a fresh prefs instance on every Robolectric version.
        context.getSharedPreferences("minis_scheduled_tasks_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun task(id: String) = ScheduledTask(
        id = id,
        label = id,
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "p",
        createdAt = 1_800_000_000_000L,
    )

    @Test
    fun `a second store instance waits for the first one's write`() {
        val first = ScheduledTaskStore(context)
        val second = ScheduledTaskStore(context)
        first.upsert(task("one"))
        first.upsert(task("two"))

        val insideWrite = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = Thread {
            first.update("one") { cur ->
                insideWrite.countDown()
                release.await(5, TimeUnit.SECONDS)
                cur.copy(label = "one-edited")
            }
        }
        writer.start()
        assertTrue(insideWrite.await(5, TimeUnit.SECONDS))

        val other = Thread { second.update("two") { it.copy(label = "two-edited") } }
        other.start()
        // The first instance holds the lock mid-cycle: if the lock were per
        // instance this write would already be done.
        Thread.sleep(300)
        assertTrue("second instance must wait for the first instance's write", other.isAlive)

        release.countDown()
        writer.join(5_000)
        other.join(5_000)
        assertFalse(writer.isAlive)
        assertFalse(other.isAlive)

        val stored = ScheduledTaskStore(context)
        assertEquals("one-edited", stored.get("one")?.label)
        assertEquals("two-edited", stored.get("two")?.label)
    }

    @Test
    fun `a duplicated once delivery claims the row only the first time`() {
        val store = ScheduledTaskStore(context)
        store.upsert(task("once"))

        assertNotNull(store.update("once") { ScheduledTaskManager.claimOnceFire(it) })
        assertFalse(store.get("once")!!.enabled)
        // The duplicate delivery of the same alarm must not run the task again.
        assertNull(store.update("once") { ScheduledTaskManager.claimOnceFire(it) })
    }
}
