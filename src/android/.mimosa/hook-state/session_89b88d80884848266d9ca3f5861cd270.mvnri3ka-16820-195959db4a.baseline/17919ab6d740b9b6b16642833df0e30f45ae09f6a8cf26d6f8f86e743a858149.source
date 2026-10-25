package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Inspection and collection surface for sub-agent waves.
 *
 * `spawn_agent` with `background=true` returns immediately with a dispatch id,
 * which only helps if the model can later ask what happened. This tool is that
 * ask: a non-blocking snapshot, a bounded wait, or the collected reports. It
 * reads [SubAgentBatchRegistry], so it also answers for waves that were
 * interrupted by a process death — their lanes come back as `stopped` instead of
 * silently vanishing.
 */
object CheckAgentTool {

    const val NAME = "check_agent"

    private const val DEFAULT_TIMEOUT_SEC = 60
    private const val MAX_TIMEOUT_SEC = 600
    private const val POLL_MS = 500L

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Inspect or collect sub-agent dispatch waves started with spawn_agent. " +
            "op=status returns a snapshot immediately; op=await blocks until the wave finishes " +
            "or timeout_sec elapses; op=collect returns the finished lanes' reports; " +
            "op=list returns every wave recorded for this chat; op=clear drops finished waves. " +
            "Use this after a background dispatch instead of re-running the work.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "op" to AgentToolParam(
                "string",
                "status (default), await, collect, list, or clear.",
                enumValues = listOf("status", "await", "collect", "list", "clear"),
            ),
            "dispatch_id" to AgentToolParam(
                "string",
                "Wave id from spawn_agent. Omit to target the most recent unfinished wave, " +
                    "or all waves for op=list/clear.",
            ),
            "timeout_sec" to AgentToolParam(
                "integer",
                "For op=await: seconds to wait before returning a partial snapshot (default 60, max 600).",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "op", "dispatch_id", "timeout_sec"),
    )

    suspend fun execute(argsJson: String, sessionId: String, context: Context?): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid check_agent arguments", false, toolTitle = NAME)
        }
        val toolTitle = args.optString("tool_title", NAME).ifBlank { NAME }
        val op = args.optString("op", "status").ifBlank { "status" }
        val requestedId = args.optString("dispatch_id", "").trim().takeIf { it.isNotEmpty() }
        val registry = SubAgentBatchRegistry

        // A wave that a dead process left `running` would otherwise make every
        // later status call claim unfinished work. Reconcile on read.
        runCatching { registry.reconcileAfterRestart(sessionId, context) }

        return when (op) {
            "clear" -> {
                val (removed, keptUncollected) = runCatching {
                    registry.clearFinished(sessionId, context)
                }.getOrDefault(0 to 0)
                // Never let housekeeping delete a report nobody has read yet.
                val note = if (keptUncollected > 0) {
                    " Kept $keptUncollected finished wave(s) whose reports were never collected — " +
                        "call op=collect to read them before clearing."
                } else {
                    ""
                }
                ToolExecutionResult("Cleared $removed finished dispatch record(s).$note", true, toolTitle = toolTitle)
            }

            "list" -> {
                val all = registry.list(sessionId, context)
                if (all.isEmpty()) {
                    ToolExecutionResult("(no sub-agent dispatch recorded for this chat)", true, toolTitle = toolTitle)
                } else {
                    val body = all.joinToString("\n") { registry.render(it) }
                    ToolExecutionResult("${all.size} dispatch record(s):\n\n$body", true, toolTitle = toolTitle)
                }
            }

            "await" -> {
                val timeoutSec = args.optInt("timeout_sec", DEFAULT_TIMEOUT_SEC)
                    .coerceIn(1, MAX_TIMEOUT_SEC)
                val target = resolve(registry, sessionId, context, requestedId)
                if (target == null) return notFound(requestedId, toolTitle)
                val deadline = System.currentTimeMillis() + timeoutSec * 1000L
                // Explicit non-null type: `var batch = target` would widen back
                // to Batch? and lose the smart cast from the null check above.
                var batch: SubAgentBatchRegistry.Batch = target
                while (!batch.complete && System.currentTimeMillis() < deadline) {
                    delay(POLL_MS)
                    batch = registry.get(sessionId, context, batch.id) ?: break
                }
                val waited = batch.complete
                // [T-subagent-background] Reading the finished outcome *is* the
                // collection event — mark it so the agent loop stops nudging
                // about a dispatch whose reports are already in context.
                if (waited) runCatching { registry.markCollected(sessionId, context, batch.id) }
                val header = if (waited) {
                    "Dispatch ${batch.id.take(8)} finished."
                } else {
                    "Dispatch ${batch.id.take(8)} still running after ${timeoutSec}s — partial snapshot below."
                }
                ToolExecutionResult(
                    header + "\n\n" + registry.render(batch, verbose = true),
                    true,
                    toolTitle = toolTitle,
                )
            }

            "collect" -> {
                val target = resolve(registry, sessionId, context, requestedId)
                if (target == null) return notFound(requestedId, toolTitle)
                val body = buildString {
                    append("## dispatch ").append(target.id.take(8)).append('\n')
                    target.lanes.forEach { lane ->
                        append("\n### lane ").append(lane.index).append(" · ")
                        append(lane.title.ifBlank { lane.kind })
                        append(" [").append(lane.state).append("]\n")
                        if (lane.error != null) append("error: ").append(lane.error).append('\n')
                        if (lane.digest.isNotBlank()) {
                            append(lane.digest).append('\n')
                        } else if (!lane.finished) {
                            append("(still running — no report yet)").append('\n')
                        } else {
                            append("(no captured output)").append('\n')
                        }
                    }
                }
                if (target.complete) runCatching { registry.markCollected(sessionId, context, target.id) }
                val note = if (target.complete) "" else
                    "\n\n(${target.running} lane(s) still in flight; call check_agent op=await to wait for them.)"
                ToolExecutionResult(body + note, true, toolTitle = toolTitle)
            }

            else -> {
                val target = resolve(registry, sessionId, context, requestedId)
                if (target == null) return notFound(requestedId, toolTitle)
                val suffix = if (target.complete) "" else
                    "\n\nStill in flight. Use op=await with timeout_sec to block until it finishes, " +
                        "or continue other work and check again later."
                ToolExecutionResult(
                    registry.render(target) + suffix,
                    true,
                    toolTitle = toolTitle,
                )
            }
        }
    }

    /**
     * Explicit id wins (prefix match, so the 8-char form the model sees works);
     * otherwise the newest unfinished wave, otherwise the newest wave.
     */
    private fun resolve(
        registry: SubAgentBatchRegistry,
        sessionId: String,
        context: Context?,
        requestedId: String?,
    ): SubAgentBatchRegistry.Batch? {
        val all = registry.list(sessionId, context)
        if (all.isEmpty()) return null
        if (requestedId != null) {
            return all.find { it.id == requestedId }
                ?: all.find { it.id.startsWith(requestedId) }
        }
        return all.firstOrNull { !it.complete } ?: all.firstOrNull()
    }

    private fun notFound(requestedId: String?, toolTitle: String): ToolExecutionResult {
        val which = requestedId?.let { " with id starting '$it'" } ?: ""
        return ToolExecutionResult(
            "No sub-agent dispatch$which is recorded for this chat. " +
                "Use op=list to see recorded waves, or spawn_agent to start one.",
            false,
            toolTitle = toolTitle,
        )
    }
}
