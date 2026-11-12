package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.sandbox.jobs.JobRegistry
import com.openminis.app.tools.AgentPlanStore
import com.openminis.app.tools.AgentReportStore
import com.openminis.app.tools.SubAgentBatchRegistry

/**
 * [T-state-hydration] Host-side recovery of the live task state.
 *
 * Plan, report, sub-agent waves and background jobs ride in tool results, so a
 * compaction or a progressive-trim offload can delete the only copy — and the
 * model then has to know to call `agent_plan op=list` / `agent_report op=get`
 * to get it back. That is a dependency on model behavior, which is exactly
 * what this branch removes.
 *
 * Instead the HOST notices the loss (compaction, trim, marker detach) and
 * writes the stores' current snapshot back into the transcript once. No
 * prompt rule, no model cooperation: the request the model receives simply
 * contains its own task state again.
 */
internal object SessionStateSnapshot {

    /** First line of a committed state note; the ledger uses it to detect eviction. */
    const val MARKER = "Session state restored by the host"

    /** Build the snapshot from the stores; null when there is nothing to restore. */
    fun build(sessionId: String, context: Context?): String? {
        if (sessionId.isBlank()) return null
        val sections = ArrayList<String>(4)

        runCatching {
            val plans = AgentPlanStore.list(sessionId, context)
            if (plans.isNotEmpty()) {
                val done = plans.count { it.status == "done" }
                sections += buildString {
                    append("Plan (${done}/${plans.size} done):")
                    plans.take(MAX_PLAN_LINES).forEachIndexed { index, plan ->
                        append("\n${index + 1}. [${plan.status}] ${plan.title}")
                    }
                    if (plans.size > MAX_PLAN_LINES) append("\n+${plans.size - MAX_PLAN_LINES} more items")
                }
            }
        }

        runCatching {
            AgentReportStore.latest(sessionId, context)?.let { report ->
                sections += "Report:\n" + AgentReportStore.renderSnapshot(report)
            }
        }

        runCatching {
            val waves = SubAgentBatchRegistry.unfinished(sessionId, context)
            if (waves.isNotEmpty()) {
                sections += buildString {
                    append("Unfinished sub-agent waves:")
                    waves.take(MAX_WAVES).forEach { batch ->
                        append("\n- wave ${batch.id.take(8)}: ${batch.running} lane(s) open")
                        if (batch.complete) {
                            append(" (finished, not collected — collect it)")
                        }
                    }
                }
            }
        }

        runCatching {
            val running = JobRegistry.list(sessionId).filter { it.state == JobRegistry.JobState.RUNNING }
            if (running.isNotEmpty()) {
                sections += buildString {
                    append("Running background jobs:")
                    running.take(MAX_JOBS).forEach { job ->
                        append("\n- ${job.id.take(8)}: ${job.command.take(COMMAND_CHARS)}")
                    }
                }
            }
        }

        return sections.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private const val MAX_PLAN_LINES = 20
    private const val MAX_WAVES = 5
    private const val MAX_JOBS = 8
    private const val COMMAND_CHARS = 120
}
