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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private fun key(sessionId: String): String = sessionId.ifBlank { "__default__" }
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
        memory[k]?.let { return it }
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
        return loaded
    }

    private fun saveLocked(sessionId: String, context: Context?, plans: List<Plan>) {
        val k = key(sessionId)
        val trimmed = plans.take(MAX_PLANS).toMutableList()
        val f = file(context, k)
        if (f == null) {
            // No context (unit tests / headless callers): memory-only board.
            memory[k] = trimmed
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
        require(status in STATUSES) { "status must be pending, active, done, or failed" }
        return lock(sessionId).withLock {
            val plans = loadLocked(sessionId, context)
            require(plans.size < MAX_PLANS) { "plan list is full" }
            val p = Plan(id = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(), title = title.trim(), description = description.trim(), status = status, position = plans.size)
            saveLocked(sessionId, context, plans + p)
            p
        }
    }

    fun update(sessionId: String, context: Context?, id: String, title: String? = null, description: String? = null, status: String? = null, position: Int? = null): Plan? = lock(sessionId).withLock {
        val plans = loadLocked(sessionId, context)
        val index = plans.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null
        if (status != null) require(status in STATUSES) { "status must be pending, active, done, or failed" }
        val old = plans[index]
        val next = old.copy(title = title?.trim()?.takeIf { it.isNotEmpty() } ?: old.title, description = description ?: old.description, status = status ?: old.status, position = position ?: old.position, updatedAt = System.currentTimeMillis())
        val out = plans.toMutableList().also { it[index] = next }
        saveLocked(sessionId, context, out)
        next
    }

    fun remove(sessionId: String, context: Context?, id: String): Boolean = lock(sessionId).withLock {
        val plans = loadLocked(sessionId, context)
        val out = plans.filterNot { it.id == id }.mapIndexed { i, p -> p.copy(position = i) }
        if (out.size == plans.size) return@withLock false
        saveLocked(sessionId, context, out)
        true
    }

    fun clear(sessionId: String, context: Context?) = lock(sessionId).withLock {
        saveLocked(sessionId, context, emptyList())
    }

    fun render(sessionId: String, context: Context?): String {
        val plans = list(sessionId, context)
        if (plans.isEmpty()) return "(no plan items)"
        return buildString {
            append("plans: ${plans.size}\n")
            plans.forEachIndexed { i, p ->
                val mark = when (p.status) { "done" -> "x"; "active" -> ">"; "failed" -> "!"; else -> " " }
                append("[$mark] ${i + 1}. ${p.title} {${p.id.take(8)}} [${p.status}]")
                if (p.description.isNotBlank()) append(" — ${p.description}")
                append("\n")
            }
        }
    }

    private val STATUSES = setOf("pending", "active", "done", "failed")
}

object AgentPlanTool {
    const val NAME = "agent_plan"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Maintain an independent task list for this chat. Each item has its own id and status; no hidden cursor or auto-advance. Operations: add, update, remove, list, clear.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "op" to AgentToolParam("string", "add, update, remove, list, or clear.", enumValues = listOf("add", "update", "remove", "list", "clear")),
            "id" to AgentToolParam("string", "Plan item id for update/remove."),
            "title" to AgentToolParam("string", "Task title."),
            "description" to AgentToolParam("string", "Optional task details."),
            "status" to AgentToolParam("string", "pending, active, done, or failed.", enumValues = listOf("pending", "active", "done", "failed")),
            "position" to AgentToolParam("integer", "Optional display position."),
        ),
        required = listOf("tool_title", "op"),
        propertyOrdering = listOf("tool_title", "op", "id", "title", "description", "status", "position"),
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
                    val p = AgentPlanStore.update(sessionId, context, args.optString("id"), args.optString("title").takeIf { it.isNotBlank() }, if (args.has("description")) args.optString("description") else null, args.optString("status").takeIf { it.isNotBlank() }, if (args.has("position")) args.optInt("position") else null) ?: return ToolExecutionResult("Error: plan item not found", false, toolTitle = toolTitle)
                    ToolExecutionResult("Updated ${p.id}: ${AgentPlanStore.render(sessionId, context)}", true, toolTitle = toolTitle)
                }
                "remove" -> if (AgentPlanStore.remove(sessionId, context, args.optString("id"))) ToolExecutionResult(AgentPlanStore.render(sessionId, context), true, toolTitle = toolTitle) else ToolExecutionResult("Error: plan item not found", false, toolTitle = toolTitle)
                "clear" -> { AgentPlanStore.clear(sessionId, context); ToolExecutionResult("(plan list cleared)", true, toolTitle = toolTitle) }
                else -> ToolExecutionResult(AgentPlanStore.render(sessionId, context), true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error agent_plan: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}
