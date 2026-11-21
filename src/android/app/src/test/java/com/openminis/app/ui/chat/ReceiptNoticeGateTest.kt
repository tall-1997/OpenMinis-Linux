package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-receipt-notice-gate] Three announcement states the user actually sees:
 * a new debt announces once, an unchanged count stays silent, and a clean
 * state re-arms the gate so a later debt announces again. Generations never
 * bleed into each other, and the "could not be checked" failure branch is
 * gated by the same store.
 */
class ReceiptNoticeGateTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun announcesOncePerFingerprintThenGoesSilent() {
        val dir = tmp.newFolder("notices")
        val parent = "a".repeat(64)
        assertEquals("line-1", ReceiptNoticeGate.resolve(dir, "session-1", parent, "line-1"))
        assertNull(ReceiptNoticeGate.resolve(dir, "session-1", parent, "line-1"))
        assertEquals("line-2", ReceiptNoticeGate.resolve(dir, "session-1", parent, "line-2"))
        assertNull(ReceiptNoticeGate.resolve(dir, "session-1", parent, "line-2"))
    }

    @Test
    fun generationsDoNotBleedIntoEachOther() {
        val dir = tmp.newFolder("notices2")
        val genA = "a".repeat(64)
        val genB = "b".repeat(64)
        assertEquals("A", ReceiptNoticeGate.resolve(dir, "session-1", genA, "A"))
        assertEquals("B", ReceiptNoticeGate.resolve(dir, "session-1", genB, "B"))
        // The same count in one generation does not resurrect the other's
        // announcement and vice versa.
        assertNull(ReceiptNoticeGate.resolve(dir, "session-1", genB, "B"))
        assertNull(ReceiptNoticeGate.resolve(dir, "session-1", genA, "A"))
    }

    @Test
    fun cleanStateClearsTheFingerprintSoLaterDebtReannounces() {
        val dir = tmp.newFolder("notices3")
        val parent = "c".repeat(64)
        assertEquals("debt", ReceiptNoticeGate.resolve(dir, "session-1", parent, "debt"))
        assertNull(ReceiptNoticeGate.resolve(dir, "session-1", parent, "debt"))
        assertNull(ReceiptNoticeGate.resolve(dir, "session-1", parent, null)) // clean -> clear
        assertEquals("debt", ReceiptNoticeGate.resolve(dir, "session-1", parent, "debt")) // re-announces
    }

    @Test
    fun errorBranchIsGatedToo() {
        val dir = tmp.newFolder("notices4")
        val parent = "d".repeat(64)
        assertEquals("err", ReceiptNoticeGate.resolve(dir, "session-1", parent, "err"))
        assertNull(ReceiptNoticeGate.resolve(dir, "session-1", parent, "err"))
    }

    @Test
    fun fingerprintsSurviveARestart() {
        val dir = tmp.newFolder("notices5")
        val parent = "e".repeat(64)
        assertEquals("n", ReceiptNoticeGate.resolve(dir, "session-1", parent, "n"))
        // A fresh resolve against the same directory = process restart: the
        // stored fingerprint must still silence the unchanged count.
        assertNull(ReceiptNoticeGate.resolve(dir, "session-1", parent, "n"))
    }
}
