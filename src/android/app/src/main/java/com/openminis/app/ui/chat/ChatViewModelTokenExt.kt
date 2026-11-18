package com.openminis.app.ui.chat

import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.UsageState
import com.openminis.app.data.foldToolUse
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.LLMProvider
import com.openminis.app.ui.chat.localizedName

// [T-android-split-chat-tokenext] Token estimation / context-usage accounting
// extracted verbatim from ChatViewModel.kt (mechanical split, zero behavior
// change). Member functions became ChatViewModel extensions with identical
// signatures. GLOBAL_MAX_TOKENS_CEILING and MIN_MAX_TOKENS were promoted
// private → internal (companion) since dynamicMaxTokens now lives here.
// SessionTokenStats / ThinkingInfo stay nested on ChatViewModel
// (TokenUsageSheet references ChatViewModel.SessionTokenStats).

/**
 * [T-compact-estimate-fallback] Cheap token estimate of the in-memory
 * agent history, used ONLY as a lower-bound floor when the last usage
 * chunk is missing or stale (`_lastTurnContextTokens` is refreshed by a
 * COMPLETED API call, so a tool-heavy stretch that has not produced a
 * new usage value yet would otherwise look "empty" to ContextPolicy and
 * skip compaction entirely). ASCII costs ~0.25 tok/char, CJK ~0.6 —
 * the classic len/4 estimator is ~4x off for CJK and would let long
 * Chinese sessions sail past the threshold.
 */
internal fun ChatViewModel.estimateAgentHistoryTokens(): Int {
    // Effective, not raw: with a compact marker in play the next request
    // carries the summary + kept tail, not the full history. Feeding the
    // raw size into the send-time / in-loop gates made every post-compact
    // check look over-threshold.
    return estimateMessagesTokens(effectiveAgentHistoryUncounted())
}

/**
 * [T-android-progressive-trim] Token estimate over an arbitrary message
 * list (extracted from estimateAgentHistoryTokens so the progressive-trim
 * level decision can estimate the UNTRIMMED payload with the exact same
 * arithmetic, calibration included).
 */
internal fun ChatViewModel.estimateMessagesTokens(
    messages: List<LLMMessage>,
    calibrated: Boolean = true,
): Int {
    var total = 0L
    for (msg in messages) {
        total += estimateMixedTokens(msg.content)
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.Text -> total += estimateMixedTokens(part.text)
                is AgentContentPart.ToolUse -> total += estimateMixedTokens(TokenScanMemo.toolUseInputJson(part.input))
                is AgentContentPart.ToolResult -> total += estimateMixedTokens(part.content)
                is AgentContentPart.ImageData -> total += 1_500
            }
        }
    }
    // [T-token-usage-calibration] 按模型维度用真实 usage 校准原始估算。
    // calibrated=false feeds the RAW sum to TokenUsageCalibration.observe —
    // observe(actual / (scale·raw)) has the fixed point √r, not r, so the
    // calibration loop must see the same scale-free shape it divides by.
    // ([T-calibration-raw], round-3 adversarial finding.)
    val raw = total.toInt()
    val modelKey = currentModel?.id ?: ""
    return if (calibrated && modelKey.isNotEmpty()) {
        com.openminis.app.data.TokenUsageCalibration.estimate(modelKey, raw)
    } else {
        raw
    }
}

private fun estimateMixedTokens(text: String): Long = TokenScanMemo.mixedTokens(text)

/**
 * max(real usage, effective estimate, untrimmed-effective estimate) so a
 * stale-but-real reading always wins.
 *
 * [T-android-inloop-compact-truthful-gate] The third term is the compact
 * gate's floor: the effective slice WITHOUT ProgressiveTrim and WITHOUT the
 * postAnchor tail budget. ProgressiveTrim strips thinking and stubs old tool
 * outputs but never drops messages, so a 992-entry pile-up weighed ~270k
 * tokens in memory while this function read the trimmed ~41k — the 80%
 * compact threshold never fired and the loop kept re-assembling 900 stale
 * tool rounds every turn.
 *
 * [T-compact-gate-unblinded] The tail budget is disabled for this term too.
 * That budget caps the measurable request at preAnchor (≤20%) + postAnchor
 * (≤35%) ≈ 55% of the window, so measuring the budgeted slice made the
 * threshold unreachable after the first compact: the marker froze, and every
 * later request silently dropped post-anchor rounds that the summary does not
 * cover. Measuring the UN-BUDGETED slice makes the gate fire again, which
 * moves the marker forward and turns those drops into a summary. The floor
 * uses the effective slice (not raw `agentHistory`) so compaction — which
 * shrinks exactly this slice — makes the post-compact check pass.
 */
internal fun ChatViewModel.contextTokensForPolicy(): Int =
    maxOf(
        _lastTurnContextTokens.value,
        estimateAgentHistoryTokens(),
        estimateMessagesTokens(
            effectiveAgentHistoryUncounted(
                applyProgressiveTrim = false,
                applyTailBudget = false,
            ),
        ),
    )

/**
 * Recompute contextUsage for the session-menu ring.
 *
 * [T-android-context-ring-zero] The char estimate walks the effective
 * (compacted) history — with a marker in play the raw list is not what
 * the next request carries ([T-android-compact-stale-usage]).
 * which for a long session is TAIL-PAGED (loadSessionTail) — a re-entered
 * session showed ~0 tokens even while the provider reported 23939/24688.
 * Prefer the REAL provider-reported context size from the last usage
 * chunk; fall back to the char estimate only before the first API call.
 */
