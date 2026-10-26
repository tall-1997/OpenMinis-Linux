package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

/**
 * [T-scheduled-new-session-permission / T-scheduled-new-session-folder]
 * Store-schema compatibility for the ScheduledTask JSON row.
 *
 * ScheduledTaskStore is a thin SharedPreferences/JSON layer — there is no
 * Room schema here, so "migration" is purely: a row written by an older
 * build (no `newSessionPermissionMode` / `newSessionFolderId` keys) must
 * parse with both fields null and re-serialize without inventing values,
 * while a row written by this build round-trips both fields exactly.
 */
class ScheduledTaskJsonCompatTest {

    private fun legacyJson(): JSONObject = JSONObject().apply {
        put("id", "legacy-1")
        put("label", "Morning brief")
        put("hour", 9)
        put("minute", 30)
        put("repeatMode", "DAILY")
        put("customDays", "")
        put("prompt", "hello")
        put("targetMode", "NEW_SESSION")
        put("enabled", true)
        put("createdAt", 1_700_000_000_000L)
    }

    @Test
    fun legacyRowParsesWithNullNewFields() {
        val task = ScheduledTask.fromJson(legacyJson())
        assertEquals("legacy-1", task.id)
        assertNull(task.newSessionPermissionMode)
        assertNull(task.newSessionFolderId)
        assertEquals(ScheduledRepeatMode.DAILY, task.repeatMode)
        assertNull(task.modelBinding)
    }

    @Test
    fun legacyRowReserializesWithoutNewKeys() {
        // A pre-feature row re-saved by an old-then-new build must not grow
        // spurious keys that would confuse a future downgraded reader.
        val out = ScheduledTask.fromJson(legacyJson()).toJson()
        assertEquals(false, out.has("newSessionPermissionMode"))
        assertEquals(false, out.has("newSessionFolderId"))
    }

    @Test
    fun newFieldsRoundTrip() {
        val task = ScheduledTask(
            id = "new-1",
            label = "l",
            timeOfDayHour = 8,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = "p",
            newSessionPermissionMode = "YOYO",
            newSessionFolderId = "folder-42",
        )
        val parsed = ScheduledTask.fromJson(task.toJson())
        assertEquals("YOYO", parsed.newSessionPermissionMode)
        assertEquals("folder-42", parsed.newSessionFolderId)
    }

    /** Minimal valid NEW_SESSION task (hour/minute have no defaults). */
    private fun base() = ScheduledTask(
        label = "l",
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "p",
    )

    @Test
    fun permissionTokenDefaultsToAsk() {
        // Null (pre-feature rows), blank, and unknown tokens must all land on
        // ASK — the same normalization PermissionMode.sessionDefault applies
        // when a chat restores its row, so a task can never widen the gate.
        val base = base()
        assertEquals("ASK", base.copy(newSessionPermissionMode = null).newSessionPermissionToken())
        assertEquals("ASK", base.copy(newSessionPermissionMode = "ASK").newSessionPermissionToken())
        assertEquals("ASK", base.copy(newSessionPermissionMode = "").newSessionPermissionToken())
        // The enum-name ALLOW_ALL is NOT a session token — only "YOYO" is.
        assertEquals("ASK", base.copy(newSessionPermissionMode = "ALLOW_ALL").newSessionPermissionToken())
        assertEquals("ASK", base.copy(newSessionPermissionMode = "DENY_ALL").newSessionPermissionToken())
    }

    @Test
    fun permissionTokenYoyoMapsToAllowAll() {
        // "YOYO" is the only full-auto token; lowercase survives because
        // sessionDefault uppercases before comparing.
        assertEquals("YOYO", base().copy(newSessionPermissionMode = "YOYO").newSessionPermissionToken())
        assertEquals("YOYO", base().copy(newSessionPermissionMode = "yoyo").newSessionPermissionToken())
    }

    @Test
    fun folderResolutionValidatesExistence() {
        val base = base()
        assertNull(base.copy(newSessionFolderId = null).resolvedNewSessionFolderId(setOf("a", "b")))
        assertEquals(
            "a",
            base.copy(newSessionFolderId = "a").resolvedNewSessionFolderId(setOf("a", "b")),
        )
        // Folder dissolved after the task was saved → degrade to unfiled.
        assertNull(base.copy(newSessionFolderId = "ghost").resolvedNewSessionFolderId(setOf("a", "b")))
        assertNull(base.copy(newSessionFolderId = "ghost").resolvedNewSessionFolderId(emptySet()))
    }
}
