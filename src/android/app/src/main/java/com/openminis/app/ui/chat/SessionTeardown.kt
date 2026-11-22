package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.data.UnreadRegistry
import com.openminis.app.goal.GoalDriftStore
import com.openminis.app.service.SessionBadgeStore
import com.openminis.app.tools.deleteReceiptFiles
import java.io.File

/**
 * [T-session-sidecar-cleanup] Deleted-session teardown: ONE entry point for
 * every host-held trace of a chat, so "delete the conversation" deletes the
 * conversation's files and records with it.
 *
 * What [com.openminis.app.data.ChatRepository.deleteSession] already covers:
 * the DB rows (Room CASCADE covers messages / message_versions /
 * compact_markers) and the whole `minis-sessions/<id>/` workspace via
 * [com.openminis.app.sandbox.SessionWorkspace.deleteEntire] — plan board,
 * sub-agent batches, mandates, reports, per-session memory.
 *
 * What this janitor adds (none of it was wired into the delete path before —
 * a deleted chat kept paying these costs forever):
 *  - sidecar files at the filesDir root: working-memory JSONL, notices JSON,
 *    goal-drift JSON (from the drift reconciler), legacy tool-receipts JSONL;
 *  - the in-memory working-memory ledger (a stale one would resurrect the
 *    deleted log on the next write);
 *  - UI-state traces: the session badge and the unread registry.
 *
 * Wire-in lives at the UI delete call sites (ChatViewModel cleanupIfEmptyOnExit
 * / SessionListViewModel), not in the repository: these stores are internal to
 * ui.chat and the architecture policy forbids data-layer imports of ui.
 * Idempotent; never throws.
 */
internal object SessionTeardown {

    /** Full teardown for a deleted session: files + in-memory + UI-state traces. */
    fun onSessionDeleted(context: Context, sessionId: String) {
        deleteAll(context.filesDir, sessionId)
        // tool-receipts-v1 (the modern store) lives under noBackupFilesDir:
        // per-session files are <sessionDigest>.json/.lock. The 256-bucket
        // shard files are shared across sessions and deliberately kept.
        runCatching {
            deleteReceiptFiles(File(context.noBackupFilesDir, "tool-receipts-v1"), sessionId)
        }
        runCatching { SessionBadgeStore.clear(sessionId) }
        runCatching { UnreadRegistry.forget(sessionId) }
    }

    /** Sidecar files only (filesDir-scoped; JVM-testable without a Context). */
    fun deleteAll(filesDir: File, sessionId: String) {
        WorkingMemoryStore.delete(filesDir, sessionId)           // working-memory/<safe>.jsonl + in-memory ledger
        NoticeLedgerStore.deleteStored(filesDir, sessionId)      // notices/<safe>.json
        GoalDriftStore.deleteStored(filesDir, sessionId)         // goal-drift/<safe>.json
        LegacyToolReceiptStore.deleteStored(filesDir, sessionId) // tool-receipts/<id>.jsonl (legacy, disambiguated)
        SessionJournal.deleteStored(filesDir, sessionId)         // session-journal/<safe>.jsonl (≤4MB, the biggest one)
    }
}
