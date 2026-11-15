package com.openminis.app.ui.chat

import com.openminis.app.data.RequestDeduper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-pi-idempotent-submit] Pins for the enqueue-path dedupe gate
 * (ChatRequestDedupeExt): the RequestDeduper component has its own test
 * (RequestDeduperTest); what is pinned HERE is the wiring semantics —
 * window rejects, blank passthrough, and the session-switch stamp reset the
 * gate adds on top of the component.
 */
class RequestDedupeGateTest {

    @Test
    fun `same text inside the window is rejected, different text is not`() {
        var now = 1_000L
        val gate = RequestDedupeGate(nowMs = { now })
        assertFalse(gate.rejects("s1", "run the tests"))
        assertTrue(gate.rejects("s1", "run the tests"))
        assertFalse(gate.rejects("s1", "run the tests twice"))
        // Edge whitespace is normalized by the fingerprint (RequestDeduper KDoc),
        // so a re-send that only differs in padding still dedupes.
        assertTrue(gate.rejects("s1", " run the tests "))
    }

    @Test
    fun `same text is admitted again after the window expires`() {
        var now = 1_000L
        val gate = RequestDedupeGate(nowMs = { now })
        assertFalse(gate.rejects("s1", "go"))
        assertTrue(gate.rejects("s1", "go"))
        now += RequestDeduper.DEFAULT_WINDOW_MS + 1
        // A real re-send: the window only absorbs accidental double-taps.
        assertFalse(gate.rejects("s1", "go"))
    }

    @Test
    fun `a session switch resets the bookkeeping`() {
        var now = 1_000L
        val gate = RequestDedupeGate(nowMs = { now })
        assertFalse(gate.rejects("s1", "hello"))
        assertTrue(gate.rejects("s1", "hello"))
        // A different session: isolated ring, the same text is admitted.
        assertFalse(gate.rejects("s2", "hello"))
        // Back to s1 — the stamp moved, s1's ring was cleared on the switch:
        // per-session state never leaks across conversations.
        assertFalse(gate.rejects("s1", "hello"))
    }

    @Test
    fun `blank text never dedupes`() {
        val gate = RequestDedupeGate()
        assertFalse(gate.rejects("s1", ""))
    }
}
