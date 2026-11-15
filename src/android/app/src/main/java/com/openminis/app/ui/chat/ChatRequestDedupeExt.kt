package com.openminis.app.ui.chat

import com.openminis.app.data.RequestDeduper

/**
 * [T-pi-idempotent-submit] Per-VM adapter that wires [RequestDeduper] (the pi
 * durable/types.ts:518-619 requestId-deduplicated Submission port) into the
 * enqueue path. The gate lives BESIDE the logic instead of inside
 * ChatViewModel.kt because the VM sits at its architecture line ceiling — the
 * same SteerState pattern AgentSteeringQueue.kt uses for per-VM state.
 *
 * Scope decision (do not widen): dedupe guards ONLY the queued-enqueue path —
 * the streaming state where a stray double-tap or an overlay/voice replay can
 * submit the same text twice within seconds. The direct sendMessage path is
 * deliberately unguarded: a repeat of a first message is usually real user
 * intent ("run this again"), and pi's Submission dedupe exists to absorb
 * re-admission races, not to veto the user.
 *
 * Attachment nuance: the fingerprint is text-only (the component's key shape),
 * so callers consult the gate only for text-only enqueues — a same-text prompt
 * with different attachments is a different send and must not be absorbed.
 *
 * Thread model: enqueuePrompt runs on Main only (see the `_promptQueue` KDoc
 * in ChatViewModel), so the gate needs no synchronization.
 */
internal class RequestDedupeGate(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val deduper = RequestDeduper()

    /** Session the current fingerprints belong to; a change resets the bookkeeping. */
    private var stampedSid: String = ""

    /**
     * True when [trimmed] was already admitted for this session inside the
     * dedupe window. Blank text never dedupes (nothing to fingerprint — the
     * caller's guard already routes blank sends elsewhere).
     *
     * Session-switch reset: a session-id transition clears the previous
     * session's ring first (the steering-queue stamp rule). A VM never re-keys
     * to a different conversation today — the one transition in its life is
     * draft → persisted promotion in ensureSession — but per-session state
     * must never leak across conversations under ANY future re-keying.
     */
    fun rejects(sid: String, trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        if (sid != stampedSid) {
            if (stampedSid.isNotEmpty()) deduper.clear(stampedSid)
            stampedSid = sid
        }
        return deduper.check(sid, trimmed, nowMs()) is RequestDeduper.Decision.Reject
    }
}
