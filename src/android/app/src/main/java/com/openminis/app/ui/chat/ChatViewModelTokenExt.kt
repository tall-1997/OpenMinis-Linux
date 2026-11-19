package com.openminis.app.ui.chat

import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextTokenEstimate
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.UsageState
import com.openminis.app.data.calibratedContextTokens
import com.openminis.app.data.foldToolUse
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.ContextUsage
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.LLMProvider

internal fun ChatViewModel.estimateAgentHistoryTokens(): Int =
    estimateMessagesTokens(effectiveAgentHistoryUncounted())

/** History-only estimate for callers that do not have an assembled request. */
internal fun ChatViewModel.estimateMessagesTokens(
    messages: List<LLMMessage>,
    calibrated: Boolean = true,
): Int {
    val raw = ContextTokenMeter.rawMessagesTokens(messages)
    return if (calibrated) calibratedContextTokens(currentModelSnapshot(), raw) else raw
}

/**
 * Compatibility estimate for callers without a dispatch projection. The unbudgeted
 * effective slice protects unsummarized history; a previous real usage is not a floor.
 */
internal fun ChatViewModel.contextTokensForPolicy(): Int = maxOf(
    estimateAgentHistoryTokens(),
    estimateMessagesTokens(effectiveAgentHistoryUncounted(applyProgressiveTrim = false, applyTailBudget = false)),
)

/** Without the system/tools/projected history we cannot prove an anchor still matches. */
fun ChatViewModel.refreshContextUsage() = refreshContextUsage(
    ContextTokenEstimate(estimateAgentHistoryTokens()),
    currentModelContextWindow ?: 0,
)

internal fun ChatViewModel.refreshContextUsage(estimate: ContextTokenEstimate, contextWindow: Int) {
    contextUsage.value = ContextUsage(
        estimate.tokens.toLong(), contextWindow.toLong(), estimate.measuredPrefixTokens?.toLong(),
    )
}

/** Read-only view of the current thinking configuration for the model. */
fun ChatViewModel.thinkingInfo(): ChatViewModel.ThinkingInfo? {
    val model = currentModel ?: return null
    // [T-thinking-three-faces-unified] Use the SAME supported/enabled logic
    // as the main loop (ChatViewModel.kt currentModelSupportsReasoning):
    // force-thinking instances report supported even when the catalog says
    // otherwise — the old null-is-unsupported read contradicted it.
    val supported = currentModelSupportsReasoning
    val level = _thinkingLevel.value
    val enabled = supported && level.isEnabled
    val levelText = if (enabled) {
        level.localizedName(context)
    } else "—"
    return ChatViewModel.ThinkingInfo(supported, enabled, levelText)
}

/**
 * Load session-level token aggregates from the database. Suspend so the
 * Token Usage sheet can fetch on demand without keeping a live subscription
 * — token data rarely changes mid-view, and we want to avoid reactive
 * overhead per token chunk.
 */
suspend fun ChatViewModel.loadSessionTokenStats(): ChatViewModel.SessionTokenStats {
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return ChatViewModel.SessionTokenStats(0, 0, 0, 0, 0, 0)
    val usages = chatRepository.sessionTokenUsages(sid)
    var input = 0L
    var output = 0L
    var cacheRead = 0L
    var cacheWrite = 0L
    var context = 0
    // [T-thinking-dual-ledger] The MOST RECENT turn's ledger — the query is
    // sort_order ASC, so the last row carrying a requested level wins.
    var lastRequested: String? = null
    var lastEffective: String? = null
    for (json in usages) {
        try {
            val obj = org.json.JSONObject(json)
            input += obj.optLong("inputTokens", 0L)
            output += obj.optLong("outputTokens", 0L)
            cacheRead += obj.optLong("cacheReadTokens", 0L)
            cacheWrite += obj.optLong("cacheCreationTokens", 0L)
            val ctx = obj.optInt("latestContextTokens", 0)
            if (ctx > 0) context = ctx
            val requested = obj.optString("thinkingLevelRequested", "")
            if (requested.isNotEmpty()) {
                lastRequested = requested
                lastEffective = obj.optString("thinkingLevelEffective", "").ifEmpty { null }
            }
        } catch (_: Exception) { /* skip malformed row */ }
    }
    val snapshot = _messages.value
    val assistantCount = snapshot.count { it.role == "assistant" }
    val toolCalls = snapshot.filter { it.role == "assistant" }
        .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
    val loops = maxOf(toolCalls, assistantCount)
    return ChatViewModel.SessionTokenStats(
        input, output, cacheRead, cacheWrite, context, loops, lastRequested, lastEffective,
        toolCallCounts = sessionToolCallCounts(snapshot),
    )
}

