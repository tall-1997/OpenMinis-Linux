package com.openminis.app.ui.chat

import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.data.repository.RevertStageOutcome
import com.openminis.app.data.repository.RevertStageRegistry
import com.openminis.app.data.repository.RevertStageStore
import com.openminis.app.data.repository.clearSessionRevert
import com.openminis.app.data.repository.commitSessionRevert
import com.openminis.app.data.repository.stageSessionRevert
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch

/**
 * [T-p9-revert] ViewModel side of the three-stage revert
 * (ChatRepositoryRevert.kt). stage() rolls files back to the boundary and
 * hides the doomed tail IN MEMORY — the DB rows stay until the next send
 * commits the cut. clear() restores the original snapshot and re-appends
 * the captured tail. commit() is wired into sendMessage: the cut lands
 * before the new user message is persisted.
 *
 * The banner state comes from RevertStageRegistry's per-session flow —
 * the same source the repository-level supersede hook updates, so a cut
 * driven by retry/edit/delete drops the banner without any ViewModel
 * plumbing.
 */

/**
 * The tails trimmed by stage(), keyed by OWNING session id so clear() can
 * re-append them verbatim. Keyed — not a single file-level var — because
 * several ChatViewModel instances (and one VM hosting several sessions over
 * its lifetime) interleave on the main dispatcher: a single var let another
 * session's [applyStagedRevertTrim] wipe this session's tail (a cached-VM
 * revisit then showed the banner but Undo silently re-appended nothing), and
 * let a draft's early-returning loadSession carry a previous session's tail
 * into the promoted session's next stage (undo spliced foreign message rows
 * into the new chat). Re-derived on every session load
 * ([applyStagedRevertTrim]), so it survives process death; all access
 * happens on the main dispatcher (the load funnel assigns _messages on
 * main, and the actions run in viewModelScope).
 */
private val revertTrimmedTails = HashMap<String, List<ChatMessage>>()

internal val ChatViewModel.revertStageFlow
    get() = RevertStageRegistry.flowFor(realSessionId.takeIf { it.isNotEmpty() } ?: sessionId)

/** Long-press "Revert to here": stage the revert, hide the tail, toast. */
internal fun ChatViewModel.revertToHere(messageId: String) {
    if (_isStreaming.value) return
    viewModelScope.launch {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
        when (val outcome = chatRepository.stageSessionRevert(context, sid, messageId)) {
            is RevertStageOutcome.Ok -> {
                trimMessagesToBoundary(sid, outcome.record.boundaryMessageId)
                toast(R.string.chat_revert_done_toast)
            }
            RevertStageOutcome.Empty -> toast(R.string.chat_revert_empty_toast)
            is RevertStageOutcome.Failed -> {
                AppLogger.warning("RevertExt", "stage failed: ${outcome.reason}")
                toast(R.string.chat_revert_failed_toast)
            }
        }
    }
}

/**
 * Banner "Undo": restore the original snapshot, re-append the tail. The DB
 * was never cut, so no history rebuild is needed — agentHistory still
 * holds the tail and is already consistent with the DB.
 */
internal fun ChatViewModel.undoRevert() {
    viewModelScope.launch {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
        if (RevertStageRegistry.get(sid) == null) return@launch
        val filesOk = chatRepository.clearSessionRevert(context, sid)
        val tail = revertTrimmedTails.remove(sid).orEmpty()
        if (tail.isNotEmpty()) {
            _messages.value = _messages.value + tail
        }
        if (filesOk) toast(R.string.chat_revert_cleared_toast)
    }
}

/**
 * [T-p9-revert-commit-on-send] Finalize a staged revert before the new
 * user message is persisted. True when nothing is staged or the cut
 * verified clean; false drops the send (fail-closed, the same rule as
 * truncateBeforeEdit's [T-anchor-lookup-fail-closed]).
 */
internal suspend fun ChatViewModel.commitStagedRevertBeforeSend(sid: String): Boolean {
    val record = RevertStageRegistry.get(sid) ?: return true
    revertTrimmedTails.remove(sid)
    val ok = chatRepository.commitSessionRevert(context, sid, record)
    if (ok) {
        // Mirror truncateBeforeEdit's post-cut hygiene exactly: agentHistory
        // still holds the cut tail in memory, so it MUST be rebuilt from the
        // truncated DB before the new turn assembles its context — the same
        // leak class as [T-edit-digest-leak]. The digest and compact markers
        // describe rows that no longer exist.
        awaitBoundedHistoryRebuild(sid)
        llmDigestLines.clear()
        llmDigestOmitted = 0
        reconcileCompactMarkersAfterTruncation(sid)
    }
    return ok
}

/**
 * [T-p9-revert-recovery] A staged revert survives process death on disk.
 * After a session load, re-register the record (the banner comes back)
 * and re-apply the in-memory tail trim. Called from the load funnel.
 */
internal fun ChatViewModel.applyStagedRevertTrim(sessionId: String) {
    revertTrimmedTails.remove(sessionId)
    val record = RevertStageStore.load(context.filesDir, sessionId) ?: run {
        // No record on disk: clear any stale registry entry for this session.
        RevertStageRegistry.forceDrop(sessionId)
        return
    }
    RevertStageRegistry.set(sessionId, record, RevertStageStore.fileFor(context.filesDir, sessionId))
    trimMessagesToBoundary(sessionId, record.boundaryMessageId)
}

private fun ChatViewModel.trimMessagesToBoundary(sessionId: String, boundaryMessageId: String) {
    val painted = _messages.value
    val idx = painted.indexOfFirst { it.id == boundaryMessageId }
    if (idx < 0) {
        // Boundary not in the painted list (compacted/offloaded?) — the
        // files are still rolled back and the record still stands; the
        // tail just stays visible until the next send commits.
        AppLogger.warning("RevertExt", "boundary $boundaryMessageId not in painted list — tail stays visible")
        return
    }
    // Chained stage: the previously trimmed tail (everything after the
    // PREVIOUS boundary) is still hidden and still belongs to the history
    // after THIS boundary — splice it on. The painted list only reaches
    // the previous boundary, so painted-tail + old tail reconstructs the
    // full hidden window [boundary+1 .. end] for undo to re-append. The old
    // tail is keyed to THIS session — a previous session's tail must never
    // splice into it (the cross-session contamination the keyed map fixes).
    revertTrimmedTails[sessionId] =
        painted.subList(idx + 1, painted.size).toList() + revertTrimmedTails[sessionId].orEmpty()
    _messages.value = painted.subList(0, idx + 1).toList()
}

private fun ChatViewModel.toast(resId: Int) {
    Toast.makeText(context.applicationContext, context.getString(resId), Toast.LENGTH_SHORT).show()
}
