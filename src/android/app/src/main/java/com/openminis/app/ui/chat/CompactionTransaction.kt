package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Journal attempts without making the journal a prerequisite for a Room commit. */
internal object CompactionTransaction {
    const val FAILURE_COOLDOWN_MS = 30_000L

    private data class RetryState(val generation: Int = 0, val failedAtMs: Long? = null)

    // The UI checks cooldown without blocking on journal I/O or recovery scans.
    private val retries = ConcurrentHashMap<String, RetryState>()
    private val openAttempts = HashMap<String, String>()

    fun generationOf(sessionId: String): Int = retries[sessionId]?.generation ?: 0

    private fun advanceGeneration(sessionId: String): Int = checkNotNull(retries.compute(sessionId) { _, state ->
        RetryState(generation = (state?.generation ?: 0) + 1)
    }).generation

    fun retryAuthorized(sessionId: String, before: Int): Boolean = generationOf(sessionId) > before

    fun retryAllowedNow(sessionId: String, nowMs: Long): Boolean = cooldownRemainingMs(sessionId, nowMs) == 0L

    fun cooldownRemainingMs(sessionId: String, nowMs: Long): Long {
        val failedAt = retries[sessionId]?.failedAtMs ?: return 0L
        // A backwards wall clock must not lock the session out until it catches up.
        if (nowMs < failedAt) return 0L
        return (FAILURE_COOLDOWN_MS - (nowMs - failedAt)).coerceAtLeast(0L)
    }

    private fun markFailed(sessionId: String, nowMs: Long) {
        retries.compute(sessionId) { _, state -> (state ?: RetryState()).copy(failedAtMs = nowMs) }
    }

    @Synchronized
    fun begin(
        filesDir: File?, sessionId: String, anchorIdx: Int, rangeMessages: Int, rangeChars: Int,
    ): String {
        val id = UUID.randomUUID().toString()
        openAttempts[id] = sessionId
        SessionJournal.record(
            filesDir, sessionId, SessionJournal.COMPACTION_START,
            mapOf("compactionId" to id, "anchorIdx" to anchorIdx, "rangeMessages" to rangeMessages, "rangeChars" to rangeChars),
        )
        return id
    }

    @Synchronized
    fun settle(
        filesDir: File?, sessionId: String, attemptId: String, summaryChars: Int, coveredCount: Int, markerId: String,
    ) {
        if (openAttempts[attemptId] != sessionId) return
        openAttempts.remove(attemptId)
        val generation = advanceGeneration(sessionId)
        SessionJournal.record(
            filesDir, sessionId, SessionJournal.COMPACTION_END,
            mapOf(
                "compactionId" to attemptId, "summaryChars" to summaryChars, "coveredCount" to coveredCount,
                "markerId" to markerId, "generation" to generation,
            ),
        )
    }

    @Synchronized
    fun abandon(filesDir: File?, sessionId: String, attemptId: String, reason: String, nowMs: Long) {
        if (openAttempts[attemptId] != sessionId) return
        openAttempts.remove(attemptId)
        if (reason != REASON_CANCELLED) markFailed(sessionId, nowMs)
        recordInterruption(filesDir, sessionId, attemptId, reason)
    }

    /** Do not report another live ViewModel's attempt as a process death. */
    @Synchronized
    fun recoverInterrupted(filesDir: File, sessionId: String): Int {
        if (sessionId.isBlank()) return 0
        val interrupted = SessionJournal.interruptedCompactions(filesDir, sessionId)
            .map { it.optString("compactionId") }
            .filterNot(openAttempts::containsKey)
        for (id in interrupted) {
            recordInterruption(filesDir, sessionId, id, "process died before the attempt settled")
        }
        return interrupted.size
    }

    private fun recordInterruption(filesDir: File?, sessionId: String, attemptId: String, reason: String) {
        SessionJournal.record(
            filesDir, sessionId, SessionJournal.COMPACTION_INTERRUPTED,
            mapOf("compactionId" to attemptId, "reason" to reason),
        )
    }

    @Synchronized
    internal fun resetForTest() {
        retries.clear()
        openAttempts.clear()
    }
}

internal const val REASON_CANCELLED = "cancelled"

internal fun ChatViewModel.beginCompactionJournal(
    sessionId: String, anchorIdx: Int, rangeMessages: Int, rangeChars: Int,
): String = CompactionTransaction.begin(compactionJournalDir(), sessionId, anchorIdx, rangeMessages, rangeChars)

internal fun ChatViewModel.settleCompactionJournal(
    sessionId: String, attemptId: String, summaryChars: Int, coveredCount: Int, markerId: String,
) = CompactionTransaction.settle(compactionJournalDir(), sessionId, attemptId, summaryChars, coveredCount, markerId)

internal fun ChatViewModel.abandonCompactionJournal(sessionId: String, attemptId: String, reason: String) =
    CompactionTransaction.abandon(compactionJournalDir(), sessionId, attemptId, reason, System.currentTimeMillis())

private fun ChatViewModel.compactionJournalDir(): File? = runCatching { context.filesDir }.getOrNull()

internal fun ChatViewModel.reportInterruptedCompactions(sessionId: String) {
    runCatching {
        val count = CompactionTransaction.recoverInterrupted(context.filesDir, sessionId)
        if (count > 0) {
            AppLogger.info(ChatViewModel.TAG, "[Compact] $count interrupted compaction attempt(s) recovered from the journal")
        }
    }
}
