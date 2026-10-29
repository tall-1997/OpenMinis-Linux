package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Independent, session-scoped plan list for the agent loop.
 *
 * A plan is a list item with its own id and status. The previous implementation
 * kept one process-global linear board, so two chats overwrote each other and a
 * cold start lost the board. This store persists one JSON file per session and
 * supports editing individual items without advancing a hidden cursor.
 */
object AgentPlanStore {
    private const val TAG = "AgentPlanStore"
    private const val DIR_NAME = "agent-plan"
    private const val FILE_NAME = "plans.json"
    private const val MAX_PLANS = 100
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val memory = ConcurrentHashMap<String, MutableList<Plan>>()

    /**
     * How long a fully-done board holds its finished state before retiring.
     * Single source of truth: PlanGoalCard animates exactly this window and
     * [retireFinished] enforces it, so the card and the store cannot drift.
     */
    const val DONE_LINGER_MS = 4_000L

    /**
     * When each board became fully done, keyed like [memory]. Not persisted:
     * after a process restart the board is re-stamped on first access, so a
     * cold start costs one extra linger instead of a file-format change.
     */
    private val finishedAt = ConcurrentHashMap<String, Long>()

    /** Test seam: the linger window is wall-clock, tests need it frozen. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /**
     * Bumped on every mutation (memory-only boards included) so UI
     * projections can reload without the writers knowing a UI exists.
     */
    val version = MutableStateFlow(0L)

    @Serializable
    data class Plan(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val description: String = "",
        val status: String = "pending",
        val position: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    )

    /**
     * One board per OWNER session on every layer. [file] already resolved lane
     * ids (`subagent:<owner>:<token>`) to their owner while the lock and the
     * in-memory cache keyed on the raw id, so a lane and its owner held two
     * divergent RAM copies over one file and the last writer silently dropped
     * the other's items. Normalizing here makes lock, cache, file and [version]
     * agree by construction. Idempotent: an owner id maps to itself.
     */
    private fun key(sessionId: String): String =
        ExecutionCoordinator.ownerSessionId(sessionId.ifBlank { "__default__" })
    private fun lock(sessionId: String): ReentrantLock = locks.getOrPut(key(sessionId)) { ReentrantLock() }

    /**
     * Deliberately the session's PRIVATE base dir, not `hostDir(...,
     * "workspace")`: workspace is a SHARED_SUBDIR, so two chats filed into the
     * same project folder would resolve to the same file and clobber each
     * other's board — the exact bug the session-scoped store fixes.
     */
    private fun file(context: Context?, sessionId: String): File? {
        context ?: return null
        val owner = ExecutionCoordinator.ownerSessionId(key(sessionId))
        return File(File(SessionWorkspace.base(context.filesDir, owner), DIR_NAME), FILE_NAME)
    }

    private fun loadLocked(sessionId: String, context: Context?): MutableList<Plan> {
        val k = key(sessionId)
        memory[k]?.let { return retireFinished(sessionId, k, context, it) }
        val f = file(context, k)
        val loaded: MutableList<Plan> = if (f == null || !f.isFile) {
            mutableListOf()
        } else {
            // [T-plan-store-silent-loss] A corrupt or unreadable board used to
            // decode to an empty list — and the next add() overwrote the file,
            // silently destroying every persisted plan. Surface it instead:
            // execute() turns the throw into a tool error, and the log line
            // names the file to inspect or delete.
            val text = AtomicFileWrite.read(f)
                ?: throw IOException("cannot read plan file ${f.absolutePath}")
            runCatching { decodePlans(text) }.getOrElse { e ->
                AppLogger.error(TAG, "plan file corrupt at ${f.absolutePath}: ${e.message}")
                throw IOException(
                    "plan file ${f.name} is corrupt (${e.message}); delete it to start a fresh board",
                    e,
                )
            }
        }
        memory[k] = loaded
        return retireFinished(sessionId, k, context, loaded)
    }

