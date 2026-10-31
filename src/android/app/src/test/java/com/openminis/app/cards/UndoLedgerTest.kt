package com.openminis.app.cards

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Expiry / dedup / once-only semantics of the in-memory undo ledger. */
class UndoLedgerTest {

    @Before
    fun setUp() = UndoLedger.clear()

    @After
    fun tearDown() = UndoLedger.clear()

    @Test
    fun recordMakesCallUndoableWithinWindow() {
        UndoLedger.record("call-1", "volume_set", """{"stream":"music","percent":50}""", nowMs = 1_000L)
        val e = UndoLedger.entry("call-1", nowMs = 2_000L)!!
        assertEquals("volume_set", e.toolName)
        assertEquals(1_000L, e.recordedAtMs)
        assertTrue(UndoLedger.undoable("call-1", nowMs = 1_000L + UndoLedger.TTL_MS))
    }

    @Test
    fun entryExpiresAfterTtl() {
        UndoLedger.record("call-1", "volume_set", "{}", nowMs = 1_000L)
        assertNull(UndoLedger.entry("call-1", nowMs = 1_000L + UndoLedger.TTL_MS + 1))
        assertFalse(UndoLedger.undoable("call-1", nowMs = 1_000L + UndoLedger.TTL_MS + 1))
    }

    @Test
    fun duplicateRecordIsDedupedAndKeepsOriginalTimestamp() {
        UndoLedger.record("call-1", "volume_set", """{"percent":50}""", nowMs = 1_000L)
        UndoLedger.record("call-1", "volume_set", """{"percent":50}""", nowMs = 5_000L)
        assertEquals(1, UndoLedger.size())
        assertEquals(1_000L, UndoLedger.entry("call-1", nowMs = 5_000L)!!.recordedAtMs)
    }

    @Test
    fun changedPayloadReplacesAndBlankInputIsRejected() {
        UndoLedger.record("call-1", "volume_set", """{"percent":50}""", nowMs = 1_000L)
        UndoLedger.record("call-1", "volume_set", """{"percent":70}""", nowMs = 2_000L)
        assertEquals(1, UndoLedger.size())
        assertEquals("""{"percent":70}""", UndoLedger.entry("call-1", nowMs = 2_000L)!!.oldArgsJson)

        UndoLedger.record("", "volume_set", "{}", nowMs = 3_000L)
        UndoLedger.record("call-2", "", "{}", nowMs = 3_000L)
        UndoLedger.record("call-3", "volume_set", "", nowMs = 3_000L)
        assertEquals(1, UndoLedger.size())
    }

    @Test
    fun markUndoneIsOnceOnlyAndDisablesFurtherUndo() {
        UndoLedger.record("call-1", "wifi_toggle", """{"on":false}""", nowMs = 1_000L)
        assertTrue(UndoLedger.markUndone("call-1", nowMs = 2_000L))
        assertTrue(UndoLedger.isUndone("call-1"))
        assertFalse(UndoLedger.undoable("call-1", nowMs = 2_000L))
        // second undo must not re-arm
        assertFalse(UndoLedger.markUndone("call-1", nowMs = 3_000L))
        assertFalse(UndoLedger.undoable("call-1", nowMs = 3_000L))
    }

    @Test
    fun markUndoneOnUnknownOrExpiredCallFails() {
        assertFalse(UndoLedger.markUndone("nope", nowMs = 1_000L))
        UndoLedger.record("call-1", "wifi_toggle", "{}", nowMs = 1_000L)
        assertFalse(UndoLedger.markUndone("call-1", nowMs = 1_000L + UndoLedger.TTL_MS + 1))
        assertFalse(UndoLedger.isUndone("call-1"))
    }

    @Test
    fun undoneEntryStillCountsAsUndoneAfterExpiry() {
        UndoLedger.record("call-1", "wifi_toggle", "{}", nowMs = 1_000L)
        UndoLedger.markUndone("call-1", nowMs = 2_000L)
        // isUndone intentionally stays observable even past the undo window so
        // a re-opened detail sheet still shows the "Undone" label.
        assertTrue(UndoLedger.isUndone("call-1"))
        assertFalse(UndoLedger.undoable("call-1", nowMs = 999_999_999L))
    }
}
