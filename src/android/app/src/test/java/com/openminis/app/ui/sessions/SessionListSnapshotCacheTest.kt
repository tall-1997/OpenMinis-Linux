package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.repository.SessionListSnapshotPayload
import com.openminis.app.data.repository.SnapshotRestoreDecision
import com.openminis.app.data.repository.decodeSessionListSnapshot
import com.openminis.app.data.repository.encodeSessionListSnapshot
import com.openminis.app.data.repository.sessionListEntryRevealed
import com.openminis.app.data.repository.sessionListStallSpinner
import com.openminis.app.data.repository.snapshotRestoreDecision
import com.openminis.app.data.repository.toEntity
import com.openminis.app.data.repository.toSnapshotRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-sessionlist-snapshot-restore] Pure-decision tests for the
 * session-list snapshot cache: full-fidelity round-trip, fault-tolerant
 * decode (corrupt / blank / future version), the restore decision table, and
 * the render-gate decisions the list pane now branches on. A snapshot the
 * decoder drops must degrade to the pre-fix blank-frame behavior, never to a
 * crash loop.
 */
class SessionListSnapshotCacheTest {

    private fun session(
        id: String = "s1",
        title: String? = "hello",
        folderId: String? = null,
        pinnedAt: Long? = null,
        updatedAt: Long = 2000L,
    ) = ChatSessionEntity(
        id = id,
        title = title,
        modelId = "gpt-test",
        createdAt = 1000L,
        updatedAt = updatedAt,
        category = "work",
        lastMessage = "last body",
        modelBinding = "provider-1/gpt-test",
        source = "shortcut",
        memoryEnabled = 1,
        pinnedAt = pinnedAt,
        editCount = 3,
        thinkingOverride = null,
        folderId = folderId,
        permissionMode = "ASK",
    )

    private fun folder(
        id: String = "f1",
        name: String = "Projects",
        pinnedAt: Long? = null,
    ) = FolderEntity(
        id = id,
        name = name,
        icon = "folder",
        color = "#FFAA00",
        origin = FolderEntity.ORIGIN_MANUAL,
        sortIndex = 0,
        pinnedAt = pinnedAt,
        description = "work stuff",
        createdAt = 500L,
        updatedAt = 600L,
    )

    private fun encodeOf(sessions: List<ChatSessionEntity>, folders: List<FolderEntity>): String =
        encodeSessionListSnapshot(
            SessionListSnapshotPayload(
                savedAtMs = 42L,
                sessions = sessions.map { it.toSnapshotRow() },
                folders = folders.map { it.toSnapshotRow() },
            ),
        )

    // --- round-trip: the restored list must equal what was on screen ---

    @Test
    fun roundTripPreservesEverySessionField() {
        val original = session()
        val restored = decodeSessionListSnapshot(encodeOf(listOf(original), emptyList()))!!
        assertEquals(1, restored.sessions.size)
        assertEquals(original, restored.sessions[0].toEntity())
    }

    @Test
    fun roundTripPreservesNullFields() {
        val original = session(title = null, folderId = null, pinnedAt = null)
        val restored = decodeSessionListSnapshot(encodeOf(listOf(original), emptyList()))!!
        assertEquals(original, restored.sessions[0].toEntity())
    }

    @Test
    fun roundTripPreservesFolderFieldsAndPinning() {
        val original = folder(pinnedAt = 900L)
        val restored = decodeSessionListSnapshot(encodeOf(emptyList(), listOf(original)))!!
        assertEquals(1, restored.folders.size)
        assertEquals(original, restored.folders[0].toEntity())
    }

    @Test
    fun multiRowRoundTripKeepsOrder() {
        val sessions = listOf(
            session(id = "a", updatedAt = 3L),
            session(id = "b", updatedAt = 2L),
            session(id = "c", updatedAt = 1L),
        )
        val restored = decodeSessionListSnapshot(encodeOf(sessions, emptyList()))!!
        assertEquals(listOf("a", "b", "c"), restored.sessions.map { it.id })
    }

    // --- decode fault tolerance ---

    @Test
    fun decodeRejectsCorruptJson() {
        assertNull(decodeSessionListSnapshot("{not json at all"))
    }

    @Test
    fun decodeRejectsBlankAndNull() {
        assertNull(decodeSessionListSnapshot(null))
        assertNull(decodeSessionListSnapshot(""))
        assertNull(decodeSessionListSnapshot("   "))
    }

    @Test
    fun decodeRejectsWrongVersion() {
        val json = encodeOf(listOf(session()), emptyList())
            .replace("\"version\":1", "\"version\":99")
        assertNull(decodeSessionListSnapshot(json))
    }

    @Test
    fun decodeToleratesUnknownFutureFields() {
        val json = """{"version":1,"savedAtMs":1,"sessions":[],"folders":[],"futureField":true}"""
        val payload = decodeSessionListSnapshot(json)
        assertEquals(0, payload!!.sessions.size)
    }

    // --- restore decision table ---

    @Test
    fun restoreDecisionTable() {
        val valid = SessionListSnapshotPayload(
            savedAtMs = 1L,
            sessions = listOf(session().toSnapshotRow()),
        )
        assertEquals(
            SnapshotRestoreDecision.SKIP_DB_ALREADY_LANDED,
            snapshotRestoreDecision(dbEmissionLanded = true, snapshot = valid),
        )
        assertEquals(
            SnapshotRestoreDecision.SKIP_NO_SNAPSHOT,
            snapshotRestoreDecision(dbEmissionLanded = false, snapshot = null),
        )
        assertEquals(
            SnapshotRestoreDecision.SKIP_EMPTY,
            snapshotRestoreDecision(dbEmissionLanded = false, snapshot = SessionListSnapshotPayload()),
        )
        assertEquals(
            SnapshotRestoreDecision.RESTORE,
            snapshotRestoreDecision(dbEmissionLanded = false, snapshot = valid),
        )
    }

    // --- render gate decisions ---

    @Test
    fun entryRevealedTable() {
        assertFalse(
            sessionListEntryRevealed(initialComplete = false, timedOut = false, snapshotRestored = false),
        )
        assertTrue(
            sessionListEntryRevealed(initialComplete = true, timedOut = false, snapshotRestored = false),
        )
        assertTrue(
            sessionListEntryRevealed(initialComplete = false, timedOut = true, snapshotRestored = false),
        )
        assertTrue(
            sessionListEntryRevealed(initialComplete = false, timedOut = false, snapshotRestored = true),
        )
    }

    @Test
    fun stallSpinnerTable() {
        // True first runs (no snapshot) keep the pre-fix behavior: blank until
        // the 3s timeout surfaces the stall spinner.
        assertTrue(
            sessionListStallSpinner(initialComplete = false, snapshotRestored = false, sessionsNonEmpty = false),
        )
        // Snapshot restored → real content on screen; the spinner must not
        // cover it even though the first Room emission is still queuing.
        assertFalse(
            sessionListStallSpinner(initialComplete = false, snapshotRestored = true, sessionsNonEmpty = true),
        )
        // DB emission landed → never a stall spinner.
        assertFalse(
            sessionListStallSpinner(initialComplete = true, snapshotRestored = false, sessionsNonEmpty = true),
        )
        assertFalse(
            sessionListStallSpinner(initialComplete = true, snapshotRestored = false, sessionsNonEmpty = false),
        )
    }
}
