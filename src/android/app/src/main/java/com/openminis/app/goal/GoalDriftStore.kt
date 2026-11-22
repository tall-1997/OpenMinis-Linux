package com.openminis.app.goal

import org.json.JSONObject
import java.io.File

/**
 * [T-goal-drift-reconciler] Durable per-session state for the goal drift
 * reconciler ([GoalDriftReconciler]).
 *
 * The reconciler counts consecutive tool turns that leave the plan board
 * untouched while the session Goal is ACTIVE. That counter must survive a
 * process restart (a long task outlives the app being killed), and it gets
 * its own store instead of riding [NoticeLedgerStore]: different lifecycle,
 * different reset rules, and the delivered-once marker here keys on the board
 * fingerprint rather than on rendered text. Same file pattern though — one
 * JSON blob per session, delete-on-fresh, never allowed to fail a turn.
 */
internal object GoalDriftStore {

    private const val STALL_COUNT_VERSION = 1

    data class DriftState(
        /** Fingerprint of the board as last seen; null when no board existed. */
        val lastBoardSha: String? = null,
        /** Consecutive tool turns with an unchanged fingerprint while the Goal was ACTIVE. */
        val stallTurns: Int = 0,
        /** The stalled fingerprint a drift nudge was already delivered for — delivered-once per distinct stalled state. */
        val nudgedBoardSha: String? = null,
    )

    private fun dir(filesDir: File): File = File(filesDir, "goal-drift")

    private fun file(filesDir: File, sessionId: String): File =
        File(dir(filesDir), "${sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    fun load(filesDir: File, sessionId: String): DriftState {
        val file = file(filesDir, sessionId)
        if (!file.exists()) return DriftState()
        return runCatching {
            val json = JSONObject(file.readText())
            DriftState(
                lastBoardSha = json.optString("lastBoardSha").takeIf { it.isNotBlank() },
                // Legacy counts mixed tool and delivery ticks. Keep the board
                // baseline and delivered marker, but don't inherit those ticks.
                stallTurns = if (json.optInt("stallCountVersion") == STALL_COUNT_VERSION) {
                    json.optInt("stallTurns", 0).coerceAtLeast(0)
                } else 0,
                nudgedBoardSha = json.optString("nudgedBoardSha").takeIf { it.isNotBlank() },
            )
        }.getOrDefault(DriftState())
    }

    fun save(filesDir: File, sessionId: String, state: DriftState) {
        runCatching {
            val file = file(filesDir, sessionId)
            if (state == DriftState()) {
                // Fresh state has nothing to remember — drop the file so a
                // completed/cleared session does not leave residue behind.
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject()
                    .put("stallCountVersion", STALL_COUNT_VERSION)
                    .put("lastBoardSha", state.lastBoardSha ?: "")
                    .put("stallTurns", state.stallTurns)
                    .put("nudgedBoardSha", state.nudgedBoardSha ?: "")
                    .toString(),
            )
        }
    }

    /**
     * [T-session-sidecar-cleanup] Deleted session: drop the drift state file.
     * Part of [com.openminis.app.ui.chat.SessionTeardown.deleteAll] —
     * the sidecar files that [ChatRepository.deleteSession]'s workspace wipe
     * does not reach. Idempotent.
     */
    fun deleteStored(filesDir: File, sessionId: String) {
        runCatching { file(filesDir, sessionId).delete() }
    }
}