/**
 * [T-pi-tools-bucket] UsageLedger tools bucket for one session: per-tool call
 * counts folded from the LOADED message window's `tool_use` blocks through
 * [foldToolUse] — the exact in-memory path the ledger's KDoc prescribes
 * ("fold from blocks ALREADY in memory"); a parts_json rebuild would pull
 * every stored message body (MBs, partially offloaded) just to count calls.
 *
 * Counts `tool_use` blocks with a real name ONLY: info/thinking blocks reuse
 * [AssistantBlock.toolName] for icons and markers, and toolResult blocks
 * mirror the same call set (would double-count). Scope note: the models
 * bucket aggregates ALL persisted usage rows (the token sums in
 * [loadSessionTokenStats]); the tools bucket covers the loaded window — the
 * same window loopCount already reports, so the two agent-activity figures
 * always agree on what a "session" is.
 *
 * Pure — JVM-tested.
 */
internal fun sessionToolCallCounts(messages: List<ChatMessage>): Map<String, Int> {
    val ledger = UsageState()
    for (message in messages) {
        if (message.role != "assistant") continue
        for (block in message.toolBlocks) {
            if (block.kind == "tool_use" && block.toolName.isNotBlank()) {
                foldToolUse(ledger, block.toolName)
            }
        }
    }
    return ledger.tools.mapValues { (_, usage) -> usage.calls.toInt() }
}

/**
 * Compute the output allowance for the provider actually serving the request.
 * [lastContextTokens] retains its name for compatibility; pass the current projected input.
 */
internal fun ChatViewModel.dynamicMaxTokens(
    provider: LLMProvider,
    lastContextTokens: Int = 0,
    contextWindow: Int = provider.model.contextWindowTokens,
): Int {
    val model = provider.model
    val maxOutputCeiling = minOf(ChatViewModel.GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
    val result = projectedOutputAllowance(maxOutputCeiling, lastContextTokens, contextWindow)
    if (result < maxOutputCeiling) {
        android.util.Log.i(ChatViewModel.TAG, "dynamicMaxTokens: $result (ceiling=$maxOutputCeiling, window=$contextWindow, input=$lastContextTokens, model=${model.id})")
    }
    return result
}

/** An unknown window retains the model ceiling; a full one leaves no output allowance. */
internal fun projectedOutputAllowance(ceiling: Int, inputTokens: Int, contextWindow: Int): Int =
    if (contextWindow <= 0) ceiling else minOf(ceiling, (contextWindow - inputTokens.coerceAtLeast(0)).coerceAtLeast(0))

/** Estimate the resident transcript for offload candidate selection. */
internal fun ChatViewModel.estimateContextTokens(): Int = estimateMessagesTokens(agentHistory)

/**
 * Approximate token count for a single agent content part. Used to rank
 * offload candidates by size. Matches iOS `BPETokenizer.countPartTokens`
 * — text uses BPE, images use the grid-cell heuristic.
 */
internal fun ChatViewModel.countPartTokens(part: AgentContentPart): Int = when (part) {
    is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
    is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.inputJson)
    is AgentContentPart.ToolResult -> {
        BPETokenizer.countTokens(part.content) +
            (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
    }
    is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
}

