package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-generation-run] Minimal generation-run ledger for crash recovery
 * (Kelivo GenerationRun, scoped down): every model turn stamps a run
 * record — session, turn anchor, model, status, timestamps — persisted
 * under `filesDir/diagnostics/generation-runs/`. On a crash mid-generation
 * the half-written stream is discoverable afterwards (which session, which
 * model, how far it got), instead of vanishing silently.
 *
 * [T-diagnostics-host-path] The first cut wrote to a literal
 * `/var/minis/...` constant — a PRoot-guest path that does not exist in the
 * app process — so the ledger never wrote a file and the startup abandoned
 * scan always returned empty. Runs now live in an app-level diagnostics
 * directory: a real host path, one file per run (runId-keyed, so sessions
 * never collide), and a single directory the startup scan can walk — the
 * in-memory folder routing [com.openminis.app.sandbox.SessionWorkspace.hostDir]
 * relies on is empty at app start, so a per-session layout would make the
 * global abandoned scan miss runs filed under a project workspace.
 *
 * The streaming content itself is checkpointed by the existing per-turn
 * append pipeline; this ledger is the recovery index over it.
 */
object GenerationRunStore {
    private const val TAG = "GenerationRun"

    enum class Status { RUNNING, DONE, FAILED, ABANDONED }

    data class Run(
        val runId: String,
        val sessionId: String,
        val modelId: String,
        val status: Status,
        val startedAtMs: Long,
        val finishedAtMs: Long? = null,
        val turnAnchor: String = "",
    )

    private const val KEEP = 40

    fun dir(context: Context): File = File(context.filesDir, "diagnostics/generation-runs")

    fun start(context: Context, sessionId: String, modelId: String, turnAnchor: String = ""): String {
        val runId = java.util.UUID.randomUUID().toString().take(8)
        val run = Run(runId, sessionId, modelId, Status.RUNNING, System.currentTimeMillis(), null, turnAnchor)
        write(context, run)
        return runId
    }

    fun finish(context: Context, runId: String, ok: Boolean) {
        val file = File(dir(context), "$runId.json")
        if (!file.exists()) return
        val run = read(file) ?: return
        write(context, run.copy(status = if (ok) Status.DONE else Status.FAILED, finishedAtMs = System.currentTimeMillis()))
        trim(dir(context))
    }

    /** Runs that were RUNNING when the process died — crash candidates. */
    fun abandoned(context: Context): List<Run> {
        val dir = dir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.mapNotNull { read(it) }
            ?.filter { it.status == Status.RUNNING }
            ?.sortedBy { it.startedAtMs }
            ?: emptyList()
    }

    fun markAbandoned(context: Context, runId: String) {
        val file = File(dir(context), "$runId.json")
        val run = read(file) ?: return
        write(context, run.copy(status = Status.ABANDONED, finishedAtMs = System.currentTimeMillis()))
    }

    /**
     * Runs for one session that were marked abandoned by the most recent
     * launch scan — i.e. the previous process died mid-generation on this
     * session. The UI offers a recovery hint from this list.
     */
    fun recentlyAbandonedFor(context: Context, sessionId: String, windowMs: Long = 10 * 60_000): List<Run> {
        val dir = dir(context)
        if (!dir.exists()) return emptyList()
        val cutoff = System.currentTimeMillis() - windowMs
        return dir.listFiles()?.mapNotNull { read(it) }
            ?.filter { it.sessionId == sessionId && it.status == Status.ABANDONED && (it.finishedAtMs ?: 0L) >= cutoff }
            ?.sortedBy { it.startedAtMs }
            ?: emptyList()
    }

    private fun write(context: Context, run: Run) {
        runCatching {
            val dir = dir(context)
            dir.mkdirs()
            val json = org.json.JSONObject()
                .put("runId", run.runId)
                .put("sessionId", run.sessionId)
                .put("modelId", run.modelId)
                .put("status", run.status.name)
                .put("startedAtMs", run.startedAtMs)
                .put("finishedAtMs", run.finishedAtMs ?: 0L)
                .put("turnAnchor", run.turnAnchor)
            File(dir, "${run.runId}.json").writeText(json.toString(2))
        }.onFailure {
            AppLogger.error(TAG, "generation run write failed: ${it.message}")
        }
    }

    private fun read(file: File): Run? = runCatching {
        val obj = org.json.JSONObject(file.readText())
        Run(
            runId = obj.getString("runId"),
            sessionId = obj.getString("sessionId"),
            modelId = obj.getString("modelId"),
            status = runCatching { Status.valueOf(obj.getString("status")) }.getOrDefault(Status.ABANDONED),
            startedAtMs = obj.getLong("startedAtMs"),
            finishedAtMs = obj.optLong("finishedAtMs", 0L).takeIf { it > 0 },
            turnAnchor = obj.optString("turnAnchor"),
        )
    }.getOrNull()

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { runCatching { it.delete() } }
    }
}