    /**
     * [T-plan-board-retirement] A finished board retires on ACCESS, not on a
     * Composable's lifecycle. The card used to own this transition, so any run
     * that finished with no chat screen mounted - app backgrounded, screen off,
     * a scheduled task, another chat in front - left its done items on disk
     * forever, and every later request kept injecting the finished board into
     * the prompt. The card still animates the same window; the store now
     * guarantees the outcome whether or not anyone is watching.
     */
    private fun retireFinished(
        sessionId: String,
        k: String,
        context: Context?,
        plans: MutableList<Plan>,
    ): MutableList<Plan> {
        if (plans.isEmpty() || !plans.all { it.status == "done" }) {
            finishedAt.remove(k)
            return plans
        }
        val stamped = finishedAt[k]
        if (stamped == null) {
            finishedAt[k] = nowMs()
            return plans
        }
        if (nowMs() - stamped < DONE_LINGER_MS) return plans
        finishedAt.remove(k)
        saveLocked(sessionId, context, emptyList())
        return mutableListOf()
    }

    private fun saveLocked(sessionId: String, context: Context?, plans: List<Plan>) {
        val k = key(sessionId)
        val trimmed = plans.take(MAX_PLANS).toMutableList()
        noteFinished(k, trimmed)
        val f = file(context, k)
        if (f == null) {
            // No context (unit tests / headless callers): memory-only board.
            memory[k] = trimmed
            version.value = version.value + 1
            return
        }
        // [T-plan-store-silent-loss] A failed persist used to be swallowed —
        // the tool reported success while the board lived only in RAM and
        // vanished with the process. AtomicFileWrite verifies the write and
        // keeps the previous file on failure; throw so execute() returns an
        // explicit tool error and memory only advances after bytes land.
        if (AtomicFileWrite.write(f, encodePlans(plans)) == null) {
            AppLogger.error(TAG, "plan board persist failed for session $k (${f.absolutePath})")
            throw IOException("failed to persist plan board; previous file kept, change is in-memory only")
        }
        memory[k] = trimmed
        version.value = version.value + 1
    }

    /** Stamp the moment a board finishes; drop the stamp when work reopens. */
    private fun noteFinished(k: String, plans: List<Plan>) {
        if (plans.isNotEmpty() && plans.all { it.status == "done" }) {
            finishedAt.putIfAbsent(k, nowMs())
        } else {
            finishedAt.remove(k)
        }
    }

    internal fun decodePlans(text: String): MutableList<Plan> =
        json.decodeFromString<List<Plan>>(text).toMutableList()

    internal fun encodePlans(plans: List<Plan>): String =
        json.encodeToString(plans.take(MAX_PLANS))

    fun list(sessionId: String, context: Context?): List<Plan> = lock(sessionId).withLock {
        loadLocked(sessionId, context).sortedBy { it.position }
    }

    fun add(sessionId: String, context: Context?, title: String, description: String = "", status: String = "pending", id: String? = null): Plan {
        require(title.isNotBlank()) { "title is required" }
        return lock(sessionId).withLock {
            val plans = loadLocked(sessionId, context)
            require(plans.size < MAX_PLANS) { "plan list is full" }
            val p = Plan(id = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(), title = title.trim(), description = description.trim(), status = normalizeStatus(status), position = plans.size)
            saveLocked(sessionId, context, plans + p)
            p
        }
    }

    fun update(sessionId: String, context: Context?, id: String, title: String? = null, description: String? = null, status: String? = null, position: Int? = null): Plan? = lock(sessionId).withLock {
        val plans = loadLocked(sessionId, context)
        val index = resolveIndex(plans, id)
        if (index < 0) return@withLock null
        val old = plans[index]
        val next = old.copy(title = title?.trim()?.takeIf { it.isNotEmpty() } ?: old.title, description = description ?: old.description, status = status?.let { normalizeStatus(it) } ?: old.status, position = position ?: old.position, updatedAt = System.currentTimeMillis())
        val out = plans.toMutableList().also { it[index] = next }
        saveLocked(sessionId, context, out)
        next
    }

    fun remove(sessionId: String, context: Context?, id: String): Boolean = lock(sessionId).withLock {
        val plans = loadLocked(sessionId, context)
        val index = resolveIndex(plans, id)
        if (index < 0) return@withLock false
        val out = plans.filterIndexed { i, _ -> i != index }.mapIndexed { i, p -> p.copy(position = i) }
        saveLocked(sessionId, context, out)
        true
    }

