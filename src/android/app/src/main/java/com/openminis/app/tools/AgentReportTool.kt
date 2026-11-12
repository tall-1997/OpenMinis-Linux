package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * [T-work-report] The session's own account of where the work stands.
 *
 * Before this existed the only place a run's state lived was the transcript: a
 * model wrote "done" or "I need you to decide X" as prose, and nothing — not the
 * prompt, not the UI, not the next turn — could tell "finished" from "stalled"
 * from "waiting for a person". That is the mechanism behind a run drifting into a
 * different task and never noticing: no structured field was ever contradicted.
 *
 * A report is a small record with an exclusive status, stored per session and
 * re-rendered into every request, so the current stage survives compaction the
 * same way the plan board does ([AgentPlanStore]).
 *
 * Statuses and the stage derivation follow the prototype's Report / Ticket-stage
 * model (dsh-superagent `docs/001-domain.md:112`, `docs/003-execution.md:7-18`):
 * a pending question outranks a ready report, which outranks waiting, and so on.
 */
object AgentReportStore {
    private const val TAG = "AgentReportStore"
    private const val DIR_NAME = "agent-report"
    private const val FILE_NAME = "report.json"

    /** Reports kept per session; the newest is the live one. */
    const val MAX_HISTORY = 8

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val memory = ConcurrentHashMap<String, MutableList<Report>>()

    /**
     * [T-work-report-staleness] Requests served since the newest report. The
     * `<work_report>` block is a snapshot of a past turn, re-rendered on every
     * request; without a counter nothing distinguishes "reported a moment ago"
     * from "reported two hours of tool rounds ago", and a stale snapshot read
     * as an enforced fact re-anchors every turn to the old step (the model
     * restates the old summary instead of advancing). record/clear reset it.
     */
    private val requestsSinceReport = ConcurrentHashMap<String, Int>()

    /** WORKING-only expiry backstop; see [stagePrompt]. */
    const val STALE_REQUEST_LIMIT = 12

    /** Bumped on every mutation so UI projections can reload. */
    val version = MutableStateFlow(0L)

    /**
     * [T-attention-order] Session ids whose latest report still needs a person
     * (answer, review, blocked, failed). Published at write time instead of
     * polled: the session list would otherwise have to read one file per session
     * to know which rows deserve the top slot.
     */
    val attentionSessions = MutableStateFlow<Set<String>>(emptySet())

    /** The status vocabulary. Order is the order shown to the model. */
    val STATUSES = listOf("progress", "waiting", "ready", "blocked", "failed", "dropped")

    /**
     * Where the session is, derived from the latest report rather than asked of
     * the model. Read the list top to bottom; the first match wins.
     */
    enum class Stage {
        NEEDS_ANSWER,
        NEEDS_REVIEW,
        BLOCKED,
        FAILED,
        WAITING,
        IDLE,
        WORKING,
    }

    @Serializable
    data class Report(
        val status: String,
        val summary: String,
        val blocker: String = "",
        val question: String = "",
        val evidence: String = "",
        val at: Long = System.currentTimeMillis(),
    )

    @Serializable
    private data class Stored(val reports: List<Report> = emptyList())

    /**
     * The ONE normalization point: blank -> "__default__", then
     * [ExecutionCoordinator.ownerSessionId] to the owning session. [file] and
     * [lock] normalize the RAW session id they are given through this exactly
     * once; callers never pass a pre-normalized key.
     *
     * [FIX-R2-12, review §三 空值与边界] [file] used to apply ownerSessionId to
     * the output of [key] again (and loadLocked passed it an already-keyed id)
     * - hidden coupling that only worked while the mapping was idempotent.
     * Collapsed here, so load/save can never disagree about which file a
     * session maps to.
     */
    private fun key(sessionId: String): String =
        ExecutionCoordinator.ownerSessionId(sessionId.ifBlank { "__default__" })

    private fun lock(sessionId: String): ReentrantLock =
        locks.getOrPut(key(sessionId)) { ReentrantLock() }

    private fun file(context: Context?, sessionId: String): File? {
        context ?: return null
        val owner = key(sessionId)
        return File(File(SessionWorkspace.base(context.filesDir, owner), DIR_NAME), FILE_NAME)
    }

    private fun loadLocked(sessionId: String, context: Context?): MutableList<Report> {
        val k = key(sessionId)
        memory[k]?.let { return it }
        val f = file(context, sessionId)
        val loaded: MutableList<Report> = if (f == null || !f.isFile) {
            mutableListOf()
        } else {
            val text = AtomicFileWrite.read(f)
                ?: throw IOException("cannot read report file ${f.absolutePath}")
            runCatching { json.decodeFromString<Stored>(text).reports.toMutableList() }
                .getOrElse { e ->
                    AppLogger.error(TAG, "report file corrupt at ${f.absolutePath}: ${e.message}")
                    throw IOException(
                        "report file ${f.name} is corrupt (${e.message}); delete it to reset",
                        e,
                    )
                }
        }
        memory[k] = loaded
        return loaded
    }

