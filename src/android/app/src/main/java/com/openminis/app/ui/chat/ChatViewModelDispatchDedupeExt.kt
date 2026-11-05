package com.openminis.app.ui.chat

import com.openminis.app.tools.SubAgentBatchRegistry
import com.openminis.app.tools.ToolExecutionResult

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
): ToolExecutionResult {
    val fingerprint = SubAgentBatchRegistry.fingerprintOf(argsJson)
    val duplicate = SubAgentBatchRegistry.findLiveDuplicate(activeSessionId, context, fingerprint)
    if (duplicate != null) {
        val running = duplicate.lanes.count { !it.finished }
        return ToolExecutionResult(
            "Not dispatched again: an identical wave is already in flight " +
                "(batch ${duplicate.id.take(8)}, $running of ${duplicate.lanes.size} lanes running). " +
                "Collect its results with check_agent instead of re-dispatching the same briefs.",
            true,
        )
    }
    return executeRunSubAgent(argsJson, toolId, toolBlocks, assistantId, currentText)
}
