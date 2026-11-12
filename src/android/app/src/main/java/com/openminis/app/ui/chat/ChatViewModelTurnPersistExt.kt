package com.openminis.app.ui.chat

import com.openminis.app.data.ToolLimitPrefs
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.NonFiniteJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// [T-android-split-chat-turnpersist] Turn finalization + per-turn persistence
// extracted verbatim from ChatViewModel.kt (mechanical split, zero behavior
// change). Member functions became ChatViewModel extensions with identical
// signatures, so every call site (agent loop, tool dispatch, sub-agent paths)
// keeps its exact syntax. `streamSession` was promoted private → internal to
// let the streaming delegates live here.

/**
 * Finalize the current assistant message when [runAgentLoop] hits the
 * MAX_AGENT_TURNS ceiling. Drops the streaming/awaiting flags so the
 * "thinking" indicator clears, writes an inline error explaining *why*
 * we stopped, and arms canResume so the user can continue from here.
 * Mirrors iOS AIChatViewModel.swift:4922-4929 pattern (canResume + error).
 */
internal fun ChatViewModel.finalizeAtTurnLimit(
    assistantId: String,
    text: String,
    blocks: List<AssistantBlock>,
    /** [T-android-resume-banner-race] The loop's epoch snapshot; see raiseCanResume. */
    fromResumeEpoch: Long,
) {
    // [T-android-thinking-indicator-linger][T-android-stream-flush-review]
    // Shared live-interruption tail (see drainLiveAssistantUi): the agent
    // loop ran on IO while this finalize hops to Main, so a late delta could
    // re-add the side-channel entry after the drain — drop it here as the
    // last Main-thread write of this turn.
    drainLiveAssistantUi(assistantId, text, blocks)
    setInlineError(
        "Stopped after ${ToolLimitPrefs.mainSessionMaxTurns()} agent turns to prevent runaway " +
        "tool use. The model kept calling tools without finishing — tap " +
        "Resume to continue from here, or send a new message to start over.",
    )
    // [T-android-group-pause-badge-restamp] A LIVE interruption just
    // happened: real entry into the paused state.
    markLiveInterruptionAndArmResume(fromResumeEpoch)
}

// [F3 GH#61] preflightValidateToolCall 已删除：required-field / empty-args 预检
// 下沉到共享层 ToolCallPreflight.requiredFieldViolation，由 ToolCallPreflight.finalize
// 对三个执行入口（主循环/子代理/群聊-主管）统一调用；主循环不再保留第二份实现。

internal suspend fun ChatViewModel.oneShotAsk(system: String, user: String): String {
    val provider = currentProvider ?: throw IllegalStateException("no provider")
    val buf = StringBuilder()
    provider.streamMessage(
        messages = listOf(LLMMessage(LLMMessage.Role.USER, user)),
        systemPrompt = system,
        maxTokens = 2048,
        temperature = samplingTemperature(_activeEntryId.value),
        tools = emptyList(),
        thinkingLevel = ThinkingLevel.OFF,
        // [GH#221] 一次性请求也带同一份额外头（OpenCode 会话头 + 用户 KV）。
        extraHeaders = requestExtraHeaders(),
    ).collect { chunk ->
        if (chunk is LLMStreamChunk.Text) buf.append(chunk.text)
    }
    return buf.toString().ifBlank { "(empty reasoning response)" }
}

/**
 * [T-android-vision-group / GH#182] Placeholder text for an image the CURRENT
 * main model can't natively see, to be carried on the outgoing image part and
 * substituted by the provider's T264 branch. Returns null when the main model
 * has native vision (pixels are attached, no placeholder needed) OR no Vision
 * Group is configured (provider falls back to its historical literal). When a
 * Vision Group IS configured, returns a hint naming [path] and steering the
 * model to call read_image — closing the loop with executeReadImageTool.
 */
internal fun ChatViewModel.visionPlaceholderFor(path: String?): String? {
    if (currentModelHasNativeVision) return null
    if (!com.openminis.app.tools.VisionGroupResolver.isConfigured(providerRepository, context)) return null
    return com.openminis.app.tools.VisionGroupResolver.noVisionImagePlaceholder(path)
}

// ─── UI Helpers ──────────────────────────────────────────────────────

internal fun ChatViewModel.updateAssistantMessage(
    id: String,
    content: String,
    isStreaming: Boolean,
    toolBlocks: List<AssistantBlock>,
    isAwaitingModelResponse: Boolean = false,
) {
    streamSession.updateAssistantMessage(id, content, isStreaming, toolBlocks, isAwaitingModelResponse)
}