    fun clear(sessionId: String, context: Context?) = lock(sessionId).withLock {
        saveLocked(sessionId, context, emptyList())
    }

    /**
     * [T-plan-status-aliases] Status used to be validated with `require(status
     * in STATUSES)` — one ecosystem's wording (completed / in_progress / …)
     * and the call threw, the model dropped the update, and the item stayed
     * stuck. Fold every common alias into the four canonical states; anything
     * unrecognized degrades to pending instead of failing the call.
     */
    internal fun normalizeStatus(raw: String?): String {
        val r = raw?.trim()?.lowercase() ?: return "pending"
        return when {
            r in DONE_ALIASES -> "done"
            r in ACTIVE_ALIASES -> "active"
            r in FAILED_ALIASES -> "failed"
            else -> "pending"
        }
    }

    /**
     * [T-plan-target-resolution] update/remove used to demand the exact UUID
     * printed by a previous render. A mistranscribed or half-remembered UUID
     * bounced with "plan item not found", the model stopped advancing the
     * board, and done items never got checked off. Resolution now runs a
     * ladder: exact id → 1-based number (the index the snapshot prints) →
     * unique exact title → unique title prefix → unique title substring.
     * Unknown or ambiguous → -1 (the caller reports it with the live board).
     */
    fun resolveIndex(plans: List<Plan>, ref: String): Int {
        val r = ref.trim()
        if (r.isEmpty()) return -1
        plans.indexOfFirst { it.id == r }.takeIf { it >= 0 }?.let { return it }
        r.toIntOrNull()?.let { n -> if (n in 1..plans.size) return n - 1 }
        uniqueMatch(plans) { it.title.trim().equals(r, ignoreCase = true) }?.let { return it }
        uniqueMatch(plans) { it.title.trim().startsWith(r, ignoreCase = true) }?.let { return it }
        uniqueMatch(plans) { it.title.contains(r, ignoreCase = true) }?.let { return it }
        return -1
    }

    private fun uniqueMatch(plans: List<Plan>, match: (Plan) -> Boolean): Int? {
        var hit = -1
        for ((i, p) in plans.withIndex()) {
            if (!match(p)) continue
            if (hit >= 0) return null // ambiguous
            hit = i
        }
        return hit.takeIf { it >= 0 }
    }

    /**
     * [T-plan-board-replace] The desktop task tools (opencode todowrite,
     * Claude Code TodoWrite) are full-list REPLACE operations — no ids, one
     * call rebuilds the board, so "mark it done" never depends on remembering
     * a UUID. This is the same contract: [incoming] becomes the entire board
     * in the given order. A provided id is reused when it names an existing
     * item (stable identity across replaces); duplicate ids are rejected;
     * an empty list clears the board.
     */
    fun replace(sessionId: String, context: Context?, incoming: List<PlanInput>): List<Plan> = lock(sessionId).withLock {
        require(incoming.size <= MAX_PLANS) { "plan list is full" }
        val existing = loadLocked(sessionId, context)
        val seenIds = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        val built = incoming.mapIndexed { i, item ->
            val title = item.title.trim()
            require(title.isNotEmpty()) { "title is required" }
            val status = normalizeStatus(item.status)
            val id = item.id.trim().takeIf { it.isNotEmpty() }?.also { raw ->
                require(seenIds.add(raw)) { "duplicate plan id $raw" }
            } ?: UUID.randomUUID().toString()
            val old = existing.firstOrNull { it.id == id }
            val changed = old == null || old.title != title || old.description != item.description.trim() || old.status != status
            Plan(
                id = id,
                title = title,
                description = item.description.trim(),
                status = status,
                position = i,
                createdAt = old?.createdAt ?: now,
                updatedAt = if (changed) now else old.updatedAt,
            )
        }
        saveLocked(sessionId, context, built)
        built
    }

