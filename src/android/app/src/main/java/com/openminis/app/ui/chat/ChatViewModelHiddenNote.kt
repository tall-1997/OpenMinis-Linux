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
        val filesDir = context.filesDir
        val state = NoticeLedgerStore.State(announcedRecallKeys.toSet(), goalAnnouncedSha)
        withContext(Dispatchers.IO) { NoticeLedgerStore.save(filesDir, activeSessionId, state) }
    }
}

internal suspend fun ChatViewModel.commitHiddenUserNote(text: String) {
    if (text.isBlank()) return
    // [T-hidden-user-note] Harness framing, not user speech: ZCode delivers
    // recalled memory / objective as a `<system-reminder>` notice so the model
    // does not treat it as a request to answer.
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