internal fun ChatViewModel.flushStreamingDelta(id: String) = streamSession.flushStreamingDelta(id)

internal fun ChatViewModel.flushAllStreamingDeltas() = streamSession.flushAllStreamingDeltas()

/**
 * Build the ordered AgentContentPart list for this turn by walking the slice of
 * `allToolBlocks` that belongs to the current turn (from `turnStartBlockIndex` to
 * the end). Text blocks become `Text`, tool_use blocks become `ToolUse` — the
 * original stream order is preserved by the list slice order. Thinking and info
 * blocks are skipped (they're persisted via `reasoningContent` or not at all).
 */
internal fun ChatViewModel.buildTurnParts(
    allToolBlocks: List<AssistantBlock>,
    turnStartBlockIndex: Int,
    toolCallInputs: Map<String, String>,
): List<AgentContentPart> {
    if (turnStartBlockIndex >= allToolBlocks.size) return emptyList()
    val out = mutableListOf<AgentContentPart>()
    for (i in turnStartBlockIndex until allToolBlocks.size) {
        val block = allToolBlocks[i]
        when (block.kind) {
            "text" -> if (block.content.isNotEmpty()) {
                out.add(AgentContentPart.Text(block.content))
            }
            "tool_use" -> {
                val name = block.toolName
                if (name.isBlank()) continue
                val inputStr = toolCallInputs[block.id] ?: "{}"
                val inputJson = NonFiniteJson.parseOrNull(inputStr) ?: JSONObject()
                // [T-android-gemini3-thoughtsig / #179] Carry the block's
                // signature into the persisted/replayed ToolUse.
                out.add(AgentContentPart.ToolUse(block.id, name, inputJson, thoughtSignature = block.thoughtSignature))
            }
            // "thinking" / "info" → not persisted in parts
            else -> { /* skip */ }
        }
    }
    return out
}

/**
 * Persist a single agent turn: the ordered list of AgentContentParts produced
 * in this turn (text segments and tool_use blocks interleaved in the order they
 * were emitted). Mirrors iOS's per-turn `persistAgentMessage` — one DB row per
 * turn, no cross-turn accumulation, preserving `parts` array order.
 *
 * This is the right entry point for the agent loop; the legacy
 * `persistAssistantMessage(text, usage, toolBlocks, ...)` accumulated all history
 * on every call, which caused:
 *   - Duplicate tool_use rows across turns (crashed LazyColumn key uniqueness)
 *   - Orphan tool_result detection thrashing (sanitize injecting placeholders)
 *   - Lost chronological text ↔ tool_use ordering within a single turn
 */
/**
 * Serialize a turn's [AgentContentPart] list into the on-disk parts_json
 * shape (text + toolUse blocks). Shared by [persistAssistantTurn] (the
 * authoritative per-turn row write) and the live session-list preview
 * update ([T-android-session-last-message-live-tool-call]) so both produce
 * an identical payload that [ChatRepository.extractTextPreview] understands.
 */
