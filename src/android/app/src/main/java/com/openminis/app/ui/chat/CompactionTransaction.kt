package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger

/**
 * [T-compaction-transaction] The durable half of a compaction.
 *
 * WHY: before this, compaction was a purely in-memory state machine
 * (`_isCompacting` / `compactJob` / `_cachedLatestMarker`). A process death
 * between "the summarizer answered (paid for)" and "the marker landed" left no
 * evidence at all: the next launch saw "compaction never happened" and paid for
 * the same summary again, with nothing in the UI or the logs to explain it.
 *
 * The transaction is bracket-first, the same shape dsh uses:
 *   start  → summary → end
 * with an unmatched `start` meaning "a summary attempt was in flight and never
 * settled". `interrupted` is recorded by the NEXT launch when it finds one, so
 * the record is honest about which side of the crash it stands on.
 *
 * Deliberately NOT a lock: Android has no second writer that could race a
 * compaction, and a stale marker that blocks compaction forever would be worse
 * than the duplicate it prevents. This is evidence, and it feeds the retry
 * authorization below.
 */
internal object CompactionTransaction {

    /** Monotonic per-session counter of settled attempts (any outcome). */
    private val generations = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** Id of the attempt currently open in this process, if any. */
    @Volatile
    internal var openId: String? = null

    internal fun newId(): String = java.util.UUID.randomUUID().toString()

    /**
     * [T-compaction-transaction] Retry authorization.
     *
     * The old rule was a boolean (`succeeded`), which let a caller retry a
     * failed compaction immediately and pay again without anything having
     * changed. A retry is now authorized only when the attempt actually
     * advanced something — here, when the summary generation for this session
     * moved. A failure that produced no advance leaves the generation where it
     * was, so an unconditional retry loop cannot burn calls.
     */
    fun generationOf(sessionId: String): Int =
        generations[sessionId]?.get() ?: 0

    fun advanceGeneration(sessionId: String): Int =
        generations.getOrPut(sessionId) { java.util.concurrent.atomic.AtomicInteger(0) }
            .incrementAndGet()

    /** True when [before] is older than the current generation: a retry may proceed. */
    fun retryAuthorized(sessionId: String, before: Int): Boolean =
        generationOf(sessionId) > before

    /** [T-compaction-transaction] Last failure per session: (generation, atMs). */
    private val lastFailure =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * [T-compaction-transaction] May a new attempt start now?
     *
     * A blind retry burns a summary call to learn nothing. The rule is: after a
     * failure, wait [FAILURE_COOLDOWN_MS] unless the generation moved (i.e. a
     * different attempt settled and changed the boundary). That is what stops a
     * broken summarizer from being re-entered by every user tap, while a
     * genuinely new situation is never blocked.
     */
    fun retryAllowedNow(sessionId: String, nowMs: Long): Boolean {
        val failed = lastFailure[sessionId] ?: return true
        if (generationOf(sessionId) != failed.first) return true
        // [T-compaction-cooldown-monotonic] A backwards wall clock (NTP fix,
        // manual change, emulator skew) must not lock the retry out for the whole
        // skew: a negative gap counts as expired, exactly like an elapsed one.
        return nowMs < failed.second || nowMs - failed.second >= FAILURE_COOLDOWN_MS
    }

    /** [T-compaction-transaction] Record a failed attempt at the given clock. */
    fun markFailed(sessionId: String, nowMs: Long) {
        lastFailure[sessionId] = generationOf(sessionId) to nowMs
    }

    /** [T-compaction-transaction] Gap before an unchanged failure may retry. */
    const val FAILURE_COOLDOWN_MS: Long = 30_000L

    internal fun resetForTest() {
        generations.clear()
        lastFailure.clear()
        openId = null
    }
}

/**
 * [T-compaction-transaction] Start marker. Returns the attempt id so the same
 * call's summary/end can be correlated (and so a crash can be matched).
 */
internal fun ChatViewModel.beginCompactionJournal(
    sessionId: String,
    anchorIdx: Int,
    rangeMessages: Int,
    rangeChars: Int,
): String {
    val id = CompactionTransaction.newId()
    CompactionTransaction.openId = id
    runCatching {
        SessionJournal.record(
            filesDir = context.filesDir,
            sessionId = sessionId,
            kind = SessionJournal.COMPACTION_START,
            fields = mapOf(
                "compactionId" to id,
                "anchorIdx" to anchorIdx,
                "rangeMessages" to rangeMessages,
                "rangeChars" to rangeChars,
            ),
        )
    }
    return id
}

/** [T-compaction-transaction] The summary settled; the marker is about to land. */
internal fun ChatViewModel.settleCompactionJournal(
    sessionId: String,
    attemptId: String,
    summaryChars: Int,
    coveredCount: Int,
    markerId: String,
) {
    CompactionTransaction.openId = null
    val generation = CompactionTransaction.advanceGeneration(sessionId)
    runCatching {
        SessionJournal.record(
            filesDir = context.filesDir,
            sessionId = sessionId,
            kind = SessionJournal.COMPACTION_END,
            fields = mapOf(
                "compactionId" to attemptId,
                "summaryChars" to summaryChars,
                "coveredCount" to coveredCount,
                "markerId" to markerId,
                "generation" to generation,
            ),
        )
    }
}

/** [T-compaction-transaction] The attempt ended without a usable summary. */
internal fun ChatViewModel.abandonCompactionJournal(
    sessionId: String,
    attemptId: String,
    reason: String,
) {
    CompactionTransaction.openId = null
    // [T-compaction-transaction] A user cancel is not a failure: the next tap
    // must run immediately. Every other abandon (no summary, no anchor, marker
    // write failed, timeout, provider error) arms the cooldown so an unchanged
    // failure cannot be re-entered by the next tap and pay again.
    if (reason != REASON_CANCELLED) {
        CompactionTransaction.markFailed(sessionId, System.currentTimeMillis())
    }
    runCatching {
        SessionJournal.record(
            filesDir = context.filesDir,
            sessionId = sessionId,
            kind = SessionJournal.COMPACTION_INTERRUPTED,
            fields = mapOf("compactionId" to attemptId, "reason" to reason),
        )
    }
}

/** [T-compaction-transaction] Reason string that means the user cancelled. */
internal const val REASON_CANCELLED = "cancelled"

/**
 * [T-compaction-transaction] Report attempts that opened and never closed —
 * i.e. a process died mid-compaction. Called on resume, not on every turn.
 */
internal fun ChatViewModel.reportInterruptedCompactions(sessionId: String) {
    // [T-session-journal-sid-capture] The caller captures the owner BEFORE
    // launching the detached scan. Re-reading the live session here would
    // attribute B's interrupted records to A after a fast switch, while A's
    // own stayed unreported.
    val sid = sessionId.ifEmpty { realSessionId.ifEmpty { activeSessionId } }
    if (sid.isEmpty()) return
    runCatching {
        val interrupted = SessionJournal.interruptedCompactions(context.filesDir, sid)
        if (interrupted.isEmpty()) return@runCatching
        AppLogger.info(
            ChatViewModel.TAG,
            "[Compact] ${interrupted.size} interrupted compaction attempt(s) recovered from the journal",
        )
        for (e in interrupted) {
            SessionJournal.record(
                filesDir = context.filesDir,
                sessionId = sid,
                kind = SessionJournal.COMPACTION_INTERRUPTED,
                fields = mapOf(
                    "compactionId" to e.optString("compactionId"),
                    "reason" to "process died before the summary settled",
                ),
            )
        }
    }
}