package com.openminis.app.vscreen

import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager

/**
 * [T-android-vscreen2] Move tasks between displays and list what is where.
 *
 * Two backends, in priority order, probed at RUNTIME — nothing is assumed:
 *
 *  1. **Shizuku user service** ([VscreenServiceClient] → [IVscreenService]):
 *     binder-level IActivityTaskManager.moveRootTaskToDisplay + getTasks —
 *     the reliable path wherever the user service machinery works.
 *  2. **Shell probe table** ([MOVE_CANDIDATES] / [LIST_CANDIDATES]): the
 *     classic shell verbs, tried one at a time against the REAL task, with
 *     the result verified through task listing when that is available.
 *     A candidate that merely exits 0 is reported as "unverified".
 *
 * If every candidate fails, the exact error is
 * "task move unavailable on this ROM; ask the user to handle it manually".
 *
 * The `wm` command family is deliberately ABSENT from the table: it has no
 * task-move verb in AOSP (`wm size/density/dismiss-keyguard/…` only), so
 * including it would be a hardcoded guess that can never succeed.
 */
class TaskMover(
    private val shell: (Array<String>, Long) -> ShizukuManager.ProcessResult,
    private val service: () -> IVscreenService?,
) {

    data class TaskRow(val taskId: Int, val displayId: Int, val packageName: String, val topActivity: String)

    sealed class ListOutcome {
        data class Rows(val rows: List<TaskRow>, val via: String) : ListOutcome()
        data class Unavailable(val message: String) : ListOutcome()
    }

    sealed class MoveOutcome {
        data class Moved(val via: String, val verified: Boolean) : MoveOutcome()
        data class Failed(val message: String) : MoveOutcome()
        data class Unavailable(val message: String) : MoveOutcome()
    }

    sealed class ImePolicyOutcome {
        data object Applied : ImePolicyOutcome()
        data object Refused : ImePolicyOutcome()
        data object NoService : ImePolicyOutcome()
    }

    /** Cached working shell move candidate (name of [MOVE_CANDIDATES] entry). */
    @Volatile private var workingMoveCandidate: String? = null

    /** Cached working shell list candidate. */
    @Volatile private var workingListCandidate: String? = null

    // ── listing ──────────────────────────────────────────────────────────

    /** All tasks, user service first, then the shell probe table. */
    fun listTasks(): ListOutcome {
        runCatching { service() }.onFailure { AppLogger.warning(TAG, "service() probe failed: ${it.message}") }
            .getOrNull()?.let { svc ->
                val raw = runCatching { svc.listTasks() }.getOrNull()
                if (raw != null) {
                    val rows = parseTaskLines(raw)
                    return ListOutcome.Rows(rows, "user service")
                }
            }
        val listOrder = if (workingListCandidate != null) {
            LIST_CANDIDATES.sortedByDescending { it.name == workingListCandidate }
        } else {
            LIST_CANDIDATES
        }
        for (candidate in listOrder) {
            val r = shell(candidate.argv, 10_000L)
            if (r.exitCode != 0) continue
            val rows = candidate.parse(r.combined)
            if (rows.isNotEmpty()) {
                workingListCandidate = candidate.name
                return ListOutcome.Rows(rows, candidate.name)
            }
        }
        workingListCandidate = null
        return ListOutcome.Unavailable(
            "task listing unavailable on this ROM (user service not bound; shell candidates probed: " +
                LIST_CANDIDATES.joinToString(", ") { it.name } + ")",
        )
    }

    /** The top task of [displayId], or null when listing is unavailable or the display is empty. */
    fun listTop(displayId: Int): TaskRow? = when (val o = listTasks()) {
        is ListOutcome.Rows -> o.rows.firstOrNull { it.displayId == displayId }
        is ListOutcome.Unavailable -> null
    }

    // ── moving ───────────────────────────────────────────────────────────

    /**
     * Move [taskId] to [displayId]. User service first (verified through
     * listing when possible), then the shell table. Every attempt is checked
     * — an exit-0 shell candidate that did not actually move the task is
     * rejected and the next one is probed.
     */
    fun move(taskId: Int, displayId: Int): MoveOutcome {
        runCatching { service() }.getOrNull()?.let { svc ->
            val ok = runCatching { svc.moveRootTaskToDisplay(taskId, displayId) }.getOrDefault(false)
            if (ok) {
                return MoveOutcome.Moved("user service", verified = verifiedByListing(taskId, displayId) ?: false)
            }
            AppLogger.warning(TAG, "service moveRootTaskToDisplay($taskId → $displayId) returned false")
        }
        val moveOrder = if (workingMoveCandidate != null) {
            MOVE_CANDIDATES.sortedByDescending { it.name == workingMoveCandidate }
        } else {
            MOVE_CANDIDATES
        }
        for (candidate in moveOrder) {
            val argv = candidate.build(taskId, displayId)
            val r = shell(argv, 10_000L)
            if (r.exitCode != 0) continue
            when (verifiedByListing(taskId, displayId)) {
                true -> {
                    workingMoveCandidate = candidate.name
                    return MoveOutcome.Moved(candidate.name, verified = true)
                }
                null -> return MoveOutcome.Moved(candidate.name, verified = false)
                false -> {
                    AppLogger.warning(TAG, "${candidate.name} exited 0 but the task did not move — probing next candidate")
                }
            }
        }
        workingMoveCandidate = null
        return MoveOutcome.Unavailable(
            "task move unavailable on this ROM; ask the user to handle it manually " +
                "(probed: user service, ${MOVE_CANDIDATES.joinToString(", ") { it.name }})",
        )
    }

    /** true/false when listing works and answers; null when listing is unavailable. */
    private fun verifiedByListing(taskId: Int, displayId: Int): Boolean? = when (val o = listTasks()) {
        is ListOutcome.Rows -> o.rows.firstOrNull { it.taskId == taskId }?.displayId == displayId
        is ListOutcome.Unavailable -> null
    }

    // ── IME policy (user service only — no shell equivalent exists) ──────

    fun setImePolicy(displayId: Int, policy: Int): ImePolicyOutcome {
        val svc = runCatching { service() }.getOrNull() ?: return ImePolicyOutcome.NoService
        return if (runCatching { svc.setDisplayImePolicy(displayId, policy) }.getOrDefault(false)) {
            ImePolicyOutcome.Applied
        } else {
            ImePolicyOutcome.Refused
        }
    }

    // ── probe tables ─────────────────────────────────────────────────────

    /** One shell move verb. [build] receives (taskId, displayId). */
    internal class MoveCandidate(val name: String, val build: (Int, Int) -> Array<String>)

    /** One shell listing verb with its output parser. */
    internal class ListCandidate(val name: String, val argv: Array<String>, val parse: (String) -> List<TaskRow>)

    companion object {
        private const val TAG = "TaskMover"

        /**
         * Shell move candidates, most standard first. Each is PROBED against
         * the real task and verified via listing; none can fail silently.
         * `am display move-stack` takes (displayId, stackId) — on old ROMs
         * where it is the only verb, root task id and stack id coincide.
         */
        internal val MOVE_CANDIDATES = listOf(
            MoveCandidate("am stack move-task") { t, d -> arrayOf("am", "stack", "move-task", t.toString(), d.toString()) },
            MoveCandidate("cmd activity stack move-task") { t, d ->
                arrayOf("cmd", "activity", "stack", "move-task", t.toString(), d.toString())
            },
            MoveCandidate("am display move-stack") { t, d ->
                arrayOf("am", "display", "move-stack", d.toString(), t.toString())
            },
        )

        internal val LIST_CANDIDATES = listOf(
            ListCandidate("am stack list", arrayOf("am", "stack", "list"), ::parseStackDump),
            ListCandidate("cmd activity stack list", arrayOf("cmd", "activity", "stack", "list"), ::parseStackDump),
            ListCandidate("dumpsys activity activities", arrayOf("dumpsys", "activity", "activities"), ::parseStackDump),
        )

        /**
         * Best-effort parser for `am stack list` / `cmd activity stack list`
         * / `dumpsys activity activities`: tracks the current display from
         * whichever line shape the ROM prints ("displayId=0", "Display #0",
         * "Tasks in Display #0") and pulls tasks out of TaskRecord lines
         * ("… #12345 A=com.example …"). Fragile by nature — the caller only
         * trusts it when it yields rows for the display it cares about, and
         * every move built on it is verified.
         */
        internal fun parseStackDump(output: String): List<TaskRow> {
            val rows = mutableListOf<TaskRow>()
            var display = -1
            for (line in output.lineSequence()) {
                val displayMatch = DISPLAY_REGEXES.firstNotNullOfOrNull { it.find(line) }
                if (displayMatch != null) {
                    display = displayMatch.groupValues[1].toIntOrNull() ?: -1
                }
                val task = TASK_RECORD_REGEX.find(line) ?: continue
                val taskId = task.groupValues[1].toIntOrNull() ?: continue
                val pkg = task.groupValues[2]
                rows.add(TaskRow(taskId, display, pkg, ""))
            }
            return rows
        }

        private val DISPLAY_REGEXES = listOf(
            Regex("displayId=(\\d+)"),
            Regex("Display #(\\d+)"),
            Regex("display=(\\d+)"),
        )

        /** TaskRecord{6f0d16c #12345 A=com.example …} → (12345, com.example). */
        private val TASK_RECORD_REGEX = Regex("TaskRecord\\{[^}]*?#(\\d+)\\s+(?:A=([A-Za-z0-9_.]+))?")

        /** IVscreenService.listTasks wire format → rows. */
        internal fun parseTaskLines(raw: String): List<TaskRow> =
            raw.lineSequence().mapNotNull { line ->
                val p = line.split("\t")
                if (p.size < 4) return@mapNotNull null
                val id = p[0].trim().toIntOrNull() ?: return@mapNotNull null
                val display = p[1].trim().toIntOrNull() ?: return@mapNotNull null
                TaskRow(id, display, p[2].trim(), p[3].trim())
            }.toList()
    }
}
