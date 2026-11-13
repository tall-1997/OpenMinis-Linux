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
 * State is durable ([NoticeLedgerStore]): the in-memory set used to die with
 * the process, so every restart re-delivered each recalled memory and
 * re-announced the goal — the repeated-injection bug in a different disguise.
 * On load the record is reconciled with the transcript: if the note it refers
 * to is no longer in history (compaction/trim), the delivery record is dropped
 * so the memory can surface again once.
 */
internal suspend fun ChatViewModel.ensureSessionScopedNotices() {
    val sid = activeSessionId
    if (sid == noticesSessionId) return
    noticesSessionId = sid
    val filesDir = context.filesDir
    val saved = withContext(Dispatchers.IO) { NoticeLedgerStore.load(filesDir, sid) }
    announcedRecallKeys.clear()
    announcedRecallKeys.addAll(saved.recallKeys)
    goalAnnouncedSha = saved.goalSha
    stateSnapshotSha = saved.stateSha
    announcedRecallCarrier = RECALL_NOTE_MARKER.takeIf { marker ->
        agentHistory.any { it.content.contains(marker) }
    }
    goalAnnouncedCarrier = GOAL_NOTE_MARKER.takeIf { marker ->
        agentHistory.any { it.content.contains(marker) }
    }
    // The transcript is the source of truth: keys without their note in
    // context are stale, and the goal must be announced again.
    if (announcedRecallCarrier == null) announcedRecallKeys.clear()
    if (goalAnnouncedCarrier == null) goalAnnouncedSha = null
}

/**
 * [T-once-notices] Everything that used to be rebuilt into every request:
 * plan-step retry steering, recalled memory, and the goal objective. Each is
 * committed once and then carried by history.
 */
internal suspend fun ChatViewModel.commitSessionNoticesOnce(
    goalObjective: String,
    goalPrompt: String?,
    goalCanContinue: Boolean,
) {
    ensureSessionScopedNotices()
    var ledgerDirty = false
    // [T-recall-once] Eviction, not repetition, is the reason to surface a
    // memory again: compaction/trimming can drop the committed note, and a
    // memory the model can no longer see must be allowed to return ONCE.
    if (announcedRecallCarrier != null &&
        agentHistory.none { it.content.contains(RECALL_NOTE_MARKER) }
    ) {
        announcedRecallKeys.clear()
        announcedRecallCarrier = null
        ledgerDirty = true
    }
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
    newRecallReminder()?.let { note ->
        commitHiddenUserNote(note)
        announcedRecallCarrier = RECALL_NOTE_MARKER
        ledgerDirty = true
    }
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
        val state = NoticeLedgerStore.State(
            announcedRecallKeys.toSet(),
            goalAnnouncedSha,
            stateSnapshotSha,
        )
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
        ),
    )
    // Read the session id up front: the IO hop can outlive a session switch.
    val sid = activeSessionId
    val partsJson = """[{"type":"text","value":${escapeJson(framed)}}]"""
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
