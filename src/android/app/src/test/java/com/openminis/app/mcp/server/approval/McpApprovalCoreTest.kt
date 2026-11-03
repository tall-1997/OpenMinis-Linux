package com.openminis.app.mcp.server.approval

import com.openminis.app.mcp.server.approval.McpApprovalCore.ErrorCode
import com.openminis.app.mcp.server.approval.McpApprovalCore.Outcome
import com.openminis.app.mcp.server.approval.McpApprovalCore.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-approval] Contract tests for the three-phase approval state machine
 * (task request → per-run approval → idempotent execute). Pure JVM, no
 * Android types — the store layer is a thin prefs JSON wrapper around this.
 *
 * The happy path helper below walks the whole dsh flow:
 *   createTask → approveTask → submitRun → approveOperation → canExecute
 *   → markExecuted → repeat execute refused (no replay).
 */
class McpApprovalCoreTest {

    private val T0 = 1_000_000L

    private fun ok(state: Outcome): State = (state as Outcome.Ok).state
    private fun errCode(state: Outcome): ErrorCode = (state as Outcome.Err).code

    // -- helpers -------------------------------------------------------------

    private fun happyState(
        taskId: String = "task-1",
        operationId: String = "op-1",
        args: String = """{"command":"echo hi"}""",
        epoch: Long = 1L,
        approvedAtMs: Long = T0,
    ): State {
        var s = State(epoch = epoch)
        s = ok(McpApprovalCore.createTask(s, taskId, "demo", T0))
        s = ok(McpApprovalCore.approveTask(s, taskId))
        val requestId = McpApprovalCore.requestIdFor(taskId, "shell_exec", args)
        s = ok(McpApprovalCore.submitRun(s, taskId, operationId, requestId, "shell_exec", args, T0))
        s = ok(McpApprovalCore.approveOperation(s, operationId, approvedAtMs))
        return s
    }

    // -- task phase ----------------------------------------------------------

    @Test
    fun `create task starts pending and records epoch`() {
        val s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        val task = s.tasks.getValue("task-1")
        assertEquals(McpApprovalCore.TaskStatus.PENDING, task.status)
        assertEquals(1L, task.epoch)
        assertEquals("demo", task.name)
    }

    @Test
    fun `duplicate task id is refused`() {
        val s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        val again = McpApprovalCore.createTask(s, "task-1", "other", T0)
        assertEquals(ErrorCode.TASK_ALREADY_EXISTS, errCode(again))
    }

    @Test
    fun `task ids are restricted to a safe charset`() {
        for (bad in listOf("", "a b", "a/b", "a\\b", "ü", "a".repeat(65), "../x")) {
            val r = McpApprovalCore.createTask(State(), bad, "demo", T0)
            assertEquals("id '$bad'", ErrorCode.INVALID_ID, errCode(r))
        }
        val good = ok(McpApprovalCore.createTask(State(), "aB-0._x", "demo", T0))
        assertTrue(good.tasks.containsKey("aB-0._x"))
    }

    @Test
    fun `blank task name is refused`() {
        val r = McpApprovalCore.createTask(State(), "task-1", "   ", T0)
        assertEquals(ErrorCode.INVALID_NAME, errCode(r))
    }

    @Test
    fun `approve task moves pending to active`() {
        var s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        s = ok(McpApprovalCore.approveTask(s, "task-1"))
        assertEquals(McpApprovalCore.TaskStatus.ACTIVE, s.tasks.getValue("task-1").status)
    }

    @Test
    fun `approve unknown task is refused`() {
        val r = McpApprovalCore.approveTask(State(), "ghost")
        assertEquals(ErrorCode.TASK_NOT_FOUND, errCode(r))
    }

    @Test
    fun `revoke task cascades to its operations`() {
        var s = happyState()
        s = ok(McpApprovalCore.revokeTask(s, "task-1"))
        assertEquals(McpApprovalCore.TaskStatus.REVOKED, s.tasks.getValue("task-1").status)
        assertTrue(s.operations.isEmpty())
        // Idempotency table survives: replay protection is not undone by revoke.
        assertTrue(s.executedOperationIds.isEmpty())
    }