internal fun ChatViewModel.buildAssistantPartsJson(
    parts: List<AgentContentPart>,
    toolBlockMeta: Map<String, AssistantBlock>,
): String = buildString {
    append("[")
    parts.forEachIndexed { index, part ->
        if (index > 0) append(",")
        when (part) {
            is AgentContentPart.Text -> {
                append("""{"type":"text","value":${escapeJson(part.text)}}""")
            }
            is AgentContentPart.ToolUse -> {
                // Skip tool_use with blank name — upstream bug guard.
                val name = part.name
                if (name.isBlank()) return@forEachIndexed
                val inputStr = part.input.toString()
                val meta = toolBlockMeta[part.id]
                val desc = meta?.toolTitle ?: ""
                val pageURL = meta?.browserURL ?: ""
                val imgPath = meta?.imageFilePath ?: ""
                // [P-process-card] Persist per-block timing so restored
                // sessions can rebuild it. Without these columns every
                // restored block had durationMs=0, so process cards
                // (which sum block durations) showed no duration at all
                // after a reload while live runs showed one.
                val durMs = meta?.durationMs ?: 0L
                val startMs = meta?.startTimeMs ?: 0L
                // [T-android-gemini3-thoughtsig / #179] Persist the captured
                // signature (null-literal when absent) so it survives a session
                // reload and can be replayed on the historical functionCall.
                val sigJson = part.thoughtSignature?.let { escapeJson(it) } ?: "null"
                append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(part.id)},"name":${escapeJson(name)},"input":${escapeJson(inputStr)},"description":${escapeJson(desc)},"pageURL":${escapeJson(pageURL)},"imageFilePath":${escapeJson(imgPath)},"thoughtSignature":$sigJson,"durationMs":$durMs,"startTimeMs":$startMs}}""")
            }
            else -> { /* tool_result is persisted via persistToolResultMessage */ }
        }
    }
    append("]")
}

internal suspend fun ChatViewModel.persistAssistantTurnForRun(
    run: com.openminis.app.service.ActiveRun,
    parts: List<AgentContentPart>,
    usage: LLMUsage?,
    reasoningContent: String? = null,
    toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
    assistantText: String? = null,
    uiMessageId: String? = run.assistantMessageId,
): String? = run.withPersistencePermit {
    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        persistAssistantTurn(parts, usage, reasoningContent, toolBlockMeta, streamDurationMs = run.streamRate.takeDurationMs()).also { id ->
            if (id != null) {
                withContext(Dispatchers.Main.immediate) { notePersistedUiRow(uiMessageId, id) }
                if (assistantText != null) run.markAssistantTurnPersisted(assistantText)
            }
        }
    }
}

internal suspend fun ChatViewModel.persistToolResultMessageForRun(
    run: com.openminis.app.service.ActiveRun,
    parts: List<AgentContentPart>,
    targetSessionId: String = realSessionId.ifEmpty { sessionId },
    uiMessageId: String? = run.assistantMessageId,
): String? = run.withPersistencePermit {
    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val resultIds = parts.filterIsInstance<AgentContentPart.ToolResult>().mapTo(mutableSetOf()) { it.id }
        persistToolResultMessage(parts, targetSessionId).also { dbId ->
            if (dbId != null) withContext(Dispatchers.Main.immediate) { notePersistedUiRow(uiMessageId, dbId) }
            val currentToolId = run.currentToolSnapshot()?.callId
            if (dbId != null && currentToolId != null && currentToolId in resultIds) {
                run.setCurrentTool(null, null)
            }
        }
    }
}

internal suspend fun ChatViewModel.persistAssistantTurn(
    parts: List<AgentContentPart>,
    usage: LLMUsage?,
    reasoningContent: String? = null,
    toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
    // [T-android-usage-tok-s] Streamed-text wall clock (first→last delta of
    // this persisted window) for the footer's tok/s readout; null = no text.
    streamDurationMs: Long? = null,
): String? {
    if (parts.isEmpty()) return null
    val partsJson = buildAssistantPartsJson(parts, toolBlockMeta)
    val tokenJson = usage?.let {
        val durSuffix = streamDurationMs?.let { d -> ",\"streamDurationMs\":$d" } ?: ""
        """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}$durSuffix}"""
    }
    val entity = chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
        reasoningContent = reasoningContent,
        // [T-token-attribution-snapshot] From the live request context, not
        // the session row — see currentModelSnapshot().
        modelSnapshot = currentModelSnapshot(),
    )
    return entity.id
}


/** Persist tool results as a user-role message (mirrors iOS behavior). */
internal suspend fun ChatViewModel.persistToolResultMessage(
    parts: List<AgentContentPart>,
    targetSessionId: String = realSessionId.ifEmpty { sessionId },
): String? {
    // [T-android-duplicate-toolcall-replay] Belt-and-braces: one row must
    // never carry two toolResult parts for the same toolUseId — a replay
    // that slipped past dispatch-time refusal would otherwise duplicate
    // the persisted payload and re-enter the transcript on reload.
    val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
        .distinctBy { it.id }
    if (results.isEmpty()) return null
    val partsJson = buildString {
        append("[")
        results.forEachIndexed { index, result ->
            if (index > 0) append(",")
            val snapshotText = escapeJson(result.content.lines().takeLast(30).joinToString("\n"))
            append("""{"type":"toolResult","value":{"toolUseId":${escapeJson(result.id)},"name":${escapeJson(result.name)},"output":${escapeJson(result.content)},"success":${!result.isError},"snapshot":{"type":"text","text":$snapshotText}}}""")
        }
        append("]")
    }
    val entity = chatRepository.appendMessage(targetSessionId, "user", partsJson)
    return entity.id
}
