package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.streamStallWatchdog
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Nested agent loop used by [spawn_agent]. Sub-agents do not receive the
 * parent conversation and must not spawn further sub-agents.
 */
object SubAgentRunner {

    /**
     * [T-subagent-unbounded-turns] Runaway guard, NOT a work budget. Sub-agents
     * have no per-task turn limit any more (ZCode child runtimes run until the
     * task is done or the shared token/lane budget stops them): this ceiling
     * only stops a pathological loop that keeps issuing tool calls forever
     * without either finishing or exhausting its token budget. It is a safety
     * valve, not a schedule.
     */
    const val ABSOLUTE_MAX_TURNS = 1000

    /**
     * Live rows for the detail page. The parent card still uses [onStep];
     * this stream is what makes thinking, a tool call, and execution distinct.
     */
    sealed class UiEvent {
        data class Phase(val label: String, val tool: String = "") : UiEvent()
        data class Thinking(val stepId: String, val delta: String) : UiEvent()
        data class Text(val stepId: String, val delta: String) : UiEvent()
        data class ToolStart(val id: String, val name: String) : UiEvent()
        data class ToolArgs(val id: String, val name: String, val args: String) : UiEvent()
        data class ToolRunning(val id: String, val name: String, val args: String) : UiEvent()
        data class ToolDone(
            val id: String,
            val name: String,
            val success: Boolean,
            val output: String,
        ) : UiEvent()
    }

    /** Fraction of budget consumed at which a <budget_warning> is injected. */
    private const val WARN_FRACTION = 0.80

    /** Fraction at which the agent is ordered to stop calling tools and write up. */
    private const val FORCE_FRACTION = 0.95

    /** Limit the buffered trace independently of the final report's spill threshold. */
    private const val TIMELINE_MAX_CHARS = 16_000

    /** 超出上限时按比例裁到这么多，避免每轮都裁剪一次（摊销）。 */
    private const val TIMELINE_TRIM_TO = 12_000

