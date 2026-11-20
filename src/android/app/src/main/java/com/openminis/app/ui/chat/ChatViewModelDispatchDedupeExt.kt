package com.openminis.app.ui.chat

import com.openminis.app.tools.SubAgentBatchRegistry
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolReceiptDispatch
import kotlinx.coroutines.sync.Semaphore

internal data class SpawnDispatchOptions(
    val limiter: Semaphore? = null,
    val parallelWriters: Int = 1,
    val waveIndex: Int = 0,
    val waveSize: Int = 1,
)

/**
 * [T-dispatch-idempotence] Spawns a sub-agent wave at most once per identity.
 *
 * A dispatch used to be a plain side effect: a retried tool call, a model that
 * re-emitted `spawn_agent` after a stall, or two lanes asked the same question
 * each produced fresh workers. The transcript then had two waves doing the same
 * work and no way to tell which one to read. The prototype gets idempotence from
 * a trigger id (`prompts/dispatch.md:46`); this path has no trigger, so the wave
 * content is the identity: same kinds and same briefs (in any order) inside a
 * ten-minute window is the same wave, and the caller gets the running batch back
 * instead of a second one.
 *
 * Deliberately not a lock on identical prompts forever: a genuine re-run after
 * the window, or after the first wave finished, still spawns.
 */
internal suspend fun ChatViewModel.executeRunSubAgentDeduped(
    argsJson: String,
    toolId: String = "",
    toolBlocks: MutableList<AssistantBlock>? = null,
    assistantId: String = "",
    currentText: String = "",
    options: SpawnDispatchOptions,
    delivery: ToolReceiptDispatch.Delivery?,
): ToolExecutionResult {
    scopeRefusal(argsJson)?.let { refusal ->
        delivery?.notSent()
        return refusal
    }
    val fingerprint = SubAgentBatchRegistry.fingerprintOf(argsJson)
    val duplicate = SubAgentBatchRegistry.findLiveDuplicate(activeSessionId, context, fingerprint)
    if (duplicate != null) {
        delivery?.notSent()
        val running = duplicate.lanes.count { !it.finished }
        return ToolExecutionResult(
            "Not dispatched again: an identical wave is already in flight " +
                "(batch ${duplicate.id.take(8)}, $running of ${duplicate.lanes.size} lanes running). " +
                "Collect its results with check_agent instead of re-dispatching the same briefs.",
            false,
        )
    }
    return executeRunSubAgent(
        argsJson, toolId, toolBlocks, assistantId, currentText,
        limiter = options.limiter, parallelWriters = options.parallelWriters,
        waveIndex = options.waveIndex, waveSize = options.waveSize, delivery = delivery,
    )
}

internal fun ChatViewModel.spawnDispatchArgs(canonical: String, args: String): String = when (canonical) {
    com.openminis.app.tools.DispatchAgentsTool.NAME -> com.openminis.app.tools.DispatchAgentsTool.toSpawnArgs(args, context)
    com.openminis.app.tools.WolfpackTool.NAME -> com.openminis.app.tools.WolfpackTool.toSpawnArgs(args)
    else -> args
}

/**
 * [T-task-mandate] Refuses a wave whose kinds fall outside the declared scope.
 *
 * Null when there is nothing to enforce: no mandate, or one with no scope, keeps
 * the old behaviour so sessions that never opted in are unaffected.
 */
private fun ChatViewModel.scopeRefusal(argsJson: String): ToolExecutionResult? {
    val mandate = runCatching {
        com.openminis.app.tools.TaskMandateStore.get(activeSessionId, context)
    }.getOrNull() ?: return null
    if (!mandate.declared) return null
    val kinds = runCatching { kindsOf(argsJson) }.getOrDefault(emptyList())
    val offending = kinds.firstOrNull {
        !com.openminis.app.tools.TaskMandateStore.allows(mandate, it)
    } ?: return null
    return ToolExecutionResult(
        com.openminis.app.tools.TaskMandateStore.refusal(mandate, offending),
        false,
    )
}

/** Lane kinds in a spawn payload; a single-task payload has one. */
private fun kindsOf(argsJson: String): List<String> {
    val root = runCatching { org.json.JSONObject(argsJson) }.getOrNull() ?: return emptyList()
    val tasks = root.optJSONArray("tasks")
        ?: return listOf(root.optString("kind").ifBlank { "worker" })
    return (0 until tasks.length()).map { index ->
        tasks.optJSONObject(index)?.optString("kind")?.ifBlank { null } ?: "worker"
    }
}
