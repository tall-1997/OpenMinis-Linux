package com.openminis.app.mcp.server.approval

import com.openminis.app.util.Digest

/**
 * [T-mcp-approval] Pure decision core for the three-phase external MCP
 * approval flow (dsh parity):
 *
 *  1. phone_task_request   — task-level grant, confirmed by the user in the
 *                            approvals UI (PENDING → ACTIVE).
 *  2. phone_local_run      — per-run request whose [requestId] is the content
 *                            hash of (taskId, tool, argumentsJson): the caller
 *                            cannot rename a run's content after the fact.
 *  3. phone_operation_execute — idempotent by [operationId]: a repeated id is
 *                            never replayed, an approval dies with its epoch
 *                            (server restart) or its TTL.
 *
 * No Android types anywhere — [McpApprovalStore] persists [State] as JSON and
 * [McpApprovedExecutor] enforces the file-level checks (source hash, task
 * root) that need the filesystem.
 *
 * Invariants (asserted by McpApprovalCoreTest):
 *  - every run is tied to an ACTIVE task whose epoch matches the state epoch;
 *  - bumpEpoch() alone revokes every outstanding grant (restart semantics);
 *  - markExecuted is the only way into [State.executedOperationIds], and the
 *    only way out of a replay attempt.
 */
object McpApprovalCore {

    /** Argument payload cap: dsh "参数 ≤64KiB". */
    const val MAX_ARGUMENT_CHARS = 64 * 1024

    /** Executed operations run ≤30s (the executor clamps shell timeouts to this). */
    const val MAX_RUN_SECONDS = 30

    /** Tool output returned to the external caller is truncated to 64KiB. */
    const val MAX_OUTPUT_CHARS = 64 * 1024

    /** Task-directory files must be regular files ≤128KiB. */
    const val MAX_TASK_FILE_BYTES = 128L * 1024

    /** An approval lives 10 minutes; after that the caller must re-request. */
    const val APPROVAL_TTL_MS = 10 * 60 * 1000L

    /** Waiting operations older than this are pruned. */
    const val WAITING_PRUNE_MS = 60 * 60 * 1000L

    private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

    enum class TaskStatus { PENDING, ACTIVE, REVOKED }
    enum class OperationStatus { WAITING, APPROVED, REVOKED }

    enum class ErrorCode {
        INVALID_ID, INVALID_NAME,
        TASK_NOT_FOUND, TASK_ALREADY_EXISTS, TASK_NOT_ACTIVE,
        ARGUMENTS_TOO_LARGE, REQUEST_ID_MISMATCH, OPERATION_EXISTS,
        OPERATION_NOT_FOUND, OPERATION_NOT_WAITING,
        ALREADY_EXECUTED, APPROVAL_EXPIRED, EPOCH_MISMATCH,
    }

    data class Task(
        val taskId: String,
        val name: String,
        val status: TaskStatus,
        val epoch: Long,
        val createdAtMs: Long,
    )

    data class Operation(
        val operationId: String,
        val taskId: String,
        val requestId: String,
        val tool: String,
        val argumentsJson: String,
        val status: OperationStatus,
        val createdAtMs: Long,
        val approvedAtMs: Long? = null,
        val sourceFileHash: String? = null,
        /** Approval-binding fingerprint: when set at approval time, the execute call must present the same value. */
        val operationFingerprint: String? = null,
    )

    data class State(
        val epoch: Long = 1L,
        val tasks: Map<String, Task> = emptyMap(),
        val operations: Map<String, Operation> = emptyMap(),
        val executedOperationIds: Set<String> = emptySet(),
    )

    sealed interface Outcome {
        data class Ok(val state: State) : Outcome
        data class Err(val code: ErrorCode, val message: String) : Outcome
    }

    private fun ok(state: State) = Outcome.Ok(state)
    private fun err(code: ErrorCode, message: String) = Outcome.Err(code, message)

    // -- ids -----------------------------------------------------------------