    internal suspend fun run(
        provider: LLMProvider,
        modelDisplayName: String,
        userPrompt: String,
        role: String?,
        skillsHint: String?,
        tools: List<AgentToolDefinition>,
        maxTokens: Int,
        // [F1 GH#343] toolCallId is the model's REAL call id, threaded to
        // executeTool so spill files (and any outcome attribution) are keyed per
        // call. Without it every parallel lane shared one filename and silently
        // overwrote its siblings' spilled output.
        executeTool: suspend (name: String, argsJson: String, toolCallId: String, scope: ToolReceiptScope?) -> ToolExecutionResult,
        onStep: suspend (turn: Int, toolName: String) -> Unit = { _, _ -> },
        onUi: suspend (UiEvent) -> Unit = {},
        kind: String = SubAgentKind.WORKER,
        writePaths: List<String> = emptyList(),
        maxTurns: Int = ABSOLUTE_MAX_TURNS,
        roleContext: Context? = null,
        /**
         * [T-subagent-report-spill] Parent session id that keys the report /
         * old-history spills into THIS session's tool-spill workspace (guest
         * path the parent's `file_read` can reach). Null (callers not yet
         * wiring it) keeps truncation honest — head+tail preserved, exact
         * omitted count reported — without a recovery path.
         */
        sessionId: String? = null,
        temperature: Double? = null,
        tokenBudget: SubAgentTokenBudget? = null,
        thinkingLevel: ThinkingLevel = ThinkingLevel.ULTRA,
        // [T-wave-e-subagent-steer, port upstream 1.16 T-sub-agents-steer]
        // User guidance queued while the lane runs; drained at every turn
        // boundary into a user-role message so the child reads it before its
        // next move. Null = no steering for this lane.
        steers: kotlinx.coroutines.channels.ReceiveChannel<String>? = null,
        // [GH#221] Per-instance user headers + OpenCode session header. A lane
        // pointed at OpenCode Go 400s on every request without the session
        // header, so the parent's header channel must reach the child too.
        extraHeaders: Map<String, String> = emptyMap(),
        // [GH#521/#188/#199] Same two-phase stream watchdog as the main agent
        // loop. [F4 GH#61] Both budgets are REQUIRED (the old `= 0L` defaults
        // turned "caller forgot" into a silent fallback and made the two lanes
        // disagree); callers pass the routing budget resolved by
        // GroupRoutingState.resolveFirstEventTimeoutMs. Zero is rejected below
        // instead of being reinterpreted as "use some default".
        firstEventTimeoutMs: Long,
        streamIdleTimeoutMs: Long,
        // [T-lanes-inherit-session-rules] The parent conversation's standing
        // rules (project/session/app, enveloped), injected into the worker's
        // system prompt so dispatched lanes obey the same session rules the
        // coordinator does. Null = the session has no rules; the worker prompt
        // is then byte-identical to before.
        sessionRules: String? = null,
        // Workspace context for final reports, independent of role lookup and history spilling.
        reportContext: Context? = roleContext,
        // [T-untrusted-tag-neutralization] Request-time projection the parent
        // applies to its own history (remote-content tool results get framework
        // markers escaped); lanes with browser/web tools have the same spoofing
        // surface, so the parent passes its projection here. Identity default =
        // lanes whose caller has no projection (none today).
        historySanitizer: (List<LLMMessage>) -> List<LLMMessage> = { it },
        receiptScope: ToolReceiptScope? = null,
    ): ToolExecutionResult {
        val briefed = SubAgentBrief.wrap(userPrompt, kind = kind, role = role, writePaths = writePaths)
        // Defensive: duplicate tool names reach providers as duplicate schemas
        // and some of them reject the payload outright. Keep first-seen order.
        val dedupedTools = SubAgentKind.dedupeByName(tools)
        // [F4 GH#61] Fail fast on an unresolved budget: silently substituting a
        // default here is exactly the "forgot to pass it" hazard. Callers resolve
        // via GroupRoutingState.resolveFirstEventTimeoutMs (always > 0) and pass
        // idle = first * 2.
        require(firstEventTimeoutMs > 0L) {
            "SubAgentRunner.run: firstEventTimeoutMs must be resolved by the caller (> 0), got $firstEventTimeoutMs"
        }
        require(streamIdleTimeoutMs > 0L) {
            "SubAgentRunner.run: streamIdleTimeoutMs must be resolved by the caller (> 0), got $streamIdleTimeoutMs"
        }
        val history = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = briefed),
        )
        // The history compactor reuses the same content-keyed file across turns.
        val sessionSpill: (nameKey: String, content: String) -> String? =
            { nameKey, content -> spillToSession(roleContext, sessionId, nameKey, content) }
        // Turn budget is whatever the coordinator assigned (auto-sized or
        // explicit), bounded only by ABSOLUTE_MAX_TURNS as a runaway guard —
        // there is no longer a low global settings clamp here.
        val turns = maxTurns.coerceIn(1, ABSOLUTE_MAX_TURNS)
        val system = workerSystemPrompt(
            modelDisplayName, role, skillsHint, kind, writePaths, turns, roleContext,
            sessionRules = sessionRules,
        )
        val report = StringBuilder()
        val timeline = StringBuilder()
        fun finishReport(footer: String = "", success: Boolean = true): ToolExecutionResult =
            ToolExecutionResult(
                composeOutput(report.toString(), timeline.toString(), footer, sessionId, reportContext),
                success,
            )
        var warned = false
        var forced = false
        var previousReceiptProof: String? = null

        try {
            var turn = 0
            while (turn < turns) {
                // Shared token budget hard stop (Codex SessionBudgetExceeded
                // semantics): once the pool is exhausted this lane stops
                // immediately and hands in what it has. A sibling that busts
                // the budget stops itself; the wave continues with the rest.
                if (tokenBudget != null && tokenBudget.exhausted) {
                    runCatching { onStep(turn, "token budget exhausted") }
                    return finishReport("(stopped: shared token budget exhausted at $turn/$turns turns)")
                }
                turn++
                runCatching { onStep(turn, "") }
                runCatching { onUi(UiEvent.Phase("思考中")) }
                // [T-wave-e-subagent-steer] Drain guidance queued since the
                // last boundary BEFORE the next model call, so the child
                // reacts to it instead of reading it after the fact. The
                // wrapper marks the provenance and tells the child it may
                // adjust course without restarting.
                if (steers != null) {
                    while (true) {
                        val steer = steers.tryReceive().getOrNull() ?: break
                        if (steer.isBlank()) continue
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = "[steer] The user sent this supplementary instruction while you were " +
                                    "working; incorporate it into the remaining work (do not restart from scratch):\n" + steer,
                            ),
                        )
                        runCatching { onStep(turn, "steer received") }
                    }
                }
                var announcedSpeak = false
                var textSeg = 0
                var thinkSeg = 0
                // Compact accumulated history before it can blow the context
                // window; the freshest tool results stay verbatim. [T-subagent-history-spill]
                // The spiller lets oversized old results keep a recovery path
                // in their stub instead of a bare head+tail excerpt.
                val sendHistory = historySanitizer(
                    SubAgentHistoryCompactor.compact(
                        history,
                        systemPrompt = system,
                        tools = dedupedTools,
                        spill = { _, content -> sessionSpill("history", content) },
                    ),
                )
                val textSb = StringBuilder()
                val toolCalls = mutableListOf<Triple<String, String, JSONObject>>()
                // [GH#61/#83/#146] The parsed `args` is `{}` when the provider
                // could not parse the arguments; keep the exact raw tail and the
                // last accumulated delta so the shared preflight can repair or
                // refuse instead of executing the substituted empty object.
                val toolRawArgs = mutableMapOf<String, String>()
                val toolDeltaTails = mutableMapOf<String, String>()
                // [T-truncated-tool-call-reject] Stream-level finish reason for
                // THIS turn; shared preflight refuses every call of a turn cut
                // by the output-token budget (same policy as the main loop).
                var turnFinishReason: String? = null
                provider.streamMessage(
                    messages = sendHistory,
                    systemPrompt = system,
                    // Output cap follows the model's own declared ceiling (the
                    // coordinator already sized maxTokens per entry); 8192 was a
                    // blanket cap that clipped long reports on capable models.
                    maxTokens = maxTokens.coerceAtLeast(256),
                    temperature = temperature,
                    tools = dedupedTools,
                    thinkingLevel = thinkingLevel,
                    extraHeaders = extraHeaders,
                ).streamStallWatchdog(
                    // [T-stream-stall-watchdog][GH#521] Phase 1 (nothing yet),
                    // phase 2 (mid-stream silence), plus the headers→first-token
                    // phase the main loop uses. A sub-agent that goes quiet now
                    // terminates at the watchdog budget instead of pinning the
                    // lane on the transport's 600s read timeout; the failure is
                    // reported through the normal lane-failure path.
                    firstTimeoutMs = firstEventTimeoutMs,
                    idleTimeoutMs = streamIdleTimeoutMs,
                    firstContentTimeoutMs = firstEventTimeoutMs,
                    firstContentReason = "sub-agent stream connected but no first token " +
                        "within ${firstEventTimeoutMs / 1000}s",
                ).collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> {
                            textSb.append(chunk.text)
                            if (!announcedSpeak) {
                                announcedSpeak = true
                                runCatching { onUi(UiEvent.Phase("回复中")) }
                            }
                            runCatching { onUi(UiEvent.Text("t$turn-text-$textSeg", chunk.text)) }
                        }
                        is LLMStreamChunk.ThinkingDelta -> {
                            runCatching { onUi(UiEvent.Phase("思考中")) }
                            runCatching { onUi(UiEvent.Thinking("t$turn-think-$thinkSeg", chunk.text)) }
                        }
                        is LLMStreamChunk.ToolUseStart -> {
                            textSeg++
                            thinkSeg++
                            announcedSpeak = false
                            runCatching { onUi(UiEvent.Phase("调用工具", chunk.name)) }
                            runCatching { onUi(UiEvent.ToolStart(chunk.id, chunk.name)) }
                        }
                        is LLMStreamChunk.ToolInputDelta -> {
                            toolDeltaTails[chunk.id] = chunk.accumulated
                            runCatching { onUi(UiEvent.ToolArgs(chunk.id, "", chunk.accumulated)) }
                        }
                        is LLMStreamChunk.ToolCallComplete -> {
                            toolCalls.add(Triple(chunk.id, chunk.name, chunk.args))
                            // [GH#61/#83/#146] Non-null only when the provider
                            // failed to parse the argument stream; keep the bytes.
                            chunk.rawArgs?.let { toolRawArgs[chunk.id] = it }
                            runCatching { onUi(UiEvent.ToolArgs(chunk.id, chunk.name, chunk.args.toString())) }
                        }
                        is LLMStreamChunk.Finished -> turnFinishReason = chunk.stopReason
                        is LLMStreamChunk.Usage -> {
                            // Shared token budget (Codex rollout_budget semantics,
                            // rewritten): usage is recorded even when it busts the
                            // budget, and the verdict is a HARD stop for this lane,
                            // not an advisory. Prefill weight <1 because cached
                            // context reads are cheap; output weight 1.
                            //
                            // `inputTokens` is already fresh-only by the parser
                            // convention (OpenAIProviderUsage.kt subtracts the
                            // cached portion; Anthropic reports it separately), so
                            // prefill is taken as-is — subtracting cacheRead here
                            // would double-subtract. Gate on any usage field, not
                            // latestContextTokens: Gemini never sets that field.
                            if (tokenBudget != null &&
                                (chunk.usage.outputTokens > 0 || chunk.usage.inputTokens > 0)
                            ) {
                                tokenBudget.recordUsage(
                                    outputTokens = chunk.usage.outputTokens,
                                    prefillTokens = chunk.usage.inputTokens,
                                )
                            }
                        }
                        else -> Unit
                    }
                }

                val text = textSb.toString().trim()
                if (text.isNotEmpty()) {
                    if (report.isNotEmpty()) report.append("\n\n")
                    report.append(text)
                }

                if (toolCalls.isEmpty()) {
                    runCatching { onStep(turn, "done") }
                    return finishReport()
                }

                val assistantParts = mutableListOf<AgentContentPart>()
                if (text.isNotEmpty()) assistantParts.add(AgentContentPart.Text(text))

                val resultParts = mutableListOf<AgentContentPart>()
                val batchScope = receiptScope?.afterLocal(previousReceiptProof)
                var batchProof: String? = null
                for ((id, name, args) in toolCalls.distinctBy { it.first }) {
                    if (SubAgentKind.isSpawnTool(name) || SubAgentKind.blocks(kind, name)) {
                        assistantParts.add(AgentContentPart.ToolUse(id, name, input = args))
                        runCatching { onStep(turn, "$name · blocked") }
                        runCatching {
                            onUi(UiEvent.ToolDone(id, name, false, "子代理不能再派生子代理，也不能使用被禁止的工具。"))
                        }
                        timeline.append("- turn $turn: $name (blocked)\n")
                        resultParts.add(
                            AgentContentPart.ToolResult(
                                id = id,
                                name = name,
                                content = "Error: sub-agents cannot spawn further sub-agents or use blocked tools. Complete the assigned work yourself.",
                                isError = true,
                            ),
                        )
                        continue
                    }
                    // [GH#61/#83/#146][GH#119] The SAME preflight layer as the
                    // main agent loop and group chat: repair the argument stream
                    // with ToolJsonRepair, refuse an irreparable one (the
                    // provider's `{}` substitute must NOT execute), refuse a
                    // truncated write (all write tools, not just file_write) and
                    // refuse every call of a length-truncated turn. Before this,
                    // a sub-agent executed `chunk.args` verbatim — the exact
                    // bypass #61 reported.
                    // 中文：子代理不再绕过参数流 preflight；与主循环共用同一实现。
                    val preflight = ToolCallPreflight.finalize(
                        toolName = name,
                        args = args,
                        rawArgumentTail = toolRawArgs[id] ?: toolDeltaTails[id],
                        advertisedTools = dedupedTools,
                        turnFinishReason = turnFinishReason,
                    )
                    if (preflight is ToolCallPreflight.Outcome.Rejected) {
                        assistantParts.add(AgentContentPart.ToolUse(id, name, input = args))
                        runCatching { onStep(turn, "$name · blocked") }
                        runCatching { onUi(UiEvent.ToolDone(id, name, false, preflight.modelMessage)) }
                        timeline.append("- turn $turn: $name (blocked: invalid or truncated arguments)\n")
                        resultParts.add(
                            AgentContentPart.ToolResult(
                                id = id,
                                name = name,
                                content = preflight.modelMessage,
                                isError = true,
                            ),
                        )
                        continue
                    }
                    val argsJson = (preflight as ToolCallPreflight.Outcome.Ready).argsStr
                    assistantParts.add(AgentContentPart.ToolUse.fromInputJson(id, name, argsJson))
                    if (SubAgentKind.isReadOnly(kind) &&
                        com.openminis.app.security.ToolAliases.canonical(name) == "memory_get" &&
                        !memoryGetIsSearch(argsJson)
                    ) {
                        val denied = "Error: explore/plan memory_get is read-only; restore is not allowed."
                        runCatching { onUi(UiEvent.ToolDone(id, name, false, denied)) }
                        resultParts.add(AgentContentPart.ToolResult(id, name, denied, isError = true))
                        continue
                    }
                    // [T-subagent-browser-readonly-actions] browser_use is
                    // allow-listed for read-only kinds, but its mutating
                    // actions are not read-only. Same shape as the shell gate.
                    if (SubAgentKind.isReadOnly(kind) && name == "browser_use") {
                        val denied = SubAgentKind.readOnlyBrowserDenial(
                            runCatching { args.optString("action") }.getOrDefault(""),
                        )
                        if (denied != null) {
                            runCatching { onUi(UiEvent.ToolDone(id, name, false, denied)) }
                            timeline.append("- turn $turn: browser_use (read-only denied)\n")
                            resultParts.add(
                                AgentContentPart.ToolResult(
                                    id = id,
                                    name = name,
                                    content = denied,
                                    isError = true,
                                ),
                            )
                            continue
                        }
                    }
                    if (SubAgentKind.isReadOnly(kind) && name in setOf("shell_execute", "shell_exec", "env_exec")) {
                        val denied = SubAgentKind.readOnlyShellDenial(
                            runCatching { org.json.JSONObject(argsJson).optString("command") }.getOrDefault(""),
                        )
                        if (denied != null) {
                            runCatching { onUi(UiEvent.ToolDone(id, name, false, denied)) }
                            timeline.append("- turn $turn: $name (read-only denied)\n")
                            resultParts.add(
                                AgentContentPart.ToolResult(id = id, name = name, content = denied, isError = true),
                            )
                            continue
                        }
                    }
                    val preview = previewToolArgs(argsJson)
                    runCatching {
                        onStep(turn, buildString {
                            append(name)
                            if (preview.isNotBlank()) append(" · ").append(preview)
                        })
                    }
                    runCatching { onUi(UiEvent.ToolRunning(id, name, argsJson)) }
                    val result = try {
                        // [F1 GH#343] Pass the call id (not a session id) — it is
                        // the per-call identity executeTool keys its spill on.
                        executeTool(name, argsJson, id, batchScope)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false)
                    }
                    if (result.receiptProof != null) batchProof = result.receiptProof
                    runCatching { onUi(UiEvent.ToolDone(id, name, result.success, result.output)) }
                    runCatching {
                        onStep(turn, buildString {
                            append(if (result.success) "ok" else "fail")
                            append(' ').append(name)
                            val snippet = result.output.replace('\n', ' ').trim().take(80)
                            if (snippet.isNotEmpty()) append(" · ").append(snippet)
                        })
                    }
                    appendTimeline(timeline, turn, name, preview, result)
                    resultParts.add(
                        AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = result.output,
                            isError = !result.success,
                            imageData = result.imageData,
                            imageMimeType = result.imageMimeType,
                            imageLinuxPath = result.imageLinuxPath,
                        ),
                    )
                }
                if (batchProof != null) previousReceiptProof = batchProof
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.ASSISTANT,
                        content = text,
                        contentParts = assistantParts,
                    ),
                )
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = resultParts,
                    ),
                )

                // [T-subagent-unbounded-turns] The run is budget-driven (token
                // pool) or completion-driven (the model stops calling tools);
                // turns only produce an advisory near the runaway guard so a
                // legitimate long task is never clipped mid-work.
                val frac = turn.toDouble() / turns
                when {
                    frac >= FORCE_FRACTION && !forced -> {
                        forced = true
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = "<budget_warning used=\"$turn/$turns\" force=\"true\">\n" +
                                    "You are at the final stretch of your turn budget. STOP calling tools NOW and return your findings so far as your final report in THIS turn — partial results are far more valuable than a perfect result you never submit. Lead with what you already confirmed, then list what is still unverified.\n" +
                                    "</budget_warning>",
                            ),
                        )
                    }
                    frac >= WARN_FRACTION && !warned -> {
                        warned = true
                        history.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = "<budget_warning used=\"$turn/$turns\">\n" +
                                    "You have used $turn of your $turns turns (~${(frac * 100).toInt()}%). Prioritize finishing: avoid further broad searches, consolidate what you have, and prepare to submit your report.\n" +
                                    "</budget_warning>",
                            ),
                        )
                    }
                }
            }
            // Loop ended at the budget (95% force message, or a stubborn agent
            // kept calling tools to the last turn). Hand back the accumulated
            // partial report instead of a bare "hit the cap" string.
            val footer = if (report.isNotBlank()) {
                "(reached the $turns-turn budget — partial report above)"
            } else {
                "(sub-agent reached the $turns-turn budget with no findings to report)"
            }
            return finishReport(footer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return finishReport("Sub-agent failed: ${e.message ?: e.javaClass.simpleName}", success = false)
        }
    }

    /** Compose the report once, then use the same spill policy as the parent tool exit. */
    internal fun composeOutput(
        report: String,
        timeline: String,
        footer: String = "",
        sessionId: String? = null,
        context: Context? = null,
    ): String {
        val body = report.trim()
        val trace = timeline.trim()
        val note = footer.trim()
        val composed = buildString {
            if (trace.isNotEmpty()) {
                append("## Trace\n")
                append(trace)
            }
            if (body.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("## Report\n")
                append(body)
            }
            if (note.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(note)
            }
            if (isEmpty()) append("(sub-agent finished with empty output)")
        }
        if (composed.length <= ToolOutputSpill.LIMIT) return composed
        return ToolOutputPolicy.apply(composed, "subagent-report", context, sessionId, "sub_agent_report")
    }

    /**
     * Card and detail show the current result, not the step history.
     * The tool result returned to the parent still uses [composeOutput].
     */
    fun cardStep(output: String): String {
        val text = output.trim()
        // Keep the host's readback link and any spill-failure notice on the detail card.
        if (text.length <= ToolOutputSpill.LIMIT && ToolOutputSpill.isOffloadPlaceholder(text)) return text
        val marker = "## Report"
        val at = text.indexOf(marker)
        if (at >= 0) {
            return text.substring(at + marker.length).trim().ifEmpty { text }
        }
        if (text.startsWith("## Trace")) return "已结束"
        return text
    }

    private fun appendTimeline(
        timeline: StringBuilder,
        turn: Int,
        name: String,
        preview: String,
        result: ToolExecutionResult,
    ) {
        // [T-subagent-memory-cap] 摊销式裁剪：只在超过硬上限时裁一次到 trim 值，
        // 避免每轮都重建字符串，也避免无界增长。
        if (timeline.length > TIMELINE_MAX_CHARS) {
            val kept = timeline.takeLast(TIMELINE_TRIM_TO)
            timeline.setLength(0)
            timeline.append("…[earlier steps elided]…\n").append(kept)
        }
        timeline.append("- turn ").append(turn).append(": ").append(name)
        if (preview.isNotBlank()) {
            timeline.append(" `").append(preview.replace('`', '\'')).append("`")
        }
        timeline.append('\n')
        val snippet = result.output.replace('\n', ' ').trim().take(160)
        if (snippet.isNotEmpty()) {
            timeline.append("  ")
            if (!result.success) timeline.append("fail: ") else timeline.append("→ ")
            timeline.append(snippet)
            timeline.append('\n')
        }
    }

    fun previewToolArgs(argsJson: String): String {
        return try {
            val o = JSONObject(argsJson)
            val raw = when {
                o.has("command") -> o.optString("command")
                o.has("path") -> o.optString("path")
                o.has("query") -> o.optString("query")
                o.has("url") -> o.optString("url")
                o.has("pattern") -> o.optString("pattern")
                else -> argsJson
            }
            raw.replace('\n', ' ').trim().take(80)
        } catch (_: Exception) {
            argsJson.replace('\n', ' ').trim().take(80)
        }
    }

    /**
     * [T-subagent-history-spill] Spill [content] into the session's tool-spill
     * workspace under a deterministic, content-hashed name: the same content
     * always lands in the SAME file, so the per-turn history-compaction
     * re-spill rewrites its own file instead of accumulating one spill per
     * turn. Guest path the model can `file_read`, or null when there is no
     * session wiring or the write failed. Same directory, naming discipline
     * and atomic-write machinery as [ToolOutputSpill].
     */
    private fun spillToSession(
        context: Context?,
        sessionId: String?,
        nameKey: String,
        content: String,
    ): String? {
        if (context == null || sessionId.isNullOrBlank()) return null
        val dir = java.io.File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill",
        )
        if (!dir.mkdirs() && !dir.isDirectory) return null
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        val file = java.io.File(dir, "$nameKey-$sha.txt")
        return if (AtomicFileWrite.write(file, content) != null) {
            "/var/minis/workspace/tool-spill/${file.name}"
        } else {
            null
        }
    }

    private fun workerSystemPrompt(
        modelDisplayName: String,
        role: String?,
        skillsHint: String?,
        kind: String,
        writePaths: List<String>,
        turns: Int,
        roleContext: Context? = null,
        sessionRules: String? = null,
    ): String {
        val catalog = roleContext?.let { CollabRoles.byName(it, role) } ?: CollabRoles.byName(role)
        val roleLine = when {
            catalog != null -> "Assigned role: ${catalog.name}.\n\n${catalog.prompt}\n\n"
            !role.isNullOrBlank() -> "Assigned role: ${role.trim()}.\n"
            else -> ""
        }
        val skillsLine = skillsHint?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "Read these skills first (file_read `/var/minis/skills/<id>/SKILL.md`): $it\n"
        } ?: ""
        // [T-lanes-inherit-session-rules] The parent session's standing rules
        // ride the worker prompt right under the identity line — dispatched
        // lanes obey the session rules the coordinator obeys.
        val rulesLine = inheritedRulesLine(sessionRules) ?: ""
        val kindLine = "Kind: $kind.\n"
        // [T-write-guard-fail-closed] Same three states as the brief: usable
        // prefixes only get listed, the poison state is spelled out as a write
        // lock-out instead of pasting WritePathGuard.INVALID into the system prompt.
        val writePrefixes = WritePathGuard.usablePrefixes(writePaths)
        val writeLine = when {
            writePrefixes.isNotEmpty() ->
                "You may only file_write/file_edit under: ${writePrefixes.joinToString()}. " +
                    "Keep shell writes in those prefixes; MINIS_WRITE_PATHS is exported.\n"
            WritePathGuard.isPoisoned(writePaths) ->
                "Your write_paths contained no usable absolute prefix, so file_write/file_edit are " +
                    "DENIED for this worker and MINIS_WRITE_PATHS is not exported. Do not retry " +
                    "writes — report what you could do without them.\n"
            else -> ""
        }
        val toolLine = if (SubAgentKind.isReadOnly(kind)) {
            "- Read-only: file_read, grep_source, and shell_execute for inspection only (date, uname, cat, ls). Do not write files or redirect output into a file."
        } else {
            "- Use tools immediately. Prefer file_read / grep_source / file_edit / file_write / shell_execute."
        }
        return """You are a sub-agent ($kind), not the session coordinator. Model: $modelDisplayName.
${rulesLine}${roleLine}${skillsLine}${kindLine}${writeLine}You cannot see the parent conversation. The user prompt is a self-contained brief with ## Task / ## Expected result / ## Constraints / ## Workflow / ## Collaboration.

Turn budget: you have $turns turns. A <budget_warning> will be injected as you approach the limit — treat it as a hard signal to wrap up. Handing in a partial report is far better than running dry mid-task; if forced to stop, lead with confirmed findings, then list unverified leftovers.

Rules:
- Complete ONLY the assigned slice. Do not rewrite unrelated files.
- Do not spawn further sub-agents. spawn_agent / run_subagent are not available and will error if you try.
- Do not delegate reading or summarizing a skill's SKILL.md to another agent — that discipline does not exist here; read it yourself if the brief points you at one.
- Searching/reading source: prefer grep_source (one call = matching lines ± context) over paging file_read through a big file. Old tool outputs are auto-trimmed from your context, so re-read a file if you need it back.
$toolLine
- Follow the brief's Workflow, then return a concise report: what changed, files touched, leftover risks, and whether Expected result passed.
- If you cannot meet the acceptance criteria, say so explicitly and list what failed.
"""
    }
}

/**
 * [T-lanes-inherit-session-rules] Pure framing for the inherited standing-rules
 * block so tests can pin the lane contract: null / blank → no block (the worker
 * prompt stays byte-identical to the pre-inheritance shape); text → the rules
 * ride under a header that names the parent-conversation authority.
 */
internal fun inheritedRulesLine(sessionRules: String?): String? =
    sessionRules?.trim()?.takeIf { it.isNotEmpty() }?.let {
        "Standing rules inherited from the parent conversation (they bind this lane):\n$it\n\n"
    }

