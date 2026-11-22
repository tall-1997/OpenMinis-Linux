package com.openminis.app.ui.chat

import com.openminis.app.data.AssistantTextPartJson
import com.openminis.app.data.ToolLimitPrefs
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONArray
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
    streamSession.updateAssistantMessage(id, replyContent(content, toolBlocks), isStreaming, toolBlocks, isAwaitingModelResponse)
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
): List<AgentContentPart> = turnPartsFromBlocks(allToolBlocks, turnStartBlockIndex, toolCallInputs)

/**
 * Pure slice builder behind [buildTurnParts]. Extracted so the slice contract
 * is JVM-testable — the extension needs a ChatViewModel receiver, which no JVM
 * test can construct.
 *
 * [T-compact-retry-rebase] The compact-and-retry seal+swap clears allToolBlocks
 * and rebases the loop's lower bound to 0; this function is where a STALE bound
 * fails. `turnStartBlockIndex >= allToolBlocks.size` (the stale pre-clear size
 * against the rewound list) returns an EMPTY list — the guard is silent by
 * design for the genuine empty-turn case, which is exactly why a stale bound
 * used to drop the whole retried turn at persist with no error anywhere.
 */
internal fun turnPartsFromBlocks(
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
                out.add(AgentContentPart.Text(block.content, block.isProcessText))
            }
            "tool_use" -> {
                val name = block.toolName
                if (name.isBlank()) continue
                // allToolInputs carries preflight's final JSON, not the streamed draft.
                out.add(AgentContentPart.ToolUse.fromInputJson(
                    block.id, name, toolCallInputs[block.id] ?: "{}", block.thoughtSignature,
                ))
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
internal fun buildAssistantPartsJson(
    parts: List<AgentContentPart>,
    toolBlockMeta: Map<String, AssistantBlock>,
): String = JSONArray().apply {
    for (part in parts) {
        when (part) {
            is AgentContentPart.Text -> put(AssistantTextPartJson.encode(part.text, part.isProcessText))
            is AgentContentPart.ToolUse -> {
                if (part.name.isBlank()) continue
                val meta = toolBlockMeta[part.id]
                val value = JSONObject().apply {
                    put("toolUseId", part.id)
                    put("name", part.name)
                    put("input", part.inputJson)
                    put("description", meta?.toolTitle.orEmpty())
                    put("pageURL", meta?.browserURL.orEmpty())
                    put("imageFilePath", meta?.imageFilePath.orEmpty())
                    put("thoughtSignature", part.thoughtSignature ?: JSONObject.NULL)
                    put("durationMs", meta?.durationMs ?: 0L)
                    put("startTimeMs", meta?.startTimeMs ?: 0L)
                }
                put(JSONObject().put("type", "toolUse").put("value", value))
            }
            else -> Unit // Results are persisted via persistToolResultMessage.
        }
    }
}.toString()

/** Persist tool results as a user-role message (mirrors iOS behavior). */
internal suspend fun ChatViewModel.persistToolResultMessage(
    parts: List<AgentContentPart>,
    targetSessionId: String = realSessionId.ifEmpty { sessionId },
): String? {
    val partsJson = buildToolResultPartsJson(parts) ?: return null
    return chatRepository.appendMessage(targetSessionId, "user", partsJson).id
}

internal fun buildToolResultPartsJson(parts: List<AgentContentPart>): String? {
    // [T-android-duplicate-toolcall-replay] Keep at most one result per wire ID.
    val results = parts.filterIsInstance<AgentContentPart.ToolResult>().distinctBy { it.id }
    if (results.isEmpty()) return null
    return JSONArray().apply {
        for (result in results) {
            val value = JSONObject().apply {
                put("toolUseId", result.id)
                put("name", result.name)
                put("output", result.content)
                put("success", !result.isError)
                result.imageLinuxPath?.let { put("imageLinuxPath", it) }
                result.receiptProof?.let { put("receiptProof", it) }
                put("snapshot", JSONObject().put("type", "text")
                    .put("text", result.content.lines().takeLast(30).joinToString("\n")))
            }
            put(JSONObject().put("type", "toolResult").put("value", value))
        }
    }.toString()
}
