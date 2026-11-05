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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * [T-task-mandate] What a run is allowed to touch, declared up front.
 *
 * The prototype separates the unit of work (Ticket) from what authorised it
 * (Mandate: scope, strength, the policy version it was accepted under, and the
 * rule that only a person's stop withdraws it — `docs/001-domain.md:34`). We had
 * the work: a goal, a plan board, lanes. What we never had was a **written
 * boundary the code could check**: a lane could quietly widen from "fix this
 * spacing" to "restyle the page" because nothing said what the task covered.
 *
 * This is the smallest useful version. It stores one mandate per session beside
 * the other session state (no schema migration), and it is deliberately
 * opt-in: a session that never declares a mandate keeps today's behaviour. Once
 * declared, a dispatch whose kind or target is outside the scope is refused by
 * code rather than discouraged by prompt.
 *
 * Scope entries are free-form tokens matched against `kind:target`, so a caller
 * can write `edit:src/android/app/src/main/java/...` or `recon`. Matching is
 * prefix-based on the whole token, case-insensitive.
 */
object TaskMandateStore {

    private const val TAG = "TaskMandateStore"
    private const val DIR_NAME = "task-mandate"
    private const val FILE_NAME = "mandate.json"

    /** Scope tokens kept; a mandate longer than this is a plan, not a boundary. */
    const val MAX_SCOPE_TOKENS = 24

    const val MAX_TOKEN_CHARS = 160

    /** Strength vocabulary, mirroring the prototype's `explicit`/`inferred`. */
    val STRENGTHS = listOf("explicit", "inferred")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val memory = ConcurrentHashMap<String, Mandate>()

    val version = MutableStateFlow(0L)

    @Serializable
    data class Mandate(
        /** What the work may touch, as `kind:target` tokens or bare kinds. */
        val scope: List<String> = emptyList(),
        /** `explicit` when a person asked for this, `inferred` when derived. */
        val strength: String = "explicit",
        /** Instruction-snapshot seq this mandate was accepted under. */
        val policySeq: Long = 0L,
        /** What the person actually asked for, quoted rather than summarised. */
        val objective: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        val declared: Boolean get() = scope.isNotEmpty()
    }

    private fun key(sessionId: String): String =
        ExecutionCoordinator.ownerSessionId(sessionId.ifBlank { "__default__" })

    private fun lock(sessionId: String): ReentrantLock =
        locks.getOrPut(key(sessionId)) { ReentrantLock() }

    private fun file(context: Context?, sessionId: String): File? {
        context ?: return null
        val owner = ExecutionCoordinator.ownerSessionId(key(sessionId))
        return File(File(SessionWorkspace.base(context.filesDir, owner), DIR_NAME), FILE_NAME)
    }

    fun declare(
        sessionId: String,
        context: Context?,
        scope: List<String>,
        strength: String,
        policySeq: Long,
        objective: String,
    ): Mandate {
        val normalizedScope = scope
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_TOKEN_CHARS) }
            .distinct()
        require(normalizedScope.isNotEmpty()) {
            "scope is required: name what this task may touch (e.g. edit:<path>, or recon)"
        }
        require(normalizedScope.size <= MAX_SCOPE_TOKENS) {
            "scope has ${normalizedScope.size} tokens; the limit is $MAX_SCOPE_TOKENS"
        }
        val normalizedStrength = strength.trim().lowercase().ifBlank { "explicit" }
        require(normalizedStrength in STRENGTHS) {
            "strength must be one of ${STRENGTHS.joinToString(", ")}"
        }
        val mandate = Mandate(
            scope = normalizedScope,
            strength = normalizedStrength,
            policySeq = policySeq.coerceAtLeast(0L),
            objective = objective.trim().take(1_000),
        )
        lock(sessionId).withLock {
            memory[key(sessionId)] = mandate
            version.value += 1
            val f = file(context, sessionId) ?: return@withLock
            runCatching {
                f.parentFile?.mkdirs()
                AtomicFileWrite.write(f, json.encodeToString(mandate))
            }.onFailure { AppLogger.error(TAG, "save failed at ${f.absolutePath}: ${it.message}") }
        }
        return mandate
    }

    fun get(sessionId: String, context: Context?): Mandate? = lock(sessionId).withLock {
        memory[key(sessionId)]?.let { return@withLock it }
        val f = file(context, sessionId) ?: return@withLock null
        if (!f.isFile) return@withLock null
        val text = AtomicFileWrite.read(f) ?: throw IOException("cannot read mandate ${f.absolutePath}")
        runCatching { json.decodeFromString<Mandate>(text) }.getOrElse { e ->
            AppLogger.error(TAG, "mandate corrupt at ${f.absolutePath}: ${e.message}")
            throw IOException("mandate file ${f.name} is corrupt (${e.message}); delete it to redeclare", e)
        }.also { memory[key(sessionId)] = it }
    }

    fun clear(sessionId: String, context: Context?) {
        lock(sessionId).withLock {
            memory.remove(key(sessionId))
            version.value += 1
            file(context, sessionId)?.delete()
        }
    }

    /**
     * Whether [action] (`kind` or `kind:target`) is inside a declared mandate.
     *
     * No mandate, or a mandate with no scope, means "not declared" — callers
     * treat that as unconstrained so an undeclared session behaves as before.
     * Otherwise a token matches when it equals the action or the action starts
     * with it (so `edit:/repo` covers `edit:/repo/app.kt`).
     */
    fun allows(mandate: Mandate?, action: String): Boolean {
        if (mandate == null || !mandate.declared) return true
        val wanted = action.trim().lowercase()
        if (wanted.isEmpty()) return true
        return mandate.scope.any { token ->
            wanted == token || wanted.startsWith("$token/") || wanted.startsWith(token)
        }
    }

    /** Human-readable refusal naming the boundary and what would widen it. */
    fun refusal(mandate: Mandate, action: String): String = buildString {
        append("Refused by the task mandate: \"").append(action).append("\" is outside the declared scope [")
        append(mandate.scope.take(6).joinToString(", "))
        if (mandate.scope.size > 6) append(", and ${mandate.scope.size - 6} more")
        append("]. Declare a wider scope with task_mandate before widening the work, or finish the declared part first.")
    }
}

