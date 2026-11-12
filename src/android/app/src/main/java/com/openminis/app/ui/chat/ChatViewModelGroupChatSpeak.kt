package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.data.GroupRoutingState
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.provider.streamStallWatchdog
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.DiscussionGraph
import com.openminis.app.tools.GroupChat
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.ToolCallPreflight
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID



/** 群聊发言人主循环 + 工具状态卡片（拆分自 ChatViewModelGroupChatExt，架构行数上限）。 */

internal suspend fun ChatViewModel.speakModel(
    provider: LLMProvider,
    system: String,
    user: String,
    tools: List<com.openminis.app.data.model.AgentToolDefinition>,
    toolGate: Mutex?,
    thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
    stopWhenClosing: Boolean,
    maxTokens: Int = 4096,
    firstTokenTimeoutMs: Long? = null,
    // [GH#221] Per-instance user headers + OpenCode session header.
    extraHeaders: Map<String, String> = emptyMap(),
    onStatus: suspend (AssistantBlock) -> Unit,
): String {
    val history = mutableListOf(LLMMessage(role = LLMMessage.Role.USER, content = user))
    val report = StringBuilder()
    var lastThinking = ""
    // [group-chat-empty-fix] 轮次上限 3→6，另加 3 分钟总时长兜底：以前工具密集的发言跑满 3 轮只剩思考兑底；思考档不降级，靠预算记账给正文留位置。
    val deadline = android.os.SystemClock.elapsedRealtime() + 180_000L
    var round = 0
    while (round < 6 && android.os.SystemClock.elapsedRealtime() < deadline) {
        round++
        if (stopWhenClosing && groupChatCloseRequested) {
            return GroupChat.recoverUtterance(report.toString(), lastThinking)
        }
        val textSb = StringBuilder()
        val thinking = StringBuilder()
        val calls = mutableListOf<Triple<String, String, JSONObject>>()
        // [GH#61/#83/#146] Keep the unparsed argument tail per call so the shared
        // preflight can repair it (the provider substitutes `{}` when parsing
        // failed; executing that is the silent-corruption bug #61 reported).
        val callRawArgs = mutableMapOf<String, String>()
        val callDeltaTails = mutableMapOf<String, String>()
        // [T-truncated-tool-call-reject][GH#119] Finish reason for THIS round;
        // shared preflight refuses every call of a length-truncated turn.
        var roundFinishReason: String? = null
        var lastStatusAt = 0L
        // [T-stream-stall-watchdog] Same two-phase bound as the main agent loop: a speaker
        // whose stream goes quiet mid-sentence must not wedge the round for a read timeout.
        // [T-routing-strategy-x] Group-level firstTokenTimeoutMs override, resolved once in speakVisible and passed down.
        val firstEventTimeoutMs = firstTokenTimeoutMs ?: if (thinkingLevel.isEnabled) 90_000L else 45_000L
        provider.streamMessage(
            messages = history,
            systemPrompt = system,
            maxTokens = maxTokens,
            temperature = null,
            tools = tools,
            thinkingLevel = thinkingLevel,
            extraHeaders = extraHeaders,
        ).streamStallWatchdog(
            firstEventTimeoutMs,
            idleTimeoutMs = firstEventTimeoutMs * 2,
            // [GH#521][#188/#199] Headers can arrive and then the speaker goes
            // silent before its first token; without this phase the only bound
            // was the 2× idle window and a stalled relay looked healthy.
            firstContentTimeoutMs = firstEventTimeoutMs,
            firstContentReason = "group-chat stream connected but no first token " +
                "within ${firstEventTimeoutMs / 1000}s",
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Started -> onStatus(
                    AssistantBlock(
                        id = "group-status",
                        kind = "info",
                        content = context.getString(com.openminis.app.R.string.group_chat_thinking),
                    ),
                )
                is LLMStreamChunk.ThinkingDelta -> {
                    thinking.append(chunk.text)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastStatusAt >= 120L) {
                        lastStatusAt = now
                        onStatus(thinkingStatus(thinking))
                    }
                }
                is LLMStreamChunk.Text -> textSb.append(chunk.text)
                is LLMStreamChunk.ToolUseStart -> onStatus(toolStatus(chunk.id, chunk.name, ToolBlockStatus.RUNNING, ""))
                is LLMStreamChunk.ToolInputDelta -> callDeltaTails[chunk.id] = chunk.accumulated
                is LLMStreamChunk.ToolCallComplete -> {
                    calls += Triple(chunk.id, chunk.name, chunk.args)
                    chunk.rawArgs?.let { callRawArgs[chunk.id] = it }
                    onStatus(toolStatus(chunk.id, chunk.name, ToolBlockStatus.RUNNING, chunk.args.toString()))
                }
                is LLMStreamChunk.Finished -> roundFinishReason = chunk.stopReason
                else -> Unit
            }
        }
        if (thinking.isNotEmpty()) onStatus(thinkingStatus(thinking))
        lastThinking = thinking.toString()
        val text = textSb.toString().trim()
        // [group-chat-empty-fix] 每轮正文都记账：带工具调用的轮次同样有话要说，
        // 以前只收“干净轮”的正文，工具密集的发言整段丢失。
        if (text.isNotEmpty()) {
            if (report.isNotEmpty()) report.append("\n\n")
            report.append(text)
        }
        if (calls.isEmpty() || tools.isEmpty()) {
            return GroupChat.recoverUtterance(report.toString(), lastThinking)
        }
        val assistantParts = mutableListOf<AgentContentPart>()
        if (text.isNotEmpty()) assistantParts += AgentContentPart.Text(text)
        calls.forEach { (callId, name, args) ->
            assistantParts += AgentContentPart.ToolUse(callId, name, input = args)
        }
        history += LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, contentParts = assistantParts)
        val results = mutableListOf<AgentContentPart>()
        for ((callId, name, args) in calls) {
            onStatus(toolStatus(callId, name, ToolBlockStatus.RUNNING, args.toString()))
            // [GH#61/#83/#146][GH#119] Same shared preflight as the main loop and
            // sub-agents. Group chat used to `executeTool(args.toString())`
            // unconditionally, so an unparseable/truncated argument stream became
            // an invisible `{}` call here while the main loop refused it.
            // 中文：群聊/主管通道与主循环共用参数流 preflight，不再直接执行原始 args。
            val preflight = ToolCallPreflight.finalize(
                toolName = name,
                args = args,
                rawArgumentTail = callRawArgs[callId] ?: callDeltaTails[callId],
                advertisedTools = tools,
                turnFinishReason = roundFinishReason,
            )
            if (preflight is ToolCallPreflight.Outcome.Rejected) {
                // [GH#61/#83/#146] Mark the call FAILED on the speaker bubble and
                // return the model-visible refusal; nothing executes.
                onStatus(toolStatus(callId, name, ToolBlockStatus.FAILED, preflight.uiMessage))
                results += AgentContentPart.ToolResult(
                    callId, name, preflight.modelMessage, isError = true,
                )
                continue
            }
            val argsJson = (preflight as ToolCallPreflight.Outcome.Ready).argsStr
            onStatus(toolStatus(callId, name, ToolBlockStatus.RUNNING, argsJson))
            val denied = DiscussionGraph.denyExecution(name, argsJson)
            val result = if (denied != null) {
                ToolExecutionResult(denied, false)
            } else if (toolGate != null) {
                // [F1 GH#343] Pass the real call id (was ""), so parallel-group
                // speakers no longer overwrite one shared `tool.txt` spill file.
                toolGate.withLock { executeTool(name, argsJson, callId) }
            } else {
                executeTool(name, argsJson, callId)
            }
            results += AgentContentPart.ToolResult(callId, name, result.output, isError = !result.success)
        }
        history += LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = results)
    }
    return GroupChat.recoverUtterance(report.toString(), lastThinking)
}


internal fun toolStatus(
    id: String,
    name: String,
    status: ToolBlockStatus,
    args: String,
) = AssistantBlock(
    id = id.ifBlank { "group-tool" },
    kind = "tool_use",
    toolName = name,
    toolTitle = name,
    toolArgs = args.take(240),
    toolStatus = status,
    content = name,
)