    /**
     * Draft→real key migration: a board seeded under the route's draft key
     * must follow the session to its persisted id. Re-adds items through
     * [add] (id preserved, position = insertion order) rather than moving
     * the file, so the memory map, the persisted file, and the version
     * signal all stay coherent. No-op for an empty old board.
     */
    fun migrate(oldSid: String, newSid: String, context: Context?) {
        if (oldSid == newSid || newSid.isBlank()) return
        val plans = list(oldSid, context)
        if (plans.isEmpty()) return
        clear(oldSid, context)
        plans.forEach { add(newSid, context, it.title, it.description, it.status, it.id) }
    }

    fun render(sessionId: String, context: Context?): String {
        val plans = list(sessionId, context)
        // [T-plan-board-announcement] Neutral text here read as "board is fine,
        // nothing to do": the model could not tell an empty board from a
        // feature it had never used. Name the gap instead.
        if (plans.isEmpty()) {
            return "(no plan items: this chat has no board yet - when the work spans " +
                "multiple steps or several tool rounds, create one with op=add so the " +
                "user sees progress live)"
        }
        return buildString {
            append("plans: ${plans.size}\n")
            plans.forEachIndexed { i, p ->
                val mark = when (p.status) { "done" -> "x"; "active" -> ">"; "failed" -> "!"; else -> " " }
                // [T-plan-render-id-truncation] The id shown must be the id
                // that round-trips: update/remove match it exactly, so a
                // truncated id either misses or silently hits the wrong row.
                append("[$mark] ${i + 1}. ${p.title} {${p.id}} [${p.status}]")
                if (p.description.isNotBlank()) append(" — ${p.description}")
                append("\n")
            }
        }
    }

    /**
     * Live board snapshot for the system prompt so the model sees the current
     * progress every request without a tool round-trip. Empty board renders
     * nothing (no section at all) to keep the prompt lean. Moved here from
     * the deleted PlanModeStore: it renders the board, not any mode.
     */
    fun boardSnapshotPrompt(plans: List<Plan>): String? {
        if (plans.isEmpty()) return null
        // A finished board has nothing to advance: announcing it would tell the
        // model to keep pushing items that already landed, and the card is on
        // its way out. The empty-board "create one" contract lives in the
        // agent_plan tool description, which is in the schema every turn.
        if (plans.all { it.status == "done" }) return null
        return buildString {
            append("<plan_board>\n")
            append("Current plan board. Advance it with agent_plan — target an item by its number (update id=\"2\", status=\"done\"), by its title, or rebuild the whole board with op=replace; the user sees it live:\n")
            plans.forEachIndexed { i, p ->
                append("${i + 1}. [${p.status}] ${p.title}")
                if (p.description.isNotBlank()) append(" — ${p.description}")
                append("\n")
            }
            append("</plan_board>")
        }
    }

    private val DONE_ALIASES = setOf("done", "completed", "complete", "finished")
    private val ACTIVE_ALIASES = setOf("active", "in_progress", "inprogress", "in-progress", "doing")
    private val FAILED_ALIASES = setOf("failed", "error", "cancelled", "canceled", "blocked")

    /** One replacement row for [replace]: ids are optional, status aliases fold in. */
    data class PlanInput(
        val id: String = "",
        val title: String,
        val description: String = "",
        val status: String = "pending",
    )
}