/** [T-task-mandate] Declares or inspects the session's written boundary. */
object TaskMandateTool {
    const val NAME = "task_mandate"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Declare the boundary of the current task in writing, or read it back. " +
            "Call it once when a task starts spanning several steps: list what the work may touch " +
            "(tokens like edit:<path>, recon, test:<path>) and quote what the user actually asked for. " +
            "After that, dispatches outside the declared scope are refused by the host, not merely " +
            "discouraged - which is what keeps a small fix from quietly turning into a refactor. " +
            "Widen the scope deliberately when the user asks for more; never widen it to fit work already started.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "op" to AgentToolParam("string", "declare, show, or clear.", enumValues = listOf("declare", "show", "clear")),
            "scope" to AgentToolParam(
                "array",
                "What the task may touch. Tokens like edit:<path>, recon, test:<path>.",
                items = AgentToolParam("string", "One scope token"),
            ),
            "strength" to AgentToolParam(
                "string",
                "explicit when a person asked for this work, inferred when derived from context.",
                enumValues = TaskMandateStore.STRENGTHS,
            ),
            "objective" to AgentToolParam("string", "The user's request quoted, not summarised."),
            "policy_seq" to AgentToolParam("integer", "Instruction-snapshot seq this is accepted under, when known."),
        ),
        required = listOf("tool_title", "op"),
        propertyOrdering = listOf("tool_title", "op", "scope", "strength", "objective", "policy_seq"),
    )

    fun execute(
        argsJson: String,
        sessionId: String = "__default__",
        context: Context? = null,
    ): ToolExecutionResult {
        val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title", NAME) }.getOrDefault(NAME)
        return try {
            val args = JSONObject(argsJson)
            when (args.optString("op", "show").lowercase()) {
                "declare" -> {
                    val scope = args.optJSONArray("scope")?.let { array ->
                        (0 until array.length()).map { array.optString(it) }
                    } ?: emptyList()
                    val mandate = TaskMandateStore.declare(
                        sessionId = sessionId,
                        context = context,
                        scope = scope,
                        strength = args.optString("strength", "explicit"),
                        policySeq = args.optLong("policy_seq", 0L),
                        objective = args.optString("objective"),
                    )
                    ToolExecutionResult(render(mandate), true, toolTitle = toolTitle)
                }
                "clear" -> {
                    TaskMandateStore.clear(sessionId, context)
                    ToolExecutionResult("(task mandate cleared; dispatches are unconstrained again)", true, toolTitle = toolTitle)
                }
                else -> {
                    val mandate = TaskMandateStore.get(sessionId, context)
                        ?: return ToolExecutionResult(
                            "(no task mandate declared; dispatches are unconstrained)",
                            true,
                            toolTitle = toolTitle,
                        )
                    ToolExecutionResult(render(mandate), true, toolTitle = toolTitle)
                }
            }
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
        } catch (e: IOException) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    private fun render(mandate: TaskMandateStore.Mandate): String = buildString {
        append("mandate: scope [")
        append(mandate.scope.joinToString(", "))
        append("] strength=").append(mandate.strength)
        if (mandate.policySeq > 0) append(" policySeq=").append(mandate.policySeq)
        if (mandate.objective.isNotBlank()) append("\nobjective: ").append(mandate.objective)
        append("\nDispatches outside this scope are refused by the host.")
    }
}
