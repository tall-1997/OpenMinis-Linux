package com.openminis.app.ui.chat

import com.openminis.app.data.RequestDeduper
import com.openminis.app.logging.AppLogger

/**
 * [T-pi-idempotent-submit] Per-VM adapter that wires [RequestDeduper] (the pi
 * durable/types.ts:518-619 requestId-deduplicated Submission port) into the
 * enqueue path. The gate lives BESIDE the logic instead of inside
 * ChatViewModel.kt because the VM sits at its architecture line ceiling — the
 * same SteerState pattern AgentSteeringQueue.kt uses for per-VM state.
 *
 * Scope decision (do not widen further): dedupe guards the two repeat-prone
 * admission channels — the queued-enqueue path (a stray double-tap or an
 * overlay/voice replay can submit the same text twice within seconds) and,
 * since the streaming double-tap fix, the steering OFFER path (P2: the offer
 * used to bypass the gate entirely). The direct sendMessage path is
 * deliberately unguarded: a repeat of a first message is usually real user
 * intent ("run this again"), and pi's Submission dedupe exists to absorb
 * re-admission races, not to veto the user.
 *
 * Two admission verbs, one record point per channel: [rejects] is the atomic
 * check-AND-record used by enqueuePrompt; the steering offer uses [seen]
 * (check-only) BEFORE the offer and [record] AFTER an ACCEPTED offer — the
 * record point is acceptance, so an offer that fails (end-of-stream race) and
 * falls back to enqueuePrompt still meets an unrecorded fingerprint there.
 *
 * Attachment nuance: the fingerprint is text-only (the component's key shape),
 * so callers consult the gate only for text-only enqueues — a same-text prompt
 * with different attachments is a different send and must not be absorbed.
 *
 * Thread model: enqueuePrompt runs on Main only (see the `_promptQueue` KDoc
 * in ChatViewModel), and the steering offer is offered from the sendMessage
 * funnel on Main too, so the gate needs no synchronization.
 */
internal class RequestDedupeGate(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val deduper = RequestDeduper()

    /** Session the current fingerprints belong to; a change resets the bookkeeping. */
    private var stampedSid: String = ""

    /**
     * Atomic check-and-record admission (enqueuePrompt path). True when
     * [trimmed] was already admitted for this session inside the dedupe window.
     * Blank text never dedupes (nothing to fingerprint — the caller's guard
     * already routes blank sends elsewhere).
     *
     * Session-switch reset: a session-id transition clears the previous
     * session's ring first (the steering-queue stamp rule). A VM never re-keys
     * to a different conversation today — the one transition in its life is
     * draft → persisted promotion in ensureSession — but per-session state
     * must never leak across conversations under ANY future re-keying.
     */
    fun rejects(sid: String, trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        syncStamp(sid)
        return deduper.check(sid, trimmed, nowMs()) is RequestDeduper.Decision.Reject
    }

    /**
     * Check-only probe (steering OFFER path): true when [trimmed] was already
     * admitted inside the window, WITHOUT recording — see the class KDoc for
     * why the record point is acceptance, not arrival.
     */
    fun seen(sid: String, trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        syncStamp(sid)
        return deduper.seenRecently(sid, trimmed, nowMs())
    }

    /**
     * Record [trimmed] as admitted without a check (steering-accept
     * bookkeeping). Without this a same-text double-tap's second offer would
     * sail through: steering never crosses enqueuePrompt's atomic gate.
     */
    fun record(sid: String, trimmed: String) {
        if (trimmed.isEmpty()) return
        syncStamp(sid)
        deduper.record(sid, trimmed, nowMs())
    }

    /** Session-switch bookkeeping shared by the three admission verbs. */
    private fun syncStamp(sid: String) {
        if (sid != stampedSid) {
            if (stampedSid.isNotEmpty()) deduper.clear(stampedSid)
            stampedSid = sid
        }
    }
}

/**
 * [T-pi-idempotent-submit] P2-4: a dedupe hit used to be a pure SILENT drop —
 * the second tap vanished with no feedback, and a deliberate fast re-send
 * (the "run this again" pattern the window exists to let through) looked
 * like a broken send. The VM sits at its architecture line ceiling, so the
 * log + notice pair lives here beside the gate; the wording stays a debounce
 * notice, not an error. Called from BOTH reject branches: the streaming
 * steering-offer gate (SendExt) and the enqueuePrompt reject branch.
 */
internal fun ChatViewModel.notifyDuplicateSendDropped() {
    AppLogger.info(ChatViewModel.TAG_STREAM, "send deduped (same text inside window)")
    appendSystemInfo(
        text = context.getString(com.openminis.app.R.string.vm_send_deduped),
        iconKind = "info",
    )
}

/**
 * [T-agent-steering-dedupe] Check-only probe for the streaming steering OFFER
 * branch (SendExt): true when this exact text was already admitted inside the
 * dedupe window — a streaming double-tap. Records nothing; the fingerprint is
 * committed by [requestDedupeRecord] only when steerPrompt ACCEPTS the offer
 * (see the SendExt branch for the funnel order and the failure fallback).
 */
internal fun ChatViewModel.requestDedupeSeen(sid: String, trimmed: String): Boolean =
    requestDeduper.seen(sid, trimmed)

/**
 * [T-agent-steering-dedupe] Commit the fingerprint after steerPrompt accepted
 * an offer. Steering never crosses enqueuePrompt's atomic gate, so without
 * this commit a same-text double-tap's second offer would pass unchecked.
 */
internal fun ChatViewModel.requestDedupeRecord(sid: String, trimmed: String) {
    requestDeduper.record(sid, trimmed)
}