    @Test
    fun `revoke idempotent`() {
        var s = happyState()
        s = ok(McpApprovalCore.revokeTask(s, "task-1"))
        s = ok(McpApprovalCore.revokeTask(s, "task-1"))
        assertEquals(McpApprovalCore.TaskStatus.REVOKED, s.tasks.getValue("task-1").status)
    }

    @Test
    fun `submit run on pending task is refused until approved`() {
        var s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        val args = """{"command":"echo hi"}"""
        val r = McpApprovalCore.submitRun(
            s, "task-1", "op-1", McpApprovalCore.requestIdFor("task-1", "shell_exec", args),
            "shell_exec", args, T0,
        )
        assertEquals(ErrorCode.TASK_NOT_ACTIVE, errCode(r))
        s = ok(McpApprovalCore.approveTask(s, "task-1"))
        val r2 = McpApprovalCore.submitRun(
            s, "task-1", "op-1", McpApprovalCore.requestIdFor("task-1", "shell_exec", args),
            "shell_exec", args, T0,
        )
        assertTrue(r2 is Outcome.Ok)
    }

    @Test
    fun `submit run on unknown task is refused`() {
        val args = """{"command":"echo hi"}"""
        val r = McpApprovalCore.submitRun(
            State(), "ghost", "op-1", McpApprovalCore.requestIdFor("ghost", "shell_exec", args),
            "shell_exec", args, T0,
        )
        assertEquals(ErrorCode.TASK_NOT_FOUND, errCode(r))
    }

    // -- requestId integrity -------------------------------------------------

