package com.openminis.app.mcp.server.approval

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.mcp.server.approval.McpApprovalCore.Operation
import com.openminis.app.mcp.server.approval.McpApprovalCore.OperationStatus
import com.openminis.app.mcp.server.approval.McpApprovalCore.State
import com.openminis.app.mcp.server.approval.McpApprovalCore.Task
import com.openminis.app.mcp.server.approval.McpApprovalCore.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * [T-mcp-approval] Persistence for the approval state machine. A thin prefs
 * JSON wrapper around [McpApprovalCore] — all decision logic lives in the
 * pure core; this class only (de)serializes, generates ids, and reads-modifies-
 * writes atomically against the in-memory cache.
 *
 * Serialization is static so the JVM tests cover the byte-level round trip
 * without any Android type. Plain SharedPreferences (no secrets inside).
 */
class McpApprovalStore internal constructor(private val prefs: SharedPreferences) {

    /** App-facing entry point: preferences live in the app's private store. */
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    companion object {
        private const val PREFS = "mcp_approvals"
        private const val KEY_STATE = "state"

        /**
         * Default task root: `/var/minis/shared/mcp-tasks/<taskId>/`.
         *
         * Shared rather than session-private because two processes read it: the
         * phone writes `result.json`, the PRoot-side client reads it back, and
         * only the shared mount is bound into the guest.
         */
        const val TASK_ROOT_PREFIX = "/var/minis/shared/mcp-tasks/"

        /**
         * Active task root prefix. Production keeps the [TASK_ROOT_PREFIX]
         * default; tests and alternate sandbox layouts point it at a writable
         * location. Volatile because tests mutate it once while other threads
         * may read it.
         */
        @Volatile
        var taskRootPrefix: String = TASK_ROOT_PREFIX

        /**
         * Guest→host translation for the task root. The phone cannot open a
         * `/var/minis/...` path itself — the prefix only exists inside the
         * PRoot mount — so production injects
         * `SessionWorkspace.resolveGuestPath`, the same resolver the shell
         * bind uses. Null keeps the literal prefix (unit tests, scratch dirs).
         */
        @Volatile
        var taskRootHostResolver: ((String) -> String)? = null

        /** Guest-visible task root: the path clients see in RPC payloads. */
        fun taskRootFor(taskId: String): String = taskRootPrefix + taskId + "/"

        /** Host directory the phone actually writes into. */
        fun hostTaskRootFor(taskId: String): String {
            val guest = taskRootFor(taskId)
            return taskRootHostResolver?.invoke(guest) ?: guest
        }

        private val RANDOM = SecureRandom()

        /** 24-hex-char id from 12 random bytes, charset-compatible with [McpApprovalCore.validId]. */
        fun newId(): String {
            val bytes = ByteArray(12)
            RANDOM.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun serialize(state: State): String = JSONObject().apply {
            put("version", 1)
            put("epoch", state.epoch)
            put("tasks", JSONArray().apply {
                state.tasks.values.sortedBy { it.createdAtMs }.forEach { task ->
                    put(JSONObject().apply {
                        put("taskId", task.taskId)
                        put("name", task.name)
                        put("status", task.status.name)
                        put("epoch", task.epoch)
                        put("createdAtMs", task.createdAtMs)
                    })
                }
            })
            put("operations", JSONArray().apply {
                state.operations.values.sortedBy { it.createdAtMs }.forEach { op ->
                    put(JSONObject().apply {
                        put("operationId", op.operationId)
                        put("taskId", op.taskId)
                        put("requestId", op.requestId)
                        put("tool", op.tool)
                        put("argumentsJson", op.argumentsJson)
                        put("status", op.status.name)
                        put("createdAtMs", op.createdAtMs)
                        if (op.approvedAtMs != null) put("approvedAtMs", op.approvedAtMs)
                        if (op.sourceFileHash != null) put("sourceFileHash", op.sourceFileHash)
                        if (op.operationFingerprint != null) put("operationFingerprint", op.operationFingerprint)
                    })
                }
            })
            put("executedOperationIds", JSONArray().apply {
                state.executedOperationIds.sorted().forEach { put(it) }
            })
        }.toString()

        /** Returns null when the stored JSON is malformed or truncated — callers fall back to a fresh [State]. */
        fun parse(json: String): State? = runCatching {
            val root = JSONObject(json)
            val epoch = root.optLong("epoch", 1L)
            val tasks = root.optJSONArray("tasks") ?: JSONArray()
            val ops = root.optJSONArray("operations") ?: JSONArray()
            val executed = root.optJSONArray("executedOperationIds") ?: JSONArray()

            val taskMap = mutableMapOf<String, Task>()
            for (i in 0 until tasks.length()) {
                val t = tasks.getJSONObject(i)
                val task = Task(
                    taskId = t.getString("taskId"),
                    name = t.optString("name"),
                    status = enumOf<TaskStatus>(t.optString("status")),
                    epoch = t.optLong("epoch", 1L),
                    createdAtMs = t.optLong("createdAtMs"),
                )
                taskMap[task.taskId] = task
            }
            val opMap = mutableMapOf<String, Operation>()
            for (i in 0 until ops.length()) {
                val o = ops.getJSONObject(i)
                val op = Operation(
                    operationId = o.getString("operationId"),
                    taskId = o.optString("taskId"),
                    requestId = o.optString("requestId"),
                    tool = o.optString("tool"),
                    argumentsJson = o.optString("argumentsJson"),
                    status = enumOfOr(o.optString("status"), OperationStatus.REVOKED),
                    createdAtMs = o.optLong("createdAtMs"),
                    approvedAtMs = if (o.has("approvedAtMs")) o.getLong("approvedAtMs") else null,
                    sourceFileHash = if (o.has("sourceFileHash")) o.getString("sourceFileHash") else null,
                    operationFingerprint = if (o.has("operationFingerprint")) o.getString("operationFingerprint") else null,
                )
                opMap[op.operationId] = op
            }
            val executedIds = mutableSetOf<String>()
            for (i in 0 until executed.length()) executedIds.add(executed.getString(i))
            State(epoch = epoch, tasks = taskMap, operations = opMap, executedOperationIds = executedIds)
        }.getOrNull()

        private inline fun <reified T : Enum<T>> enumOf(name: String): T =
            enumValues<T>().firstOrNull { it.name == name } ?: enumValues<T>().first()

        /**
         * Fail-closed variant: an unknown stored value degrades to [fallback]
         * instead of "the first enum constant". Used where the first constant
         * is the permissive one (a grant), so a corrupt row can never be read
         * back as something the user may act on.
         */
        private inline fun <reified T : Enum<T>> enumOfOr(name: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }

    @Volatile
    private var cached: State = run {
        val raw = prefs.getString(KEY_STATE, null)
        if (raw == null) State() else parse(raw) ?: State()
    }

    fun load(): State = cached

    /**
     * Atomic read-modify-write: the transform runs under the lock, the result
     * is cached and flushed. Returns the new state so callers can reply from
     * the same snapshot they just persisted.
     */
    fun update(transform: (State) -> State): State = synchronized(this) {
        val next = transform(cached)
        cached = next
        prefs.edit().putString(KEY_STATE, serialize(next)).apply()
        next
    }

    fun replace(state: State) = update { state }

    /** Forget every task/operation (e.g. "clear all approvals" from the UI); epoch survives. */
    fun clearApprovals(): State = update { it.copy(tasks = emptyMap(), operations = emptyMap()) }
}