    /**
     * Content hash of one run request. The external caller supplies this value
     * in phone_local_run; the core recomputes it, so any divergence between the
     * submitted requestId and (taskId, tool, arguments) is rejected.
     */
    fun requestIdFor(taskId: String, tool: String, argumentsJson: String): String =
        Digest.sha256Hex("$taskId\n$tool\n$argumentsJson").take(32)

    fun validId(id: String): Boolean = ID_PATTERN.matches(id)

    // -- task phase ----------------------------------------------------------

    fun createTask(state: State, taskId: String, name: String, nowMs: Long): Outcome {
        if (!validId(taskId)) return err(ErrorCode.INVALID_ID, "taskId must match [A-Za-z0-9._-]{1,64}")
        if (name.isBlank()) return err(ErrorCode.INVALID_NAME, "task name must not be blank")
        val existing = state.tasks[taskId]
        // Same-id reuse is refused only inside one generation. A row left over
        // from a previous epoch is already dead (the restart revoked it), so a
        // client that re-walks the flow with its usual task ids must not be
        // pushed into inventing new ones forever.
        if (existing != null && existing.epoch == state.epoch) {
            return err(ErrorCode.TASK_ALREADY_EXISTS, "task '$taskId' already exists")
        }
        val staleOps = state.operations.filterValues { it.taskId == taskId }
        val task = Task(taskId, name, TaskStatus.PENDING, state.epoch, nowMs)
        return ok(
            state.copy(
                tasks = state.tasks + (taskId to task),
                operations = state.operations - staleOps.keys,
            ),
        )
    }

    fun approveTask(state: State, taskId: String): Outcome {
        val task = state.tasks[taskId] ?: return err(ErrorCode.TASK_NOT_FOUND, "task '$taskId' not found")
        // Generation check first: a task created before a restart is not a task
        // this run may activate, whatever its row status says.
        if (task.epoch != state.epoch) return err(ErrorCode.EPOCH_MISMATCH, "task '$taskId' belongs to epoch ${task.epoch}")
        if (task.status != TaskStatus.PENDING) return err(ErrorCode.TASK_NOT_ACTIVE, "task '$taskId' is ${task.status}")
        return ok(state.copy(tasks = state.tasks + (taskId to task.copy(status = TaskStatus.ACTIVE))))
    }

    /**
     * Revoke a task and every outstanding run under it. Executed ids stay in
     * the idempotency table — revoking does not re-open a replay window.
     */
    fun revokeTask(state: State, taskId: String): Outcome {
        val task = state.tasks[taskId] ?: return err(ErrorCode.TASK_NOT_FOUND, "task '$taskId' not found")
        if (task.status == TaskStatus.REVOKED) return ok(state)
        val remaining = state.operations.filterValues { it.taskId != taskId }
        return ok(
            state.copy(
                tasks = state.tasks + (taskId to task.copy(status = TaskStatus.REVOKED)),
                operations = remaining,
            ),
        )
    }

    // -- run phase -----------------------------------------------------------

    fun submitRun(
        state: State,
        taskId: String,
        operationId: String,
        requestId: String,
        tool: String,
        argumentsJson: String,
        nowMs: Long,
    ): Outcome {
        val task = state.tasks[taskId] ?: return err(ErrorCode.TASK_NOT_FOUND, "task '$taskId' not found")
        if (task.epoch != state.epoch) return err(ErrorCode.EPOCH_MISMATCH, "task '$taskId' belongs to epoch ${task.epoch}")
        if (task.status != TaskStatus.ACTIVE) return err(ErrorCode.TASK_NOT_ACTIVE, "task '$taskId' is ${task.status}")
        if (!validId(operationId)) return err(ErrorCode.INVALID_ID, "operationId must match [A-Za-z0-9._-]{1,64}")
        if (state.operations.containsKey(operationId)) {
            return err(ErrorCode.OPERATION_EXISTS, "operation '$operationId' already exists")
        }
        if (argumentsJson.length > MAX_ARGUMENT_CHARS) {
            return err(ErrorCode.ARGUMENTS_TOO_LARGE, "arguments exceed $MAX_ARGUMENT_CHARS chars")
        }
        val expected = requestIdFor(taskId, tool, argumentsJson)
        if (requestId != expected) {
            return err(ErrorCode.REQUEST_ID_MISMATCH, "requestId does not match the run content hash")
        }
        val op = Operation(
            operationId = operationId,
            taskId = taskId,
            requestId = requestId,
            tool = tool,
            argumentsJson = argumentsJson,
            status = OperationStatus.WAITING,
            createdAtMs = nowMs,
        )
        return ok(state.copy(operations = state.operations + (operationId to op)))
    }