    @Test
    fun `request id is a deterministic content hash`() {
        val a = McpApprovalCore.requestIdFor("task-1", "shell_exec", """{"command":"x"}""")
        val b = McpApprovalCore.requestIdFor("task-1", "shell_exec", """{"command":"x"}""")
        assertEquals(a, b)
        assertEquals(32, a.length)
        assertTrue(a.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `request id differs when any input differs`() {
        val base = McpApprovalCore.requestIdFor("t", "shell_exec", """{"command":"x"}""")
        assertFalse(base == McpApprovalCore.requestIdFor("t2", "shell_exec", """{"command":"x"}"""))
        assertFalse(base == McpApprovalCore.requestIdFor("t", "file_read", """{"command":"x"}"""))
        assertFalse(base == McpApprovalCore.requestIdFor("t", "shell_exec", """{"command":"y"}"""))
    }

    @Test
    fun `tampered request id is refused - content is immutable`() {
        var s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        s = ok(McpApprovalCore.approveTask(s, "task-1"))
        val args = """{"command":"echo hi"}"""
        val r = McpApprovalCore.submitRun(s, "task-1", "op-1", "0".repeat(32), "shell_exec", args, T0)
        assertEquals(ErrorCode.REQUEST_ID_MISMATCH, errCode(r))
    }

    // -- argument cap --------------------------------------------------------

    @Test
    fun `arguments over 64KiB are refused`() {
        var s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        s = ok(McpApprovalCore.approveTask(s, "task-1"))
        val big = """{"command":"${"x".repeat(McpApprovalCore.MAX_ARGUMENT_CHARS)}}""""
        val r = McpApprovalCore.submitRun(
            s, "task-1", "op-1", McpApprovalCore.requestIdFor("task-1", "shell_exec", big),
            "shell_exec", big, T0,
        )
        assertEquals(ErrorCode.ARGUMENTS_TOO_LARGE, errCode(r))
    }

    @Test
    fun `duplicate operation id is refused`() {
        val s = happyState()
        val args = """{"command":"echo again"}"""
        val r = McpApprovalCore.submitRun(
            s, "task-1", "op-1", McpApprovalCore.requestIdFor("task-1", "shell_exec", args),
            "shell_exec", args, T0,
        )
        assertEquals(ErrorCode.OPERATION_EXISTS, errCode(r))
    }

    // -- approval phase ------------------------------------------------------

    @Test
    fun `approve operation moves waiting to approved`() {
        val s = happyState(approvedAtMs = T0)
        val op = s.operations.getValue("op-1")
        assertEquals(McpApprovalCore.OperationStatus.APPROVED, op.status)
        assertEquals(T0, op.approvedAtMs)
    }

    @Test
    fun `approve unknown operation is refused`() {
        val r = McpApprovalCore.approveOperation(State(), "ghost", T0)
        assertEquals(ErrorCode.OPERATION_NOT_FOUND, errCode(r))
    }

    @Test
    fun `double approve is refused`() {
        val s = happyState()
        val r = McpApprovalCore.approveOperation(s, "op-1", T0 + 1)
        assertEquals(ErrorCode.OPERATION_NOT_WAITING, errCode(r))
    }

    @Test
    fun `approve operation of revoked task is refused`() {
        var s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        s = ok(McpApprovalCore.approveTask(s, "task-1"))
        val args = """{"command":"echo hi"}"""
        s = ok(McpApprovalCore.submitRun(
            s, "task-1", "op-1", McpApprovalCore.requestIdFor("task-1", "shell_exec", args),
            "shell_exec", args, T0,
        ))
        s = ok(McpApprovalCore.revokeTask(s, "task-1"))
        val r = McpApprovalCore.approveOperation(s, "op-1", T0)
        assertEquals(ErrorCode.OPERATION_NOT_FOUND, errCode(r))
    }

    // -- execute phase -------------------------------------------------------

    @Test
    fun `happy path - execute allowed then idempotently blocked`() {
        val s = happyState()
        val grant = McpApprovalCore.canExecute(s, "op-1", T0 + 1_000)
        assertTrue(grant is Outcome.Ok)
        val s2 = ok(McpApprovalCore.markExecuted(s, "op-1"))
        assertTrue(s2.executedOperationIds.contains("op-1"))
        val replay = McpApprovalCore.canExecute(s2, "op-1", T0 + 2_000)
        assertEquals(ErrorCode.ALREADY_EXECUTED, errCode(replay))
    }

    @Test
    fun `execute without approval is refused`() {
        var s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        s = ok(McpApprovalCore.approveTask(s, "task-1"))
        val args = """{"command":"echo hi"}"""
        s = ok(McpApprovalCore.submitRun(
            s, "task-1", "op-1", McpApprovalCore.requestIdFor("task-1", "shell_exec", args),
            "shell_exec", args, T0,
        ))
        val r = McpApprovalCore.canExecute(s, "op-1", T0)
        assertEquals(ErrorCode.OPERATION_NOT_WAITING, errCode(r))
    }

    @Test
    fun `execute after epoch bump is refused - restart revokes everything`() {
        val s = happyState()
        val bumped = McpApprovalCore.bumpEpoch(s)
        assertEquals(2L, bumped.epoch)
        val r = McpApprovalCore.canExecute(bumped, "op-1", T0 + 1_000)
        assertEquals(ErrorCode.EPOCH_MISMATCH, errCode(r))
    }

    @Test
    fun `submit run after epoch bump is refused`() {
        val s = happyState()
        val bumped = McpApprovalCore.bumpEpoch(s)
        val args = """{"command":"echo hi2"}"""
        val r = McpApprovalCore.submitRun(
            bumped, "task-1", "op-2", McpApprovalCore.requestIdFor("task-1", "shell_exec", args),
            "shell_exec", args, T0 + 2_000,
        )
        assertEquals(ErrorCode.EPOCH_MISMATCH, errCode(r))
    }

    @Test
    fun `epoch bump marks outstanding grants revoked but keeps executed rows`() {
        // Executed: the row keeps its grant so a reconnect can replay the result.
        val executed = ok(McpApprovalCore.markExecuted(happyState(), "op-1"))
        val bumped = McpApprovalCore.bumpEpoch(executed)
        assertEquals(
            McpApprovalCore.OperationStatus.APPROVED,
            bumped.operations.getValue("op-1").status,
        )
        // Never executed: revoked outright, never left looking approved.
        val pending = happyState(operationId = "op-2")
        val bumped2 = McpApprovalCore.bumpEpoch(pending)
        assertEquals(
            McpApprovalCore.OperationStatus.REVOKED,
            bumped2.operations.getValue("op-2").status,
        )
    }

    @Test
    fun `revoked operation cannot be approved after a restart`() {
        val bumped = McpApprovalCore.bumpEpoch(happyState())
        val r = McpApprovalCore.approveOperation(bumped, "op-1", T0 + 5_000)
        assertEquals(ErrorCode.EPOCH_MISMATCH, errCode(r))
    }

    @Test
    fun `approval expires after TTL`() {
        val s = happyState(approvedAtMs = T0)
        val within = McpApprovalCore.canExecute(s, "op-1", T0 + McpApprovalCore.APPROVAL_TTL_MS - 1)
        assertTrue(within is Outcome.Ok)
        val expired = McpApprovalCore.canExecute(s, "op-1", T0 + McpApprovalCore.APPROVAL_TTL_MS + 1)
        assertEquals(ErrorCode.APPROVAL_EXPIRED, errCode(expired))
    }

    @Test
    fun `execute unknown operation is refused`() {
        val r = McpApprovalCore.canExecute(State(), "ghost", T0)
        assertEquals(ErrorCode.OPERATION_NOT_FOUND, errCode(r))
    }

    @Test
    fun `mark executed of unknown operation is refused`() {
        val r = McpApprovalCore.markExecuted(State(), "ghost")
        assertEquals(ErrorCode.OPERATION_NOT_FOUND, errCode(r))
    }

    @Test
    fun `source file hash survives approval and round trip`() {
        var s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        s = ok(McpApprovalCore.approveTask(s, "task-1"))
        val args = """{"path":"/var/minis/mcp-tasks/task-1/note.md"}"""
        s = ok(McpApprovalCore.submitRun(
            s, "task-1", "op-1", McpApprovalCore.requestIdFor("task-1", "file_write", args),
            "file_write", args, T0,
        ))
        s = ok(McpApprovalCore.approveOperation(s, "op-1", T0, sourceFileHash = "ab12"))
        assertEquals("ab12", s.operations.getValue("op-1").sourceFileHash)
    }

    // -- pruning -------------------------------------------------------------

    @Test
    fun `prune removes expired approved operations and stale waiting ones`() {
        var s = happyState(approvedAtMs = T0)
        val args = """{"command":"echo stale"}"""
        s = ok(McpApprovalCore.submitRun(
            s, "task-1", "op-2", McpApprovalCore.requestIdFor("task-1", "shell_exec", args),
            "shell_exec", args, T0,
        ))
        // now is past both the approval TTL (op-1) and the waiting-prune
        // horizon (op-2): both must go.
        val now = T0 + McpApprovalCore.WAITING_PRUNE_MS + 1
        val pruned = McpApprovalCore.pruneExpired(s, now)
        assertTrue(pruned.operations.isEmpty())
        // Tasks and the idempotency table are not pruned.
        assertTrue(pruned.tasks.containsKey("task-1"))
    }

    @Test
    fun `prune keeps fresh approved operations`() {
        val s = happyState(approvedAtMs = T0)
        val pruned = McpApprovalCore.pruneExpired(s, T0 + 1_000)
        assertTrue(pruned.operations.containsKey("op-1"))
    }

    // -- round trip stability ------------------------------------------------

    @Test
    fun `state is fully inspectable for the approvals UI`() {
        var s = happyState()
        s = ok(McpApprovalCore.submitRun(
            s, "task-1", "op-2", McpApprovalCore.requestIdFor("task-1", "file_read", """{"path":"/a"}"""),
            "file_read", """{"path":"/a"}""", T0 + 10,
        ))
        assertEquals(1, s.tasks.size)
        assertEquals(2, s.operations.size)
        assertEquals(1, s.operations.values.count { it.status == McpApprovalCore.OperationStatus.WAITING })
    }

    // -- fingerprint binding -------------------------------------------------

    @Test
    fun `fingerprint bound at approval must be presented at execute`() {
        var s = happyState()
        val op = s.operations.getValue("op-1")
        assertTrue(op.operationFingerprint == null)
    }

    @Test
    fun `execute-time fingerprint check is bidirectional`() {
        var s = State(epoch = 7L)
        s = ok(McpApprovalCore.createTask(s, "task-fp", "fp demo", T0))
        s = ok(McpApprovalCore.approveTask(s, "task-fp"))
        val args = """{"path":"/tmp/a"}"""
        // No binding: nothing may be presented later.
        s = ok(McpApprovalCore.submitRun(
            s, "task-fp", "op-nobind", McpApprovalCore.requestIdFor("task-fp", "file_read", args),
            "file_read", args, T0,
        ))
        s = ok(McpApprovalCore.approveOperation(s, "op-nobind", T0))
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH,
            (McpApprovalCore.fingerprintMatches(s, "op-nobind", "some-fp") as Outcome.Err).code)
        assertTrue(McpApprovalCore.fingerprintMatches(s, "op-nobind", null) is Outcome.Ok)

        // Bound at approval: execute must present the same value.
        s = ok(McpApprovalCore.submitRun(
            s, "task-fp", "op-bound", McpApprovalCore.requestIdFor("task-fp", "file_read", args),
            "file_read", args, T0,
        ))
        s = ok(McpApprovalCore.approveOperation(s, "op-bound", T0, operationFingerprint = "abc123"))
        assertEquals("abc123", s.operations.getValue("op-bound").operationFingerprint)
        assertTrue(McpApprovalCore.fingerprintMatches(s, "op-bound", "abc123") is Outcome.Ok)
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH,
            (McpApprovalCore.fingerprintMatches(s, "op-bound", "other") as Outcome.Err).code)
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH,
            (McpApprovalCore.fingerprintMatches(s, "op-bound", null) as Outcome.Err).code)
    }

    @Test
    fun `cancel drops only WAITING operations`() {
        var s = happyState(approvedAtMs = T0)
        val args = """{"command":"echo waiting"}"""
        s = ok(McpApprovalCore.submitRun(
            s, "task-1", "op-2", McpApprovalCore.requestIdFor("task-1", "shell_exec", args),
            "shell_exec", args, T0,
        ))
        s = ok(McpApprovalCore.cancelOperation(s, "op-2"))
        assertFalse(s.operations.containsKey("op-2"))
        assertTrue(s.operations.containsKey("op-1"))
        // Approved operations are immutable.
        assertEquals(McpApprovalCore.ErrorCode.OPERATION_NOT_WAITING,
            (McpApprovalCore.cancelOperation(s, "op-1") as Outcome.Err).code)
    }

    @Test
    fun `delete removes task and its operations but keeps the idempotency table`() {
        var s = happyState(approvedAtMs = T0)
        s = ok(McpApprovalCore.markExecuted(s, "op-1"))
        s = ok(McpApprovalCore.deleteTask(s, "task-1"))
        assertFalse(s.tasks.containsKey("task-1"))
        assertTrue(s.operations.isEmpty())
        assertTrue(s.executedOperationIds.contains("op-1"))
        assertEquals(McpApprovalCore.ErrorCode.TASK_NOT_FOUND,
            (McpApprovalCore.deleteTask(s, "task-1") as Outcome.Err).code)
    }

    // -- restart / revocation ------------------------------------------------

    @Test
    fun `revoked operations age out on the waiting clock`() {
        var s = happyState(approvedAtMs = T0)
        s = McpApprovalCore.bumpEpoch(s)
        // Same instant: the UI still gets to explain what happened.
        assertEquals(McpApprovalCore.OperationStatus.REVOKED, s.operations.getValue("op-1").status)
        assertEquals(1, McpApprovalCore.pruneExpired(s, T0 + 1).operations.size)
        // Past WAITING_PRUNE_MS a revoked grant is dropped like an unanswered run.
        val later = T0 + McpApprovalCore.WAITING_PRUNE_MS + 1
        assertTrue(McpApprovalCore.pruneExpired(s, later).operations.isEmpty())
        // While a never-approved run with the same age also goes away.
        assertTrue(McpApprovalCore.pruneExpired(State(epoch = 1L), later).operations.isEmpty())
    }

    @Test
    fun `approved operations still prune on the approval clock after a revoke`() {
        val s = happyState(approvedAtMs = T0 + 1_000)
        val fresh = McpApprovalCore.pruneExpired(s, T0 + 2_000)
        assertEquals(1, fresh.operations.size)
        val stale = McpApprovalCore.pruneExpired(s, T0 + 1_000 + McpApprovalCore.APPROVAL_TTL_MS + 1)
        assertTrue(stale.operations.isEmpty())
    }

    @Test
    fun `an unapproved run is not pruned by the approval clock`() {
        var s = happyState(approvedAtMs = T0)
        // op-2 never gets an approval timestamp; it lives on the waiting clock,
        // so a long gap must not drop it before WAITING_PRUNE_MS.
        val args = """{"command":"echo slow"}"""
        s = ok(McpApprovalCore.submitRun(
            s, "task-1", "op-2", McpApprovalCore.requestIdFor("task-1", "shell_exec", args),
            "shell_exec", args, T0,
        ))
        val pastTtl = McpApprovalCore.pruneExpired(s, T0 + McpApprovalCore.APPROVAL_TTL_MS + 1)
        assertTrue(pastTtl.operations.containsKey("op-2"))
    }

    @Test
    fun `approving a task from a previous generation is refused`() {
        var s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        s = McpApprovalCore.bumpEpoch(s)
        assertEquals(ErrorCode.EPOCH_MISMATCH, errCode(McpApprovalCore.approveTask(s, "task-1")))
        // The restart already retired the row, and the refused call adds nothing.
        assertEquals(McpApprovalCore.TaskStatus.REVOKED, s.tasks.getValue("task-1").status)
    }

    @Test
    fun `restart retires every task row`() {
        var s = happyState()
        val args = """{"command":"echo slow"}"""
        s = ok(McpApprovalCore.createTask(s, "task-2", "second", T0))
        s = ok(McpApprovalCore.approveTask(s, "task-2"))
        s = ok(
            McpApprovalCore.submitRun(
                s, "task-2", "op-2", McpApprovalCore.requestIdFor("task-2", "shell_exec", args),
                "shell_exec", args, T0,
            ),
        )
        s = McpApprovalCore.bumpEpoch(s)
        // Every row is retired, not just the ones with a grant attached.
        for (id in listOf("task-1", "task-2")) {
            assertEquals(id, McpApprovalCore.TaskStatus.REVOKED, s.tasks.getValue(id).status)
        }
    }

    @Test
    fun `a stale task id can be reused after a restart`() {
        var s = happyState()
        s = McpApprovalCore.bumpEpoch(s)
        // A client that re-walks the flow keeps using its usual ids: the dead
        // generation must not turn that id into a permanent dead end.
        s = ok(McpApprovalCore.createTask(s, "task-1", "again", T0 + 10))
        val task = s.tasks.getValue("task-1")
        assertEquals(McpApprovalCore.TaskStatus.PENDING, task.status)
        assertEquals(s.epoch, task.epoch)
        // Old operations do not leak into the fresh task.
        assertFalse(s.operations.containsKey("op-1"))
        assertTrue(s.operations.isEmpty())
        // The new generation's happy path still works end to end.
        s = ok(McpApprovalCore.approveTask(s, "task-1"))
        assertEquals(McpApprovalCore.TaskStatus.ACTIVE, s.tasks.getValue("task-1").status)
    }

    @Test
    fun `same generation duplicate ids stay refused`() {
        val s = ok(McpApprovalCore.createTask(State(), "task-1", "demo", T0))
        assertEquals(ErrorCode.TASK_ALREADY_EXISTS, errCode(McpApprovalCore.createTask(s, "task-1", "demo", T0 + 1)))
    }

    @Test
    fun `dead task rows age out of the store`() {
        var s = happyState()
        s = McpApprovalCore.bumpEpoch(s)
        // Fresh: the UI can still show the restart explanation.
        assertTrue(McpApprovalCore.pruneExpired(s, T0 + 1).tasks.containsKey("task-1"))
        // Old: the row is unreachable forever, so the store reclaims it.
        val later = T0 + McpApprovalCore.WAITING_PRUNE_MS + 1
        assertTrue(McpApprovalCore.pruneExpired(s, later).tasks.isEmpty())
    }

    @Test
    fun `restart reason wins over row status on every decision path`() {
        var s = happyState(approvedAtMs = T0)
        s = McpApprovalCore.bumpEpoch(s)
        // Both approve and execute report the generation, not "not waiting":
        // the client's retry logic keys off the difference.
        assertEquals(ErrorCode.EPOCH_MISMATCH, errCode(McpApprovalCore.approveOperation(s, "op-1", T0)))
        assertEquals(ErrorCode.EPOCH_MISMATCH, errCode(McpApprovalCore.canExecute(s, "op-1", T0)))
    }
}