    private fun saveLocked(sessionId: String, context: Context?, reports: List<Report>) {
        val retained = reports.takeLast(MAX_HISTORY)
        memory[key(sessionId)] = retained.toMutableList()
        // [FIX-R2-11, review §三 异步与并发] Atomic read-modify-write: two
        // sessions saving concurrently must not lose one refresh (a plain
        // `version.value += 1` read-modifies between threads).
        version.update { it + 1 }
        val f = file(context, sessionId) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            AtomicFileWrite.write(f, json.encodeToString(Stored(retained)))
        }.onFailure { AppLogger.error(TAG, "save failed at ${f.absolutePath}: ${it.message}") }
    }

    /** Records one report. Throws [IllegalArgumentException] on a contract breach. */
    fun record(
        sessionId: String,
        context: Context?,
        status: String,
        summary: String,
        blocker: String = "",
        question: String = "",
        evidence: String = "",
    ): Report {
        val normalized = status.trim().lowercase()
        require(normalized in STATUSES) {
            "status must be one of ${STATUSES.joinToString(", ")} (got \"$status\")"
        }
        if (summary.isBlank()) {
            throw IllegalArgumentException("summary is required: what changed and what is true now")
        }
        if (normalized == "blocked" || normalized == "failed") {
            if (blocker.isBlank()) {
                throw IllegalArgumentException(
                    "status=$normalized requires blocker: name the concrete missing prerequisite",
                )
            }
        }
        val report = Report(
            status = normalized,
            summary = summary.trim(),
            blocker = blocker.trim(),
            question = question.trim(),
            evidence = evidence.trim(),
        )
        lock(sessionId).withLock {
            val reports = loadLocked(sessionId, context)
            reports.add(report)
            saveLocked(sessionId, context, reports)
        }
        // A fresh report invalidates the staleness count by definition.
        requestsSinceReport[key(sessionId)] = 0
        publishAttention(sessionId, report)
        return report
    }

    fun latest(sessionId: String, context: Context?): Report? = lock(sessionId).withLock {
        loadLocked(sessionId, context).lastOrNull()
    }

    fun history(sessionId: String, context: Context?): List<Report> = lock(sessionId).withLock {
        loadLocked(sessionId, context).toList()
    }

    fun clear(sessionId: String, context: Context?) {
        lock(sessionId).withLock { saveLocked(sessionId, context, emptyList()) }
        requestsSinceReport[key(sessionId)] = 0
        publishAttention(sessionId, null)
    }

    /**
     * [T-work-report-staleness] One request was assembled against the current
     * (possibly stale) block. Called once per agent-loop turn — stream retries
     * re-use the same assembled request and must not inflate the count.
     */
    fun noteRequestServed(sessionId: String) {
        requestsSinceReport.compute(key(sessionId)) { _, v -> (v ?: 0) + 1 }
    }

    /** Keeps [attentionSessions] in step with the newest report. */
    private fun publishAttention(sessionId: String, report: Report?) {
        val stage = stageOf(report)
        val needsPerson = report != null && (
            stage == Stage.NEEDS_ANSWER || stage == Stage.NEEDS_REVIEW ||
                stage == Stage.BLOCKED || stage == Stage.FAILED
            )
        val id = key(sessionId)
        attentionSessions.update { current ->
            if (needsPerson) current + id else current - id
        }
    }

    /** Stage derivation. Null report means the session has not reported yet. */
    fun stageOf(report: Report?): Stage {
        if (report == null) return Stage.WORKING
        if (report.question.isNotBlank()) return Stage.NEEDS_ANSWER
        return when (report.status) {
            "ready" -> Stage.NEEDS_REVIEW
            "blocked" -> Stage.BLOCKED
            "failed" -> Stage.FAILED
            "waiting" -> Stage.WAITING
            "dropped" -> Stage.IDLE
            else -> Stage.WORKING
        }
    }

    /** Human-readable stage token, e.g. `needs-answer`. */
    fun stageToken(stage: Stage): String = stage.name.lowercase().replace('_', '-')

    /** [T-work-report-staleness] Compact relative age so the model can tell a
     * fresh snapshot from a stale one — the old block never showed its age. */
    internal fun reportAgeLabel(atMillis: Long, nowMillis: Long): String {
        val minutes = ((nowMillis - atMillis).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} d ago"
        }
    }

    /**
     * The block that rides on every request. Rendered from the store, so history
     * trimming cannot remove it; omitted only when nothing was ever reported.
     *
     * [T-work-report-staleness] A snapshot that nobody refreshed stops asserting
     * its prose after [STALE_REQUEST_LIMIT] requests: a WORKING summary read as
     * an enforced fact re-anchored every turn to the old step (the model kept
     * restating it instead of advancing). Only WORKING expires — a question,
     * blocker, or ready review is an actionable request to a person and must
     * keep riding until answered. The full block also carries its age and a
     * snapshot caveat, so even a fresh report reads as a snapshot, not live state.
     */
    fun stagePrompt(sessionId: String, context: Context?, nowMillis: Long = System.currentTimeMillis()): String? {
        val report = latest(sessionId, context) ?: return null
        val stage = stageOf(report)
        val served = requestsSinceReport[key(sessionId)] ?: 0
        if (stage == Stage.WORKING && served >= STALE_REQUEST_LIMIT) {
            return buildString {
                append("<work_report>\n")
                append("stage: working (no refreshed report for $served requests — " +
                    "call agent_report to update it before relying on it)\n")
                append("</work_report>")
            }
        }
        return buildString {
            append("<work_report>\n")
            append("stage: ${stageToken(stage)}\n")
            append("last report (${report.status}, ${reportAgeLabel(report.at, nowMillis)}): ${report.summary}\n")
            append(SNAPSHOT_CAVEAT)
            if (report.question.isNotBlank()) {
                append("waiting on the user: ${report.question}\n")
            }
            if (report.blocker.isNotBlank()) append("blocker: ${report.blocker}\n")
            if (report.evidence.isNotBlank()) append("evidence: ${report.evidence}\n")
            append(STAGE_RULE)
            append("\n</work_report>")
        }
    }

    /** [T-work-report-staleness] The old block asserted past conclusions as
     * present-tense enforced facts; this line makes its nature explicit. */
    private const val SNAPSHOT_CAVEAT =
        "This is a snapshot, not live state: later turns may have moved past it — " +
            "check the transcript before acting on it, and never restate this summary " +
            "as a fresh conclusion.\n"

    /** Refresh-first contract: the block repeats until the model reports again. */
    private const val STAGE_RULE =
        "This is a snapshot of the last report, not a new instruction. Continue the user's " +
            "latest task; do not call agent_report merely because this block is present. " +
            "Report again only when the stage actually changed."

    /** Tool-result rendering: what the model sees after reporting. */
    fun render(report: Report): String {
        val stage = stageOf(report)
        return buildString {
            append("reported ${report.status}; stage is now ${stageToken(stage)}.")
            when (stage) {
                Stage.NEEDS_ANSWER ->
                    append(" Stop and let the user answer: do not start other work while this question is open.")
                Stage.NEEDS_REVIEW ->
                    append(" The user reviews next: keep working only if they ask.")
                Stage.BLOCKED, Stage.FAILED ->
                    append(" Do not retry blindly; the blocker above is what a person must clear.")
                Stage.WAITING ->
                    append(" The turn can end here; new input will resume it.")
                Stage.IDLE, Stage.WORKING -> Unit
            }
        }
    }
}

