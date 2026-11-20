package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.PendingTurnRow
import com.openminis.app.data.repository.appendTurnRows
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.ActiveRunContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** [T-once-notices] Marker line of a committed recall note. */
internal const val RECALL_NOTE_MARKER = "## Recalled relevant memories"

/** [T-once-notices] Marker line of a committed goal objective note. */
internal const val GOAL_NOTE_MARKER = "Current objective — supersedes any earlier <goal_context>"

/**
 * [T-once-notices] Reset one-shot notice bookkeeping when the session changes.
 *
 * The durable recall keys describe successful attempts, not notes pinned in
 * history. Goal and task-state notices still have their own transcript carriers.
 */
private suspend fun ChatViewModel.ensureSessionScopedNotices(sid: String) {
    if (sid != activeSessionId || sid == noticesSessionId) return
    val filesDir = context.filesDir
    val saved = withContext(Dispatchers.IO) {
        NoticeLedgerStore.load(filesDir, sid).also {
            WorkingMemoryStore.preload(sid, filesDir)
        }
    }
    if (sid != activeSessionId || sid == noticesSessionId) return
    noticesSessionId = sid
    announcedRecallKeys.clear()
    // Earlier versions saved candidate labels before dispatch. They are not delivery evidence.
    announcedRecallKeys.addAll(saved.recallKeys.filter { it.startsWith("v2:") })
    goalAnnouncedSha = saved.goalSha
    stateSnapshotSha = saved.stateSha
    goalAnnouncedCarrier = GOAL_NOTE_MARKER.takeIf { marker ->
        agentHistory.any { it.content.contains(marker) }
    }
    if (goalAnnouncedCarrier == null) goalAnnouncedSha = null
}

internal fun ChatViewModel.currentNoticeState(): NoticeLedgerStore.State = NoticeLedgerStore.State(
    announcedRecallKeys.toSet(), goalAnnouncedSha, stateSnapshotSha,
)

/**
 * [T-once-notices] Everything that used to be rebuilt into every request:
 * plan-step retry steering and the goal objective. Recall is now request-local:
 * persisting it in history would bypass diary TTL on every subsequent turn.
 */
internal suspend fun ChatViewModel.commitSessionNoticesOnce(
    goalObjective: String,
    goalPrompt: String?,
    goalCanContinue: Boolean,
    expectedSessionId: String = activeSessionId,
) {
    val sid = expectedSessionId
    ensureSessionScopedNotices(sid)
    fun ownsNotices() = activeSessionId == sid && noticesSessionId == sid
    if (!ownsNotices()) return
    var ledgerDirty = false
    if (stateHydrationPending) {
        stateHydrationPending = false
        val snapshot = withContext(Dispatchers.IO) { SessionStateSnapshot.build(sid, context) }
        if (!ownsNotices()) return
        if (!snapshot.isNullOrBlank()) {
            val sha = com.openminis.app.util.Digest.sha256Hex(snapshot)
            if (sha != stateSnapshotSha) {
                val committed = commitHiddenUserNote("${SessionStateSnapshot.MARKER}\n\n$snapshot", sid)
                if (!ownsNotices()) return
                if (committed) {
                    stateSnapshotSha = sha
                    ledgerDirty = true
                } else {
                    stateHydrationPending = true
                }
            }
        }
        if (!commitWorkingMemoryHandover(sid) && ownsNotices()) stateHydrationPending = true
    }
    if (!ownsNotices()) return
    // An evicted state note needs a fresh carrier, not only the old hash.
    if (stateSnapshotSha != null && agentHistory.none { it.content.contains(SessionStateSnapshot.MARKER) }) {
        stateSnapshotSha = null
        stateHydrationPending = true
        ledgerDirty = true
    }
    consumePlanStepRetryPrompt()?.let { commitHiddenUserNote(it, sid) }
    if (!ownsNotices()) return
    if (goalCanContinue && goalPrompt != null && goalObjective.isNotBlank()) {
        val goalEvicted = goalAnnouncedCarrier != null && agentHistory.none { it.content.contains(GOAL_NOTE_MARKER) }
        val sha = com.openminis.app.util.Digest.sha256Hex(goalObjective)
        if (sha != goalAnnouncedSha || goalEvicted) {
            val committed = commitHiddenUserNote("$GOAL_NOTE_MARKER in this conversation.\n\n$goalPrompt", sid)
            if (!ownsNotices()) return
            if (committed) {
                goalAnnouncedSha = sha
                goalAnnouncedCarrier = GOAL_NOTE_MARKER
                ledgerDirty = true
            }
        }
    }
    if (ledgerDirty) {
        val state = currentNoticeState()
        withContext(Dispatchers.IO) { NoticeLedgerStore.save(context.filesDir, sid, state) }
    }
}

