package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

/**
 * [T-tool-turn-throw-isolation] One executor throwing (a malformed-args
 * JSONObject, an adapter bug…) used to escape the producer, cancel every
 * sibling lane via coroutineScope, and abort the whole run before any turn
 * persistence — the transcript kept bare tool_use parts and the user got a
 * generic inline error for a productive run. Every lane body is now
 * isolated: an unexpected throw becomes THIS call's failed tool_result
 * (pairing stays balanced), a per-call timeout becomes the call's timeout
 * style failure, and only a real run-level cancel still propagates (checked
 * via isActive; ensureActive at the loop boundary remains the authority).
 */

/**
 * Write [call]'s isolation failure into the executed parts AND flip its UI
 * block. The block flip matters: phase 3 only re-pushes the list, and
 * without this the card spins RUNNING for the rest of the session while the
 * persisted row keeps a running tool (no terminal sweep exists outside
 * user-stop). Same shape as the preflight-refusal flip.
 *
 * [T-tool-turn-throw-isolation] Duplicate-id runs: the joined loser must not
 * clobber a sibling's already-recorded SUCCESS with the isolation error —
 * skip when a non-error result is already settled.
 */
internal fun failIsolation(
    ctx: ToolTurnContext,
    execution: ToolTurnExecution,
    call: PlannedToolCall,
    message: String,
) {
    val existing = execution.executedParts[call.id]
    if (existing != null && !existing.isError) return
    val errorText = "Error: tool execution failed unexpectedly: $message"
    execution.executedParts[call.id] = AgentContentPart.ToolResult(
        id = call.id,
        name = call.name,
        content = errorText,
        isError = true,
    )
    // [T-tool-turn-throw-isolation] Flip the block under the SAME monitor
    // every other allToolBlocks mutator uses — the spawn fan-out runs this
    // concurrently with sibling peers' publishSubAgentCard, and an
    // unsynchronized throw inside this catch would escape the async and
    // cancel the whole wave (the exact behavior isolation exists to
    // prevent). The status flip accepts ANY non-terminal state: the spawn
    // fan-out path never runs the PENDING→RUNNING flip the serial lane
    // does, so a spawn peer's block is still PENDING when its producer
    // throws — restricting the flip to RUNNING left the card spinning
    // forever (PENDING renders as running in the UI).
    synchronized(ctx.allToolBlocks) {
        val failedIdx = ctx.allToolBlocks.indexOfFirst { it.id == call.id }
        if (failedIdx < 0) return
        if (ctx.allToolBlocks[failedIdx].toolStatus in TERMINAL_STATUSES) return
        val elapsed = System.currentTimeMillis() - ctx.allToolBlocks[failedIdx].startTimeMs
        ctx.allToolBlocks[failedIdx] = ctx.allToolBlocks[failedIdx].copy(
            toolStatus = ToolBlockStatus.FAILED,
            content = errorText,
            durationMs = elapsed,
        )
    }
}

/** Terminal tool-block states a failure flip must never overwrite. */
private val TERMINAL_STATUSES = setOf(
    ToolBlockStatus.SUCCESS,
    ToolBlockStatus.FAILED,
    ToolBlockStatus.TIMEOUT,
    ToolBlockStatus.CANCELLED,
)

/**
 * [T-tool-turn-throw-isolation] Isolate one lane body: catch its throws and
 * convert them into [fail] calls instead of letting them cancel the whole
 * turn's coroutineScope. A run-level cancel (isActive false) rethrows; a
 * per-call withTimeout escaping here becomes THIS call's timeout failure.
 */
internal suspend fun isolateLane(
    call: PlannedToolCall,
    fail: (String) -> Unit,
    body: suspend () -> Unit,
) {
    try {
        body()
    } catch (ce: kotlinx.coroutines.CancellationException) {
        if (!coroutineContext.isActive) throw ce
        fail("timed out: ${ce.message ?: ce.javaClass.simpleName}")
    } catch (t: Throwable) {
        com.openminis.app.logging.AppLogger.error(
            ChatViewModel.TAG,
            "tool ${call.name} threw unexpectedly; failing this call only: " +
                "${t.javaClass.simpleName}: ${t.message}",
        )
        fail(t.message ?: t.javaClass.simpleName)
    }
}