    fun approveOperation(
        state: State,
        operationId: String,
        nowMs: Long,
        sourceFileHash: String? = null,
        operationFingerprint: String? = null,
    ): Outcome {
        val op = state.operations[operationId] ?: return err(ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
        val task = state.tasks[op.taskId] ?: return err(ErrorCode.TASK_NOT_FOUND, "task '${op.taskId}' not found")
        if (task.epoch != state.epoch) return err(ErrorCode.EPOCH_MISMATCH, "task '${op.taskId}' belongs to epoch ${task.epoch}")
        if (op.status != OperationStatus.WAITING) return err(ErrorCode.OPERATION_NOT_WAITING, "operation '$operationId' is ${op.status}")
        if (task.status != TaskStatus.ACTIVE) return err(ErrorCode.TASK_NOT_ACTIVE, "task '${op.taskId}' is ${task.status}")
        if (operationFingerprint != null && operationFingerprint.length > 128) {
            return err(ErrorCode.INVALID_ID, "operationFingerprint must be ≤128 chars")
        }
        val approved = op.copy(
            status = OperationStatus.APPROVED,
            approvedAtMs = nowMs,
            sourceFileHash = sourceFileHash,
            operationFingerprint = operationFingerprint,
        )
        return ok(state.copy(operations = state.operations + (operationId to approved)))
    }

    // -- execute phase -------------------------------------------------------

    /**
     * Execute gate, evaluated inside phone_operation_execute before any tool
     * runs: approved + same epoch + within TTL + not yet executed. Source-file
     * hashes are checked by [com.openminis.app.mcp.server.McpApprovedExecutor]
     * where the filesystem is available.
     */
    fun canExecute(state: State, operationId: String, nowMs: Long): Outcome {
        val op = state.operations[operationId] ?: return err(ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
        if (state.executedOperationIds.contains(operationId)) {
            return err(ErrorCode.ALREADY_EXECUTED, "operation '$operationId' was already executed")
        }
        // Restart wins over row state: a grant revoked by an epoch bump must
        // report *why* it is gone (the client re-walks the flow) instead of
        // looking like a run that was never approved.
        val task = state.tasks[op.taskId] ?: return err(ErrorCode.TASK_NOT_FOUND, "task '${op.taskId}' not found")
        if (task.epoch != state.epoch) return err(ErrorCode.EPOCH_MISMATCH, "task '${op.taskId}' belongs to epoch ${task.epoch}")
        if (op.status != OperationStatus.APPROVED) return err(ErrorCode.OPERATION_NOT_WAITING, "operation '$operationId' is ${op.status}")
        val approvedAt = op.approvedAtMs ?: return err(ErrorCode.OPERATION_NOT_WAITING, "operation '$operationId' has no approval time")
        if (nowMs - approvedAt > APPROVAL_TTL_MS) {
            return err(ErrorCode.APPROVAL_EXPIRED, "approval for '$operationId' expired")
        }
        return ok(state)
    }

    /**
     * Execute-time fingerprint check: a fingerprint bound at approval must be
     * presented verbatim at execute time; presenting one when none was bound
     * is also rejected (the binding is bidirectional).
     */
    fun fingerprintMatches(state: State, operationId: String, presented: String?): Outcome {
        val op = state.operations[operationId]
            ?: return err(ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
        val bound = op.operationFingerprint
        return when {
            bound == null && presented == null -> ok(state)
            bound != null && bound == presented -> ok(state)
            bound != null -> err(ErrorCode.REQUEST_ID_MISMATCH, "operationFingerprint does not match the approval binding")
            else -> err(ErrorCode.REQUEST_ID_MISMATCH, "no fingerprint was bound at approval; presenting one is rejected")
        }
    }

    /**
     * Cancel a WAITING operation: it is dropped from the state. Approved or
     * executed operations are immutable (an approval or execution may not be
     * unwritten).
     */
    fun cancelOperation(state: State, operationId: String): Outcome {
        val op = state.operations[operationId]
            ?: return err(ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
        if (op.status != OperationStatus.WAITING) {
            return err(ErrorCode.OPERATION_NOT_WAITING, "operation '$operationId' is ${op.status}")
        }
        return ok(state.copy(operations = state.operations - operationId))
    }

    /**
     * Delete a task and every operation under it. Executed ids stay in the
     * idempotency table — deleting does not re-open a replay window.
     */
    fun deleteTask(state: State, taskId: String): Outcome {
        if (!state.tasks.containsKey(taskId)) {
            return err(ErrorCode.TASK_NOT_FOUND, "task '$taskId' not found")
        }
        return ok(
            state.copy(
                tasks = state.tasks - taskId,
                operations = state.operations.filterValues { it.taskId != taskId },
            ),
        )
    }

    /** Record an execution. Idempotent: marking an already-marked id is a no-op. */
    fun markExecuted(state: State, operationId: String): Outcome {
        if (!state.operations.containsKey(operationId) &&
            !state.executedOperationIds.contains(operationId)
        ) {
            return err(ErrorCode.OPERATION_NOT_FOUND, "operation '$operationId' not found")
        }
        if (state.executedOperationIds.contains(operationId)) return ok(state)
        return ok(state.copy(executedOperationIds = state.executedOperationIds + operationId))
    }

    // -- lifecycle -----------------------------------------------------------

    /**
     * Restart semantics: a new epoch invalidates every outstanding grant.
     * Callers reconnect and must walk the approval flow again — nothing is
     * restored and nothing is replayed.
     */
    fun bumpEpoch(state: State): State = state.copy(
        epoch = state.epoch + 1,
        // Every task row becomes REVOKED too: the whole generation is gone, and
        // leaving rows behind would stack a dead card per restart in the UI.
        tasks = state.tasks.mapValues { (_, task) -> task.copy(status = TaskStatus.REVOKED) },
        // Grants never survive a restart. Every grant that has not been
        // executed becomes REVOKED — the approvals UI can then say "lost to a
        // restart" instead of showing an approved row that only fails at
        // execute time. Executed rows keep APPROVED: their result is still
        // replayable. The epoch bump stays as the authoritative refusal.
        operations = state.operations.mapValues { (id, op) ->
            if (id in state.executedOperationIds) op else op.copy(status = OperationStatus.REVOKED)
        },
    )

    /**
     * Housekeeping: drop APPROVED operations past their TTL and WAITING
     * operations older than [WAITING_PRUNE_MS]. Tasks and the idempotency
     * table survive.
     */
    fun pruneExpired(state: State, nowMs: Long): State {
        val kept = state.operations.filterValues { op ->
            when (op.status) {
                OperationStatus.WAITING -> nowMs - op.createdAtMs < WAITING_PRUNE_MS
                OperationStatus.APPROVED -> op.approvedAtMs?.let { nowMs - it < APPROVAL_TTL_MS } ?: false
                // A revoked grant is dead weight the moment a restart writes it,
                // but the approvals UI needs a while to explain what happened —
                // it ages out on the same clock as an unanswered run.
                OperationStatus.REVOKED -> nowMs - op.createdAtMs < WAITING_PRUNE_MS
            }
        }
        // Dead task rows age out on the same clock, otherwise every restart
        // would leave one more unreachable row in the store. Live-generation
        // rows and the idempotency table stay put.
        val taskIdsWithOps = kept.values.mapTo(HashSet()) { it.taskId }
        val keptTasks = state.tasks.filterValues { task ->
            task.taskId in taskIdsWithOps || task.epoch == state.epoch ||
                nowMs - task.createdAtMs < WAITING_PRUNE_MS
        }
        return state.copy(operations = kept, tasks = keptTasks)
    }
}
