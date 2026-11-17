package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.tools.FileCheckpointStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-tool-undo-replay] ZCode-style per-file undo and turn-level rollback.
 *
 * - Single file: restore that path to its pre-turn state — the EARLIEST
 *   checkpoint of the path in the turn window (restore|delete two-state,
 *   mirroring ZCode's `file-rewind`: existed → write pre-image back;
 *   created-in-turn → delete).
 * - Rollback all: same inverse for every changed path of the turn.
 * - A guard snapshot (source="undo-guard") is captured BEFORE the inverse
 *   lands — the DeepSeek fail-closed boundary — so an undo is itself
 *   undoable through later cards.
 *
 * Snapshot resolution: FileCheckpointStore entries for the path whose
 * createdAt falls inside the turn window built from the path's own block
 * timestamps. `startTimeMs`/`durationMs` are persisted with the block, so
 * the window survives DB reload. An empty window (snapshot pruned or the
 * capture never ran) degrades to an honest failure message — a wrong
 * restore would be worse.
 */
private const val TAG_UNDO = "ChatToolUndo"

/** Window margins around a path's own block timestamps. */
private const val UNDO_LEAD_MS = 2_000L
private const val UNDO_TAIL_MS = 5_000L

data class TurnFileUndoState(
    val messageId: String,
    val path: String,
    val checkpointId: String,
    val existedBefore: Boolean,
)

internal class ChatToolUndoController(private val vm: ChatViewModel) {
    private val _revertedKeys = MutableStateFlow<Set<String>>(emptySet())
    val revertedKeys: StateFlow<Set<String>> = _revertedKeys.asStateFlow()

    fun markReverted(messageId: String, path: String) {
        _revertedKeys.value = _revertedKeys.value + TurnChanges.revertedKey(messageId, path)
    }
}

/**
 * Resolves the pre-turn snapshot for one changed path of a message. Null =
 * no snapshot in the turn window (pruned / capture missing) → undo refused.
 */
internal fun resolveTurnFileUndoState(
    change: TurnChanges.PathChange,
    checkpoints: List<FileCheckpointStore.Checkpoint>,
): TurnFileUndoState? {
    val start = change.firstStartMs - UNDO_LEAD_MS
    val end = change.lastEndMs + UNDO_TAIL_MS
    val earliest = checkpoints
        .asSequence()
        .filter { it.entries.any { e -> e.path == change.path } }
        .filter { it.createdAt in start..end }
        .minByOrNull { it.createdAt }
        ?: return null
    val entry = earliest.entries.first { it.path == change.path }
    return TurnFileUndoState(
        messageId = "",
        path = change.path,
        checkpointId = earliest.id,
        existedBefore = entry.existed,
    )
}

/**
 * One-tap undo for a single changed file of a completed turn card.
 * Results land as an assistant notice (same channel as code-run).
 */
fun ChatViewModel.undoTurnFileChange(messageId: String, path: String) {
    val message = _messages.value.firstOrNull { it.id == messageId } ?: return
    val change = TurnChanges.collect(message.toolBlocks).firstOrNull { it.path == path } ?: return
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) {
        insertUndoNotice(context.getString(R.string.turn_changes_undo_no_session))
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        val undoState = withContext(Dispatchers.IO) {
            resolveTurnFileUndoState(change, FileCheckpointStore.list(context, sid))
        }
        if (undoState == null) {
            insertUndoNotice(
                context.getString(R.string.turn_changes_undo_no_snapshot, path),
            )
            return@launch
        }
        if (!captureUndoGuard(sid, listOf(path))) return@launch
        when (val result = restoreViaCheckpoints(sid, undoState)) {
            is UndoOutcome.Restored -> {
                toolUndo.markReverted(messageId, path)
                insertUndoNotice(result.message)
            }
            is UndoOutcome.Failed -> insertUndoNotice(context.getString(R.string.turn_changes_undo_failed, result.reason))
        }
    }
}

