package com.openminis.app.mcp.server.approval

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.mcp.server.approval.McpApprovalCore.Operation
import com.openminis.app.mcp.server.approval.McpApprovalCore.Outcome
import com.openminis.app.mcp.server.approval.McpApprovalCore.State
import com.openminis.app.mcp.server.approval.McpApprovalCore.Task

/**
 * [T-mcp-approval] Context-bound facade over [McpApprovalStore] +
 * [McpApprovalCore]: every mutation is a read-modify-write transaction that
 * only persists on an Ok outcome, so a rejected request can never leave a
 * half-applied state on disk.
 *
 * Thread-safety: the approval store is a single JSON file; all mutations are
 * serialized through one monitor. Reads are cheap snapshots of the store's
 * in-memory cache.
 */
class McpApprovalManager internal constructor(private val store: McpApprovalStore) {

    constructor(context: Context) : this(McpApprovalStore(context.applicationContext))

    private val now: Long get() = System.currentTimeMillis()

    private fun load(): State = store.load()

    private fun persist(state: State) = store.replace(state)

    private inline fun transact(fn: (State) -> Outcome): Outcome = synchronized(this) {
        when (val out = fn(load())) {
            is Outcome.Ok -> {
                persist(out.state)
                out
            }
            is Outcome.Err -> out
        }
    }

    // -- task phase ----------------------------------------------------------

    fun createTask(taskId: String, name: String): Outcome =
        transact { McpApprovalCore.createTask(it, taskId, name, now) }

    fun approveTask(taskId: String): Outcome =
        transact { McpApprovalCore.approveTask(it, taskId) }

    fun revokeTask(taskId: String): Outcome =
        transact { McpApprovalCore.revokeTask(it, taskId) }

    fun cancelOperation(operationId: String): Outcome =
        transact { McpApprovalCore.cancelOperation(it, operationId) }

    fun deleteTask(taskId: String): Outcome =
        transact { McpApprovalCore.deleteTask(it, taskId) }

    // -- run phase -----------------------------------------------------------

    fun submitRun(taskId: String, operationId: String, tool: String, argumentsJson: String): Outcome =
        transact {
            McpApprovalCore.submitRun(
                state = it,
                taskId = taskId,
                operationId = operationId,
                requestId = McpApprovalCore.requestIdFor(taskId, tool, argumentsJson),
                tool = tool,
                argumentsJson = argumentsJson,
                nowMs = now,
            )
        }

    fun approveOperation(operationId: String, sourceFileHash: String?, operationFingerprint: String?): Outcome =
        transact { McpApprovalCore.approveOperation(it, operationId, now, sourceFileHash, operationFingerprint) }

    // -- execute phase -------------------------------------------------------

    /** Gate check only — the executor runs the tool and then [markExecuted] commits. */
    fun tryExecute(operationId: String): Outcome =
        transact { McpApprovalCore.canExecute(it, operationId, now) }

    fun checkFingerprint(operationId: String, presented: String?): Outcome =
        transact { McpApprovalCore.fingerprintMatches(it, operationId, presented) }

    fun markExecuted(operationId: String): Outcome =
        transact { McpApprovalCore.markExecuted(it, operationId) }

    /** Housekeeping; returns the pruned state and the ids of removed operations. */
    fun pruneExpired(): Pair<State, Set<String>> = synchronized(this) {
        val before = load()
        val after = McpApprovalCore.pruneExpired(before, now)
        val removed = before.operations.keys - after.operations.keys
        if (removed.isNotEmpty()) persist(after)
        return@synchronized after to removed
    }

    // -- queries -------------------------------------------------------------

    fun state(): State = load()

    fun task(taskId: String): Task? = load().tasks[taskId]

    fun operation(operationId: String): Operation? = load().operations[operationId]

    fun taskOf(operationId: String): Task? = load().operations[operationId]?.let { load().tasks[it.taskId] }

    fun taskRootFor(taskId: String): String = McpApprovalStore.taskRootFor(taskId)

    /** Best-effort delete of a task directory after cleanup/prune. */
    fun deleteTaskDirectory(taskId: String): Boolean =
        runCatching {
            val dir = java.io.File(McpApprovalStore.taskRootFor(taskId))
            if (dir.exists()) {
                dir.deleteRecursively()
                true
            } else {
                false
            }
        }.getOrElse {
            AppLogger.warning("McpApprovalManager", "failed to delete task dir for $taskId: ${it.message}")
            false
        }
}
