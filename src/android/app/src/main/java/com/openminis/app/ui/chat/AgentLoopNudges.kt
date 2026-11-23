package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.SubAgentBatchRegistry

/**
 * Loop-tail helpers extracted verbatim from runAgentLoop so the loop body
 * stays under its line ceiling: pure string assembly and failure-bounded
 * registry reads over the detached sub-agent batches. No state, no model
 * cost. Goal drift reconciliation lives in [com.openminis.app.goal]
 * (GoalManager.recordUsage / driftNudgeIfOwed) where the Goal row it reads
 * belongs.
 */
internal object AgentLoopNudges {

    /**
     * Extracted verbatim from runAgentLoop: the sub-agent collect reminder
     * text. Pure string assembly over the batch registry.
     */
    internal fun renderSubAgentCollectNudge(pending: List<SubAgentBatchRegistry.Batch>): String = buildString {
        append("<system-reminder>\n")
        append(SubAgentBatchRegistry.renderPending(pending))
        append('\n')
        pending.forEach { b ->
            append("- dispatch ").append(b.id.take(8)).append(": ")
            append(
                if (b.complete) {
                    "finished, reports NOT collected yet"
                } else {
                    "${b.running}/${b.lanes.size} lane(s) still running"
                },
            )
            append(" (").append(b.done).append(" done, ").append(b.failed).append(" failed)\n")
        }
        append(
            "\nDo not end the turn as if nothing had been dispatched. Call check_agent now: " +
                "op=await with timeout_sec to wait for running lanes, op=collect to read the finished " +
                "reports, then fold those results into your answer. If a lane genuinely cannot finish " +
                "in time, say so explicitly — name the dispatch id, state that it is still running, and " +
                "tell the user it can be collected later with check_agent.",
        )
        append("\n</system-reminder>")
    }

    /**
     * [T-subagent-completion-timeliness] ZCode drains a finished background
     * task's notification right after a tool batch (and as a model-only turn
     * when idle), instead of waiting for the model to end its turn. The
     * Android loop has no cross-coroutine wake, so the closest faithful port
     * is: after every committed tool batch, hand the parent a bounded digest
     * of waves that finished but were never collected. Only NEWLY finished
     * batches are reported (batch id + finishedAt), so a wave that keeps
     * running never re-interrupts the loop with the same words.
     */
    internal fun newlyFinishedSubAgentCollects(
        sessionId: String,
        context: Context?,
        alreadyAnnounced: Set<String>,
    ): List<SubAgentBatchRegistry.Batch> =
        pendingSubAgentCollects(sessionId, context).filter { batch ->
            batch.complete && batch.collectedAt == 0L &&
                batch.finishedAt > 0L && batch.id !in alreadyAnnounced
        }

    /**
     * [T-subagent-completion-timeliness] The after-batch drain: returns the
     * nudge text for waves that finished since the last call (marking them
     * announced), or null when there is nothing new. Budget is the caller's.
     */
    internal fun drainFinishedWaves(
        sessionId: String,
        context: Context?,
        alreadyAnnounced: MutableSet<String>,
    ): String? {
        val finished = newlyFinishedSubAgentCollects(sessionId, context, alreadyAnnounced.toSet())
        if (finished.isEmpty()) return null
        alreadyAnnounced.addAll(finished.map { it.id })
        return renderSubAgentCollectNudge(finished)
    }

    /** Failure-bounded read of the detached sub-agent waves that still owe a result. */
    internal fun pendingSubAgentCollects(sessionId: String, context: Context?): List<SubAgentBatchRegistry.Batch> =
        runCatching { SubAgentBatchRegistry.unfinished(sessionId, context) }
            .getOrElse { e ->
                AppLogger.warning("ChatViewModel", "sub-agent registry read failed: ${e.message}")
                emptyList()
            }
}