/** Rollback EVERY changed file of the turn in one tap. */
fun ChatViewModel.undoAllTurnChanges(messageId: String) {
    val message = _messages.value.firstOrNull { it.id == messageId } ?: return
    val changes = TurnChanges.collect(message.toolBlocks)
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) {
        insertUndoNotice(context.getString(R.string.turn_changes_rollback_no_session))
        return
    }
    if (changes.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val checkpoints = FileCheckpointStore.list(context, sid)
        val resolved = changes.mapNotNull { change ->
            resolveTurnFileUndoState(change, checkpoints)?.let { change.path to it }
        }
        val unresolved = changes.map { it.path } - resolved.map { it.first }
        if (resolved.isEmpty()) {
            insertUndoNotice(context.getString(R.string.turn_changes_rollback_no_snapshots))
            return@launch
        }
        if (!captureUndoGuard(sid, resolved.map { it.first })) return@launch

        val restored = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((path, undoState) in resolved) {
            when (val outcome = restoreViaCheckpoints(sid, undoState)) {
                is UndoOutcome.Restored -> {
                    toolUndo.markReverted(messageId, path)
                    restored.add(path)
                }
                is UndoOutcome.Failed -> failed.add(path)
            }
        }
        val sep = context.getString(R.string.turn_changes_list_separator)
        val lines = buildList {
            if (restored.isNotEmpty()) {
                add(context.getString(R.string.turn_changes_rollback_done, restored.size))
            }
            if (failed.isNotEmpty()) {
                add(context.getString(R.string.turn_changes_rollback_failed_files, failed.joinToString(sep)))
            }
            if (unresolved.isNotEmpty()) {
                add(context.getString(R.string.turn_changes_rollback_skipped, unresolved.joinToString(sep)))
            }
        }
        if (lines.isNotEmpty()) insertUndoNotice(lines.joinToString("\n"))
    }
}

/**
 * Guard capture BEFORE the inverse lands (undo stays undoable). A failed
 * guard aborts the whole undo — fail-closed, DeepSeek boundary semantics.
 */
private fun ChatViewModel.captureUndoGuard(
    sid: String,
    paths: List<String>,
): Boolean {
    val guard = FileCheckpointStore.capture(
        context, sid, paths,
        label = "undo guard",
        source = "undo-guard",
    )
    if (guard.checkpoint == null) {
        insertUndoNotice(
            context.getString(
                R.string.turn_changes_undo_guard_failed,
                guard.reason ?: context.getString(R.string.turn_changes_unknown_reason),
            ),
        )
        return false
    }
    return true
}

private sealed class UndoOutcome {
    data class Restored(val message: String) : UndoOutcome()
    data class Failed(val reason: String) : UndoOutcome()
}

/** restore|delete two-state inverse through the checkpoint store. */
private suspend fun ChatViewModel.restoreViaCheckpoints(
    sid: String,
    undoState: TurnFileUndoState,
): UndoOutcome = withContext(Dispatchers.IO) {
    try {
        val restored = FileCheckpointStore.restoreTargets(
            context, sid, undoState.checkpointId, setOf(undoState.path),
        )
        when {
            !restored.success ->
                UndoOutcome.Failed(restored.lines.joinToString("\n").ifEmpty { context.getString(R.string.turn_changes_restore_failed) })
            undoState.existedBefore -> UndoOutcome.Restored(
                context.getString(R.string.turn_changes_undo_restored, undoState.path),
            )
            else -> UndoOutcome.Restored(
                context.getString(R.string.turn_changes_undo_deleted, undoState.path),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG_UNDO, "restore failed", e)
        UndoOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
}

/** Assistant notice insert (final text in one shot, persisted as one part). */
private fun ChatViewModel.insertUndoNotice(text: String) {
    val id = java.util.UUID.randomUUID().toString()
    _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
        id = id,
        role = "assistant",
        content = text,
    ))
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val parts = """[{"type":"text","value":${JSONObject.quote(text)}}]"""
            chatRepository.appendMessage(
                sessionId = sid,
                role = "assistant",
                partsJson = parts,
                modelSnapshot = currentModelSnapshot(),
            )
        } catch (e: Exception) {
            Log.w(TAG_UNDO, "persist undo notice failed: ${e.message}")
        }
    }
}

/**
 * Unwrap exceptions thrown inside callbackFlow.
 * callbackFlow wraps internal throws into CancellationException(cause=original).
 * This extracts the original LLMError if present.
 * (Moved from a ChatViewModel member to a top-level helper when the
 * ViewModel hit its architecture-policy line ceiling.)
 */
internal fun unwrapFlowException(e: Throwable): Throwable {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is com.openminis.app.data.model.LLMError) return cause
        cause = cause.cause
    }
    return e
}
