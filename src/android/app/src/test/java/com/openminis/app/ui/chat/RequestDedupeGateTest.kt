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

    @Test
    fun `deliberate re-send inside one second is admitted after the window`() {
        // P2-4: the shipped window is 800 ms — the "run this again" pattern
        // (and chained follow-ups users fire within seconds) must pass, while
        // an immediate double-tap stays rejected. The hit itself now surfaces
        // a system-info notice via notifyDuplicateSendDropped (VM wiring,
        // manual regression).
        var now = 1_000L
        val gate = RequestDedupeGate(nowMs = { now })
        assertFalse(gate.rejects("s1", "go"))
        // Immediate second send: rejected (double-tap range).
        now += 300
        assertTrue(gate.rejects("s1", "go"))
        // Deliberate re-send just past the window: admitted again.
        now += 1_000
        assertFalse(gate.rejects("s1", "go"))
    }

    // -- [T-agent-steering-dedupe] the steering OFFER path: check-only probe,
    //    record on acceptance. The offer bypasses enqueuePrompt's atomic
    //    check-and-record gate, so seen() must record NOTHING and record()
    //    must commit the fingerprint for the NEXT offer/enqueue to hit.

    @Test
    fun `steering double tap - first offer seen-then-recorded, second offer blocked`() {
        var now = 1_000L
        val gate = RequestDedupeGate(nowMs = { now })
        // Tap 1: the funnel probes (nothing recorded yet), steerPrompt accepts,
        // the fingerprint is committed.
        assertFalse(gate.seen("s1", "continue"))
        gate.record("s1", "continue")
        // Tap 2 within the window: the probe sees the recorded fingerprint —
        // the offer is refused with the dedupe notice, nothing is queued twice.
        assertTrue(gate.seen("s1", "continue"))
        // Past the window: a deliberate re-send is offered again (the window
        // only absorbs the accidental double-tap, enqueue-path parity).
        now += RequestDeduper.DEFAULT_WINDOW_MS + 1
        assertFalse(gate.seen("s1", "continue"))
    }

    @Test
    fun `steering probe records nothing - enqueue fallback after a failed offer is not mis-rejected`() {
        // steerPrompt loses the end-of-stream race: the funnel falls through to
        // enqueuePrompt, whose atomic gate must still see an UNRECORDED
        // fingerprint (the probe alone must not have committed it).
        var now = 1_000L
        val gate = RequestDedupeGate(nowMs = { now })
        assertFalse(gate.seen("s1", "run the build"))
        // No record() happened (offer failed) — enqueue's check-and-record admits.
        assertFalse(gate.rejects("s1", "run the build"))
        // ...and a same-text double-tap on the enqueue path still dedupes.
        assertTrue(gate.rejects("s1", "run the build"))
    }

    @Test
    fun `steering acceptance also blocks a same-text enqueue inside the window`() {
        // Cross-channel: steer accepted + recorded, then the same text is
        // enqueued (attachments path or race fallback) inside the window —
        // the enqueue gate must reject it (one delivery, not two).
        var now = 1_000L
        val gate = RequestDedupeGate(nowMs = { now })
        assertFalse(gate.seen("s1", "same text"))
        gate.record("s1", "same text")
        assertTrue(gate.rejects("s1", "same text"))
    }

    @Test
    fun `steering probe and record are blank-safe and session-stamped`() {
        val gate = RequestDedupeGate()
        assertFalse(gate.seen("s1", ""))
        gate.record("s1", "")  // no-op, must not create bookkeeping
        assertFalse(gate.rejects("s1", ""))
        // Session isolation: s2's offer is unaffected by s1's record.
        assertFalse(gate.seen("s2", "hello"))
        gate.record("s1", "hello")
        assertTrue(gate.seen("s1", "hello"))
        assertFalse(gate.seen("s2", "hello"))
    }
}
