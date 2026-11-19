package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.Dispatchers
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
internal suspend fun ChatViewModel.ensureSessionScopedNotices() {
    val sid = activeSessionId
    if (sid == noticesSessionId) return
    noticesSessionId = sid
    val filesDir = context.filesDir
    val saved = withContext(Dispatchers.IO) { NoticeLedgerStore.load(filesDir, sid) }
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
) {
    ensureSessionScopedNotices()
    var ledgerDirty = false
    // [T-state-hydration] The host restores the live task state itself after a
    // compaction/trim/load dropped it. No model tool call and no prompt rule:
    // the snapshot is written into the transcript once and deduped by sha.
    if (stateHydrationPending) {
        stateHydrationPending = false
        val snapshot = SessionStateSnapshot.build(activeSessionId, context)
        if (!snapshot.isNullOrBlank()) {
            val sha = com.openminis.app.util.Digest.sha256Hex(snapshot)
            if (sha != stateSnapshotSha) {
                stateSnapshotSha = sha
                commitHiddenUserNote("${SessionStateSnapshot.MARKER}\n\n$snapshot")
                ledgerDirty = true
            }
        }
        // [T-working-memory-ledger] The trimmed/evicted rounds may include
        // work that no plan/report snapshot covers (raw tool rounds). The
        // ledger's one-line records of undelivered rounds ride the same
        // hydration: delivered once into the transcript, then carried by it.
        WorkingMemoryStore.renderUndelivered(activeSessionId)?.let { note ->
            commitHiddenUserNote(note)
            ledgerDirty = true
        }
    }
    // After a hydration, the state note is the carrier; drop it if it is gone.
    if (stateSnapshotSha != null &&
        agentHistory.none { it.content.contains(SessionStateSnapshot.MARKER) }
    ) {
        stateSnapshotSha = null
        stateHydrationPending = true
        ledgerDirty = true
    }
    consumePlanStepRetryPrompt()?.let { commitHiddenUserNote(it) }
    if (goalCanContinue && goalPrompt != null && goalObjective.isNotBlank()) {
        val goalEvicted = goalAnnouncedCarrier != null &&
            agentHistory.none { it.content.contains(GOAL_NOTE_MARKER) }
        // Hash the OBJECTIVE, not the rendered block: the block carries
        // tokens/elapsed that tick every request and would re-announce.
        val sha = com.openminis.app.util.Digest.sha256Hex(goalObjective)
        if (sha != goalAnnouncedSha || goalEvicted) {
            goalAnnouncedSha = sha
            goalAnnouncedCarrier = GOAL_NOTE_MARKER
            commitHiddenUserNote("$GOAL_NOTE_MARKER in this conversation.\n\n$goalPrompt")
            ledgerDirty = true
        }
    }
    if (ledgerDirty) {
        // Pin the session: a switch during the write must not file this
        // session's delivery record under the next one.
        val sid = noticesSessionId ?: activeSessionId
        val filesDir = context.filesDir
        val state = currentNoticeState()
        withContext(Dispatchers.IO) { NoticeLedgerStore.save(filesDir, sid, state) }
    }
}

internal suspend fun ChatViewModel.commitHiddenUserNote(text: String) {
    if (text.isBlank()) return
    // [T-hidden-user-note] Harness framing, not user speech: Z-Code delivers
    // recalled memory / objective as a `<system-reminder>` notice so the model
    // does not treat it as a request to answer. The no-reply contract lives in
    // the system prompt's action-discipline directive — one place, covering
    // every note. A per-note head was tried (#339) and removed: it sat
    // OUTSIDE the reminder block, so the display pipeline (which strips only
    // `<system-reminder>` blocks) leaked the bookkeeping line into user
    // bubbles, and echo-prone models repeated it. The framing must stay
    // strip-clean end to end.
    val framed = "<system-reminder>\n$text\n</system-reminder>"
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = framed,
            contentParts = listOf(AgentContentPart.Text(framed)),
            isHostGenerated = true,
        ),
    )
    // Read the session id up front: the IO hop can outlive a session switch.
    val sid = activeSessionId
    val partsJson = hostReminderPartsJson(escapeJson(framed))
    val persisted = withContext(Dispatchers.IO) {
        runCatching { chatRepository.appendMessage(sid, "user", partsJson) }.getOrNull()
    } ?: return
    withContext(Dispatchers.Main.immediate) {
        notePersistedUiRow(null, persisted.id)
    }
}

// NOTE: the per-note no-reply head (HOST_NOTE_NO_REPLY_HEAD) that #339
// prepended to every committed note was REMOVED in #342 — the contract
// lives in the system prompt's action-discipline directive, and the frame
// must stay strip-clean end to end. The constant now lives in
// [PreviewText] (data.repository — headless layer), where the single
// stripping authority uses it to clean rows persisted while #339 was live.
// Do not reintroduce per-note frame text here.