/** [T-work-report] The `agent_report` tool: one structured status per call. */
object AgentReportTool {
    const val NAME = "agent_report"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Report where this run actually stands. Call it when the situation changes, " +
            "not on a timer: a step landed (progress), the turn must wait for new input (waiting), " +
            "the work is done and reviewable (ready), something concrete blocks you (blocked), " +
            "it failed (failed), or there is nothing to do (dropped). " +
            "Put a single concrete decision for the user in `question` - one question, not a list. " +
            "`blocked` and `failed` require `blocker`. Never report ready without evidence of checks run. " +
            "This state is shown to the user and re-injected every turn, so it is how the run stays " +
            "accountable for the task it was given. The <work_report> block re-shows your last " +
            "summary on every request until you report again.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "status" to AgentToolParam(
                "string",
                "progress, waiting, ready, blocked, failed, or dropped.",
                enumValues = AgentReportStore.STATUSES,
            ),
            "summary" to AgentToolParam("string", "What changed and what is true now, in one or two sentences."),
            "blocker" to AgentToolParam("string", "Required for blocked/failed: the concrete missing prerequisite."),
            "question" to AgentToolParam("string", "A single concrete decision the user must make, if any."),
            "evidence" to AgentToolParam("string", "Checks actually run, with their results."),
        ),
        required = listOf("tool_title", "status", "summary"),
        propertyOrdering = listOf("tool_title", "status", "summary", "blocker", "question", "evidence"),
    )

    fun execute(
        argsJson: String,
        sessionId: String = "__default__",
        context: Context? = null,
    ): ToolExecutionResult {
        val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title", NAME) }.getOrDefault(NAME)
        return try {
            val args = JSONObject(argsJson)
            if (args.optString("op") == "clear") {
                AgentReportStore.clear(sessionId, context)
                return ToolExecutionResult("(report cleared)", true, toolTitle = toolTitle)
            }
            val report = AgentReportStore.record(
                sessionId = sessionId,
                context = context,
                status = args.optString("status"),
                summary = args.optString("summary"),
                blocker = args.optString("blocker"),
                question = args.optString("question"),
                evidence = args.optString("evidence"),
            )
            val note = if (report.question.count { it == '?' } > 1) {
                " Note: ask one question. Split the rest into a later report."
            } else {
                ""
            }
            ToolExecutionResult(
                AgentReportStore.render(report) + note,
                true,
                toolTitle = toolTitle,
            )
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
        } catch (e: IOException) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}