object AgentPlanTool {
    const val NAME = "agent_plan"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Maintain the working plan for this chat, shown to the user live above the composer. " +
            "The board is a full-list REPLACE on every change: use op=replace with the complete list of items " +
            "(ids are optional — reuse an item's id to keep it stable, omit it to mint one). " +
            "Create the board when the work spans 3+ steps or several tool rounds, one item per deliverable step; " +
            "flip the item you are on to active, and to done the moment it lands - in the turn the work happens. " +
            "update/remove need no ids either: target an item by its 1-based number from the snapshot, its exact " +
            "title, or a unique title prefix. Skip the board for single-shot answers and one-command lookups.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "op" to AgentToolParam("string", "add, update, remove, list, clear, or replace.", enumValues = listOf("add", "update", "remove", "list", "clear", "replace")),
            "id" to AgentToolParam("string", "Plan item id for update/remove. You can also pass its 1-based number (shown in the snapshot), its exact title, or a unique title prefix."),
            "title" to AgentToolParam("string", "Task title."),
            "description" to AgentToolParam("string", "Optional task details."),
            "status" to AgentToolParam("string", "pending, active, done, or failed. Aliases like completed / in_progress / cancelled are accepted and normalized.", enumValues = listOf("pending", "active", "done", "failed")),
            "position" to AgentToolParam("integer", "Optional display position."),
            "plans" to AgentToolParam(
                "array",
                "Full list for op=replace: it becomes the entire board, in this order. Empty list clears the board.",
                items = AgentToolParam(
                    "object",
                    "One board item.",
                    properties = mapOf(
                        "title" to AgentToolParam("string", "Task title."),
                        "description" to AgentToolParam("string", "Optional task details."),
                        "status" to AgentToolParam("string", "pending, active, done, or failed (aliases accepted).", enumValues = listOf("pending", "active", "done", "failed")),
                        "id" to AgentToolParam("string", "Optional: reuse a previous item's id to keep it stable."),
                    ),
                    required = listOf("title"),
                ),
            ),
        ),
        required = listOf("tool_title", "op"),
        propertyOrdering = listOf("tool_title", "op", "id", "title", "description", "status", "position", "plans"),
    )

    fun execute(argsJson: String, sessionId: String = "__default__", context: Context? = null): ToolExecutionResult {
        val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title", NAME) }.getOrDefault(NAME)
        return try {
            val args = JSONObject(argsJson)
            val op = args.optString("op", "list")
            when (op) {
                "add" -> {
                    val p = AgentPlanStore.add(sessionId, context, args.optString("title"), args.optString("description"), args.optString("status", "pending"), args.optString("id").takeIf { it.isNotBlank() })
                    ToolExecutionResult("Added ${p.id}: ${AgentPlanStore.render(sessionId, context)}", true, toolTitle = toolTitle)
                }
                "update" -> {
                    val ref = args.optString("id")
                    val p = AgentPlanStore.update(sessionId, context, ref, args.optString("title").takeIf { it.isNotBlank() }, if (args.has("description")) args.optString("description") else null, args.optString("status").takeIf { it.isNotBlank() }, if (args.has("position")) args.optInt("position") else null)
                        ?: return ToolExecutionResult("Error: plan item not found for \"$ref\". Current board: ${AgentPlanStore.render(sessionId, context)}", false, toolTitle = toolTitle)
                    ToolExecutionResult("Updated ${p.id}: ${AgentPlanStore.render(sessionId, context)}", true, toolTitle = toolTitle)
                }
                "remove" -> {
                    val ref = args.optString("id")
                    if (AgentPlanStore.remove(sessionId, context, ref)) ToolExecutionResult(AgentPlanStore.render(sessionId, context), true, toolTitle = toolTitle)
                    else ToolExecutionResult("Error: plan item not found for \"$ref\". Current board: ${AgentPlanStore.render(sessionId, context)}", false, toolTitle = toolTitle)
                }
                "clear" -> { AgentPlanStore.clear(sessionId, context); ToolExecutionResult("(plan list cleared)", true, toolTitle = toolTitle) }
                "replace" -> {
                    val plansArg = args.optJSONArray("plans") ?: runCatching { JSONArray(args.optString("plans")) }.getOrNull()
                        ?: return ToolExecutionResult("Error agent_plan: plans must be a JSON array", false, toolTitle = toolTitle)
                    val items = mutableListOf<AgentPlanStore.PlanInput>()
                    for (i in 0 until plansArg.length()) {
                        val e = plansArg.optJSONObject(i) ?: continue
                        items += AgentPlanStore.PlanInput(id = e.optString("id"), title = e.optString("title"), description = e.optString("description"), status = e.optString("status", "pending"))
                    }
                    AgentPlanStore.replace(sessionId, context, items)
                    ToolExecutionResult(AgentPlanStore.render(sessionId, context), true, toolTitle = toolTitle)
                }
                else -> ToolExecutionResult(AgentPlanStore.render(sessionId, context), true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error agent_plan: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}
