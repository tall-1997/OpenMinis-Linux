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

    // ── [T-undo-staleness-ordering] per-subject sequencing ───────────────────

    @Test
    fun newerRecordForSameSubjectRefusesOlderUndo() {
        UndoLedger.record("call-1", "volume_set", """{"stream":"music","percent":50}""", nowMs = 1_000L)
        UndoLedger.record("call-2", "volume_set", """{"stream":"music","percent":70}""", nowMs = 2_000L)
        // Undoing the older card would restore the 50% captured before the
        // newer call ran — refused; only the latest record stays undoable.
        assertFalse(UndoLedger.undoable("call-1", nowMs = 2_000L))
        assertTrue(UndoLedger.undoable("call-2", nowMs = 2_000L))
    }

    @Test
    fun differentVolumeStreamsAreDistinctSubjects() {
        UndoLedger.record("call-1", "volume_set", """{"stream":"music","percent":50}""", nowMs = 1_000L)
        UndoLedger.record("call-2", "volume_set", """{"stream":"ring","percent":70}""", nowMs = 2_000L)
        // Music and ring volume are independent device state; neither undo
        // clobbers the other's subject, so both stay undoable.
        assertTrue(UndoLedger.undoable("call-1", nowMs = 2_000L))
        assertTrue(UndoLedger.undoable("call-2", nowMs = 2_000L))
    }

    @Test
    fun differentToolsRemainIndependentSubjects() {
        UndoLedger.record("call-1", "dnd_set", """{"mode":"off"}""", nowMs = 1_000L)
        UndoLedger.record("call-2", "brightness_set", """{"level":128}""", nowMs = 2_000L)
        assertTrue(UndoLedger.undoable("call-1", nowMs = 2_000L))
        assertTrue(UndoLedger.undoable("call-2", nowMs = 2_000L))
    }

    @Test
    fun subjectKeyDerivesStreamSelectorAndToolFallback() {
        assertEquals("volume:music", UndoLedger.subjectKeyOf("volume_set", """{"stream":"music","percent":5}"""))
        assertEquals("volume:ring", UndoLedger.subjectKeyOf("volume_set", """{"stream":"ring"}"""))
        // Garbage / selector-less args degrade to the tool-level subject.
        assertEquals("volume", UndoLedger.subjectKeyOf("volume_set", "{not json"))
        assertEquals("volume", UndoLedger.subjectKeyOf("volume_set", """{"percent":10}"""))
        assertEquals("brightness", UndoLedger.subjectKeyOf("brightness_set", """{"level":9}"""))
        assertEquals("dnd", UndoLedger.subjectKeyOf("dnd_set", """{"mode":"all"}"""))
        assertEquals("sound_mode", UndoLedger.subjectKeyOf("sound_mode", """{"mode":"silent"}"""))
        assertEquals("wifi", UndoLedger.subjectKeyOf("wifi_toggle", """{"on":true}"""))
        assertEquals("bluetooth", UndoLedger.subjectKeyOf("bluetooth_toggle", """{"on":false}"""))
        assertEquals("mobile_data", UndoLedger.subjectKeyOf("mobile_data_toggle", """{"on":true}"""))
        // Unknown tools: the tool name IS the subject (deterministic fallback).
        assertEquals("some_future_tool", UndoLedger.subjectKeyOf("some_future_tool", "{}"))
    }

    @Test
    fun markUndoneVoidsOlderRecordsOfSameSubject() {
        UndoLedger.record("call-1", "volume_set", """{"stream":"music","percent":50}""", nowMs = 1_000L)
        UndoLedger.record("call-2", "volume_set", """{"stream":"music","percent":70}""", nowMs = 2_000L)
        UndoLedger.record("call-3", "volume_set", """{"stream":"music","percent":80}""", nowMs = 3_000L)
        assertTrue(UndoLedger.markUndone("call-3", nowMs = 4_000L))
        // call-1 and call-2 would restore even older values on top of the
        // restored one — voided outright, not "undone" (their cards never
        // showed an undo the user pressed).
        assertNull(UndoLedger.entry("call-1", nowMs = 4_000L))
        assertNull(UndoLedger.entry("call-2", nowMs = 4_000L))
        assertFalse(UndoLedger.undoable("call-1", nowMs = 4_000L))
        assertFalse(UndoLedger.undoable("call-2", nowMs = 4_000L))
        assertFalse(UndoLedger.isUndone("call-1"))
        assertEquals(1, UndoLedger.size())
        // The undone record itself keeps the "Undone" observable state.
        assertTrue(UndoLedger.isUndone("call-3"))
    }

    @Test
    fun voidingSparesOtherSubjects() {
        UndoLedger.record("call-1", "volume_set", """{"stream":"music","percent":50}""", nowMs = 1_000L)
        UndoLedger.record("call-2", "volume_set", """{"stream":"ring","percent":70}""", nowMs = 2_000L)
        UndoLedger.record("call-3", "volume_set", """{"stream":"music","percent":80}""", nowMs = 3_000L)
        assertTrue(UndoLedger.markUndone("call-3", nowMs = 4_000L))
        // The ring-stream record is a different subject — untouched.
        assertTrue(UndoLedger.undoable("call-2", nowMs = 4_000L))
        assertEquals(2, UndoLedger.size())
    }

    @Test
    fun payloadReplaceOnSameCallMakesItLatestAgain() {
        UndoLedger.record("call-1", "volume_set", """{"stream":"music","percent":50}""", nowMs = 1_000L)
        UndoLedger.record("call-2", "volume_set", """{"stream":"music","percent":70}""", nowMs = 2_000L)
        assertFalse(UndoLedger.undoable("call-1", nowMs = 2_000L))
        // A re-derived payload for the same call id is a genuine replacement:
        // the call re-records as the subject's latest.
        UndoLedger.record("call-1", "volume_set", """{"stream":"music","percent":55}""", nowMs = 3_000L)
        assertTrue(UndoLedger.undoable("call-1", nowMs = 3_000L))
        assertFalse(UndoLedger.undoable("call-2", nowMs = 3_000L))
    }

    @Test
    fun olderRecordStaysRefusedIntoTtlExpiry() {
        UndoLedger.record("call-1", "volume_set", """{"stream":"music","percent":50}""", nowMs = 1_000L)
        UndoLedger.record("call-2", "volume_set", """{"stream":"music","percent":70}""", nowMs = 61_000L)
        // While both are live the older one is refused…
        assertFalse(UndoLedger.undoable("call-1", nowMs = 62_000L))
        // …and refusal persists past the older record's own TTL expiry (the
        // ordering refusal and the expiry refusal agree); the newer record is
        // untouched and stays undoable.
        assertFalse(UndoLedger.undoable("call-1", nowMs = 1_000L + UndoLedger.TTL_MS + 1))
        assertTrue(UndoLedger.undoable("call-2", nowMs = 1_000L + UndoLedger.TTL_MS + 1))
    }
}