private suspend fun ChatViewModel.commitWorkingMemoryHandover(sid: String): Boolean = withContext(Dispatchers.IO) {
    if (activeSessionId != sid) return@withContext false
    val filesDir = context.filesDir
    val note = WorkingMemoryStore.renderUndelivered(sid, filesDir) ?: return@withContext true
    var committed = false
    try {
        commitHiddenUserNote(note, sid) { committed = true }
    } finally {
        withContext(NonCancellable) {
            if (committed) WorkingMemoryStore.ackDelivered(sid, filesDir)
            else WorkingMemoryStore.undoDelivery(sid, filesDir)
        }
    }
}

/**
 * [T-hidden-note-secret-mask] The framing choke point every host note passes
 * through: mask credentials BEFORE persisting. Producers (ledger/failure)
 * already mask, but recall/goal/state/plan notes can carry user-confided text
 * from memory files. Masking here is the defense-in-depth layer that keeps the
 * chat DB (and therefore export/backup) clean.
 */
internal fun frameHiddenNote(text: String): String {
    // [T-hidden-note-frame-safe] memory_write can store a tool output that
    // contains `</system-reminder>`; without neutralization the frame closes
    // early and the rest of the note becomes raw user speech.
    val safe = text.replace(FRAME_TAGS, "[tag]")
    return "<system-reminder>\n" + com.openminis.app.util.SecretMasking.mask(safe) + "\n</system-reminder>"
}

/** Opening/closing harness frame tags, with optional attributes. */
private val FRAME_TAGS = Regex("</?(?:system-reminder|context-summary)(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)

internal suspend fun ChatViewModel.commitHiddenUserNote(
    text: String,
    expectedSessionId: String = activeSessionId,
    expectedEpoch: Long = snapshotResumeEpoch(),
    onPersisted: () -> Unit = {},
): Boolean {
    val sid = expectedSessionId
    val run = ActiveRunContext.current()
    fun ownsNote() = ownsTranscript(sid, expectedEpoch, run)
    if (text.isBlank() || !ownsNote()) return false
    val framed = frameHiddenNote(text)
    val partsJson = hostReminderPartsJson(escapeJson(framed))
    val caller = currentCoroutineContext()
    suspend fun persist(): Boolean = withContext(NonCancellable) {
        val persisted = withContext(Dispatchers.IO) {
            caller.ensureActive()
            if (!ownsNote()) return@withContext null
            try {
                chatRepository.appendTurnRows(sid, listOf(PendingTurnRow("user", partsJson)), ownsWrite = ::ownsNote)
                    .singleOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warning(ChatViewModel.TAG, "Could not persist host note: ${e.javaClass.simpleName}")
                null
            }
        } ?: return@withContext false
        onPersisted()
        withContext(Dispatchers.Main.immediate) {
            if (!ownsNote()) return@withContext
            appendBoundedHistory(LLMMessage(
                role = LLMMessage.Role.USER,
                content = framed,
                contentParts = listOf(AgentContentPart.Text(framed)),
                dbMessageId = persisted.id,
                isHostGenerated = true,
            ))
            notePersistedUiRow(null, persisted.id)
        }
        true
    }
    return if (run == null) persist() else run.withPersistencePermit { persist() } == true
}

// NOTE: the per-note no-reply head (HOST_NOTE_NO_REPLY_HEAD) that #339
// prepended to every committed note was REMOVED in #342 — the contract
// lives in the system prompt's action-discipline directive, and the frame
// must stay strip-clean end to end. The constant now lives in
// [PreviewText] (data.repository — headless layer), where the single
// stripping authority uses it to clean rows persisted while #339 was live.
// Do not reintroduce per-note frame text here.
