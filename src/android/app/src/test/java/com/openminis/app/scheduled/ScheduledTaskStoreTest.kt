package com.openminis.app.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-scheduled-new-session-permission] Store-level JSON compatibility: a
 * prefs file written by a pre-feature build (rows without the
 * `newSessionPermissionMode` / `newSessionFolderId` keys) must load with the
 * new fields null, and a row saved by this build must survive a fresh store
 * instance (SharedPreferences reload) with the fields intact.
 *
 * Robolectric (plain Application) so the real SharedPreferences-backed
 * [ScheduledTaskStore] runs, not a mock — same pattern as the rest of the
 * JVM suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScheduledTaskStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun legacyJsonLoadsAsNullNewFields() {
        val store = ScheduledTaskStore(context)
        store.clear()
        // Hand-write the prefs payload exactly as an older build would have
        // left it: no newSession* keys anywhere.
        val legacy = """
            [
              {"id":"legacy-1","label":"old task","hour":7,"minute":15,
               "repeatMode":"DAILY","customDays":"","prompt":"hi",
               "targetMode":"NEW_SESSION","enabled":true,
               "createdAt":1700000000000}
            ]
        """.trimIndent().replace("\n", "")
        context.getSharedPreferences("minis_scheduled_tasks_prefs", Context.MODE_PRIVATE)
            .edit().putString("tasks_json", legacy).commit()

        val loaded = store.all()
        assertEquals(1, loaded.size)
        assertEquals("legacy-1", loaded[0].id)
        assertNull(loaded[0].newSessionPermissionMode)
        assertNull(loaded[0].newSessionFolderId)
        // Defaults resolve at use time, not at parse time.
        assertEquals("ASK", loaded[0].newSessionPermissionToken())
        assertTrue(loaded[0].resolvedNewSessionFolderId(emptySet()) == null)
    }

    @Test
    fun newFieldsSurviveStoreReload() {
        val store = ScheduledTaskStore(context)
        store.clear()
        val task = ScheduledTask(
            id = "new-1",
            label = "gated task",
            timeOfDayHour = 8,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.DAILY,
            prompt = "p",
            newSessionPermissionMode = "YOYO",
            newSessionFolderId = "ws-1",
        )
        store.upsert(task)

        // A second instance re-reads the prefs file — the same state any
        // other process entry point (alarm receiver, CLI) would see.
        val fresh = ScheduledTaskStore(context).get("new-1")!!
        assertEquals("YOYO", fresh.newSessionPermissionMode)
        assertEquals("ws-1", fresh.newSessionFolderId)
    }

    @Test
    fun mixedOldAndNewRowsCoexist() {
        val store = ScheduledTaskStore(context)
        store.clear()
        store.upsert(
            ScheduledTask(
                id = "old-1", label = "old", timeOfDayHour = 1, timeOfDayMinute = 0,
                repeatMode = ScheduledRepeatMode.ONCE, prompt = "p",
            ),
        )
        store.upsert(
            ScheduledTask(
                id = "new-1", label = "new", timeOfDayHour = 2, timeOfDayMinute = 0,
                repeatMode = ScheduledRepeatMode.ONCE, prompt = "p",
                newSessionPermissionMode = "YOYO",
                newSessionFolderId = "ws-9",
            ),
        )
        val fresh = ScheduledTaskStore(context)
        assertNull(fresh.get("old-1")!!.newSessionPermissionMode)
        assertEquals("YOYO", fresh.get("new-1")!!.newSessionPermissionMode)
        assertEquals("ws-9", fresh.get("new-1")!!.newSessionFolderId)
    }
}