fun ChatViewModel.refreshContextUsage() {
    val reported = _lastTurnContextTokens.value
    var usedChars = 0L
    var imageCount = 0
    for (msg in effectiveAgentHistoryUncounted()) {
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.Text -> usedChars += part.text.length
                is AgentContentPart.ToolUse -> usedChars += TokenScanMemo.toolUseInputJson(part.input).length
                is AgentContentPart.ToolResult -> {
                    usedChars += part.content.length
                }
                is AgentContentPart.ImageData -> imageCount += 1
            }
        }
    }
    // [T-pi-context-token-estimator] The flat chars/3.5 heuristic ignored
    // image parts entirely; the pi-ported estimator counts each image as
    // ~4800 chars (its empirical image-token weight) and ceils the char
    // division instead of truncating it.
    val estTokens = com.openminis.app.data.ContextTokenEstimator.estimateTextTokens(
        usedChars + imageCount * com.openminis.app.data.ContextTokenEstimator.ESTIMATED_IMAGE_CHARS,
    )
    val used = if (reported > 0) reported.toLong() else estTokens
    val window = currentModelContextWindow?.toLong() ?: 0L
    contextUsage.value = com.openminis.app.data.model.ContextUsage(used, window)
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
 * Compute max output tokens that fits within the remaining context window.
 * Logic mirrors iOS's dynamicMaxTokens():
 *   result = min(provider.defaultMaxTokens, max(contextWindow - inputTokens, MIN_MAX_TOKENS))
 *
 * @param provider The current LLM provider (carries defaultMaxTokens).
 * @param lastContextTokens API-reported input token count from the last call (0 = first call).
 */
internal fun ChatViewModel.dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
    val model = currentModel ?: return minOf(ChatViewModel.GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
    // Ceiling: min(global cap, model.maxOutputTokens-or-provider-default).
    // The global cap means we never send more than 128K regardless of
    // what the model claims it can output.
    val maxOutputCeiling = minOf(ChatViewModel.GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
    // Context window: model.contextWindow if known, else the shared
    // model-id heuristic. [T-anthropic-context-window] Route through
    // LLMModel.contextWindowTokens so the corrected Claude-1M / Gemini-1M
    // values apply here too, instead of the stale local "everything 200K"
    // copy that under-reported modern Claude/Gemini windows.
    val contextWindow = model.contextWindowTokens
    if (contextWindow <= 0) return maxOutputCeiling
    val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
    val remaining = contextWindow - inputTokens
    val clamped = maxOf(remaining, ChatViewModel.MIN_MAX_TOKENS)
    val result = minOf(maxOutputCeiling, clamped)
    if (result < maxOutputCeiling) {
        android.util.Log.i(ChatViewModel.TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
    }
    return result
}

/**
 * Char-based fallback estimate when the API hasn't reported a token
 * baseline yet (first call in a turn). Mirrors iOS line 7451.
 *
 * Uses ~3.5 chars per token for mixed text + adds the tokenizer's
 * image-aware count for image bytes. Underestimates JSON-heavy tool
 * inputs slightly but is adequate as a "should we offload" gate —
 * offload itself uses precise [BPETokenizer.countTokens] per-part
 * for the candidate ranking.
 */
internal fun ChatViewModel.estimateContextTokens(): Int {
    var totalChars = 0
    var imageTokens = 0
    for (msg in agentHistory) {
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.Text -> totalChars += part.text.length
                is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                is AgentContentPart.ToolResult -> {
                    totalChars += part.content.length
                    part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                }
                is AgentContentPart.ImageData -> {
                    imageTokens += BPETokenizer.countImageTokens(part.data)
                }
            }
        }
    }
    return (totalChars / 3.5).toInt() + imageTokens
}

/**
 * Approximate token count for a single agent content part. Used to rank
 * offload candidates by size. Matches iOS `BPETokenizer.countPartTokens`
 * — text uses BPE, images use the grid-cell heuristic.
 */
internal fun ChatViewModel.countPartTokens(part: AgentContentPart): Int = when (part) {
    is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
    is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
    is AgentContentPart.ToolResult -> {
        BPETokenizer.countTokens(part.content) +
            (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
    }
    is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
}

/**
 * [T-token-usage-calibration][T-compaction-cascade][T-calibration-raw] Feeds
 * the per-model calibration EMA from a completed stream turn. The estimated
 * side is the RAW (scale-free) sum of the PROJECTED request history — the
 * actual usage belongs to the projected shape (micro-prune + neutralize),
 * and feeding the calibrated form instead has the EMA fixed point at sqrt(r),
 * not r (CJK sessions under-report ~40%).
 */
internal fun ChatViewModel.observeCalibratedUsage(
    modelKey: String,
    requestHistory: List<LLMMessage>,
    actualTokens: Int,
) {
    if (modelKey.isEmpty() || actualTokens <= 0) return
    com.openminis.app.data.TokenUsageCalibration.observe(
        modelKey,
        estimated = estimateMessagesTokens(requestHistory, calibrated = false),
        actual = actualTokens,
    )
}

