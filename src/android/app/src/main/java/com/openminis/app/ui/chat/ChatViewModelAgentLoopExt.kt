package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.R
import com.openminis.app.agent.StreamDropReminder
import com.openminis.app.data.ContextUsageAttempt
import com.openminis.app.data.GroupRoutingState
import com.openminis.app.data.ToolLimitPrefs
import com.openminis.app.data.reportedRequestInputTokens
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AssistantTextPhase
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.OutboundPrivacyChanged
import com.openminis.app.provider.streamStallWatchdog
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.goal.recordCommittedTurnUsage
import com.openminis.app.tools.ToolReceiptScope
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject

/**
 * [T-truncated-tool-call-reject] finish_reason=length / max_tokens 意味着
 * 输出预算耗尽，流尾的工具调用参数极可能被截断成半截 JSON。这类调用必须
 * 拒执行并回写结构化错误，而不是拿半截参数去写文件/跑 shell。
 * 镜像 Eta AGENT_RUNTIME.md 的截断即拒执行。
 *
 * [GH#119] 判定与拒绝逻辑已收敛进共享
 * [com.openminis.app.tools.ToolCallPreflight]：主循环、子代理、群聊三条流
 * 都按同一个 reason 集合拒绝，不再只有主循环受保护。
 */

internal suspend fun ChatViewModel.runAgentLoopBody(
    provider: LLMProvider,
    systemPrompt: MemoryDeliveryPrompt?,
    skipInitialCompaction: Boolean = false,
    fallbackProviders: List<ChatViewModel.FallbackCandidate> = emptyList(),
    fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
    goalExecutionRun: Boolean = false,
    /** [T-android-instant-thinking] send 路径已预插占位气泡，复用其 id。 */
    overrideAssistantId: String? = null,
    journal: ChatRunJournal,
    loopResumeEpoch: Long,
    generationRunId: String,
    uiBatcher: UiUpdateBatcher,
) {
    val loopSessionId = journal.sessionId
    val activeRun = com.openminis.app.service.ActiveRunContext.current()
    fun ownsLoop() = ownsTranscript(loopSessionId, loopResumeEpoch, activeRun)
    if (!ownsLoop()) return
    // [T-android-model-switch-midrun-adopt] Epoch this run last acted on (var:
    // resolveModelSwitch reassigns it on mid-run adoption, so the fallback writeback below
    // may still persist the ADOPTED model).
    var loopModelEpoch = snapshotModelSelectionEpoch()
    AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${agentHistory.size}")
    // [T-android-mem-probe-trust] Log the send-path context shape once per run. Extracted to
    // ChatViewModelCtxShape.kt (architecture ceiling; the counting pass is pure and JVM-tested
    // there). [T-thinking-dual-ledger] The line carries the requested thinking tier; the
    // response side lands in each turn's token_usage JSON via [TurnThinkingUsage].
    logContextShape(agentHistory, _thinkingLevel.value)
    // [T-android-queued-message-interrupt-on-toolclose] `assistantId` is
    // normally a single message id for the whole agent loop (iOS-parity:
    var assistantId = overrideAssistantId ?: "assistant_${System.currentTimeMillis()}"
    val allToolBlocks = mutableListOf<AssistantBlock>()
    // Per-tool ring of the most recent `accumulated` JSON snapshots from
    // `LLMStreamChunk.ToolInputDelta`, capped at TOOL_INPUT_CHUNK_RING_MAX per tool id
    // so memory stays bounded on long streams. The preflight validator below drains it
    // on a blocked call to reconstruct how the model assembled (or failed to assemble)
    // the args.
    val toolInputChunkRings: MutableMap<String, MutableList<String>> = mutableMapOf()
    // [GH#61/#83/#146] 本轮无法解析的参数尾巴（keyed by toolCall id，每轮清空）。
    val toolCallRawArgs = mutableMapOf<String, String>()
    var accumulatedText = ""
    var mayBypassInitialCompaction = skipInitialCompaction
    var receiptUserTurnId = agentHistory.lastOrNull(::receiptUserRow)?.dbMessageId
    activeRun?.associateSession(loopSessionId)
    activeRun?.attachAssistantMessage(assistantId)
    // T94 fix 2: throttle text-delta UI updates to ~20fps (50ms).
    var lastUiUpdateMs = 0L
    var lastFlushedLen = 0
    // [T-thinking-delta-ui-gate] Reasoning models stream thinking deltas at 50-200/s;
    // the thinking branch re-publishes the full block list at human cadence (100ms)
    // instead of delta cadence — see the ThinkingDelta arm.
    var lastThinkingUiMs = 0L
    // T256 tier 2: per-tool-kind input-delta gates. file_write/file_edit pills churn
    // JSON the user can't read anyway — 1Hz update is plenty; other tools get 5Hz so
    // command/url previews stay legible.
    var lastFileToolInputMs = 0L
    var lastOtherToolInputMs = 0L
    // [T-android-throttle-ladder-converge] 150ms first tier for this surface.
    // [T-android-model-switch-midrun-adopt] Fallback state is re-read per request.
    var currentProvider = provider
    var remainingFallbacks = fallbackProviders.toMutableList()
    val fallbackReasons = mutableListOf<String>()
    // [port-upstream-1.16][T-b13-empty-fallback] Members that failed with an
    // error this run — the empty-turn walk never picks them again.
    val triedEntryIds = mutableSetOf<String>()

    // Accumulate tool inputs across all turns (so persist includes all, not just current turn)
    val allToolInputs = mutableMapOf<String, String>()
    val goalManager = com.openminis.app.goal.GoalManager(chatRepository)
    val goalSessionId = loopSessionId
    var goalMountedForCurrentRequest = false
    var goalContinuations = 0
    // [T-subagent-background] Bounded nudges for uncollected detached waves.
    var subAgentCollectNudges = 0
    val announcedFinishedBatches = mutableSetOf<String>()  // [T-subagent-completion-timeliness]
    // [T-plan-close-nudge] Same bounded shape, for an unreconciled plan board.
    var planCloseNudges = 0
    var goalRunRequested = goalExecutionRun
    // [T-gemini-signalless-eof] One bounded continuation for a text stream without a terminal event.
    var usedStreamDropContinuation = false
    var streamReplyPrefix: StreamReplyPrefix? = null
    // [T-retry-projection-reuse] See DispatchProjectionCache.
    val projectionCache = DispatchProjectionCache()

    // Add placeholder assistant message (once). Marked awaiting so the "Minis is
    // thinking" indicator shows during the initial request gap before the first
    // stream chunk arrives. Mirrors iOS isAwaitingModelResponse. T300: snapshot the
    // user's current thinking level at message creation so the renderer can hide
    // Deep Thinking blocks for turns the user explicitly asked not to surface, even
    // when a forced-reasoning model still streams reasoning_content.
    // turnThinkingLevel 保留在块外：in-loop compact 的 freshAssistantId 分支同样引用它。
    val turnThinkingLevel = _thinkingLevel.value
    // [T-android-instant-thinking] send 路径已把占位气泡插入 _messages，
    // 此处跳过，避免同一回复出现两条气泡。
    if (overrideAssistantId == null) {
        withContext(Dispatchers.Main) {
            // The model is producing again: the previous turn's
            // retry/interrupt/error sticker is now stale. Sweep BEFORE the
            // new placeholder is appended — clearInlineError() only targets
            // the TAIL row, so a sticker stranded on an earlier one (a
            // dropped run's wind-down racing this entry) would survive every
            // tail-scoped clear and render beside live output. The rule is
            // "the hint bubble disappears the moment the model starts
            // running", not "once the first token lands".
            sweepStaleInlineErrors()
            _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
                id = assistantId, role = "assistant", content = "", isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = turnThinkingLevel,
            ))
        }
    }

    // Tracks whether the loop exited via `break` (any reason) or fell off the range
    // end. Set false by every break path that ISN'T "past MAX_AGENT_TURNS" — without
    // it the post-loop tail slapped a fake turn-limit error everywhere.
    var loopExitedNormally = false
    var completedReply: String? = null
    // [T-android-auto-compact-inloop] In-loop guard compactions in THIS runAgentLoop —
    // bounds compact-thrash: once the cap is hit, a still-over-threshold history stops
    // the turn. Mirrors iOS maxInLoopCompactions.
    var inLoopCompactions = 0
    // [T-compact-on-context-overflow] How many times THIS runAgentLoop compacted in
    // response to the provider's own context verdict (ContextLengthExceeded), not the
    // token estimate. Capped at 1: if the provider still refuses after a compaction,
    // fallback/terminal-error handling takes over.
    var contextOverflowCompactions = 0
    // [T-android-empty-after-toolresult-reminder] One-shot guard for the
    // "<system-reminder> + retry one round" recovery after an empty response following
    // a tool result — at most once per runAgentLoop (it can never loop); a still-empty
    // reminder round surfaces a real error. iOS parity:
    // AIChatViewModel.didInjectEmptyToolReminderThisRun.
    var didInjectEmptyToolReminder = false
    // [port-upstream-1.16][T-android-dsml-leak] Resends spent on a leaked DSML
    // tool call; [T-b13-empty-fallback] empty-streak entries never revisited.
    var dsmlLeakRetries = 0
    val emptyStreakEntryIds = mutableSetOf<String>()
    // [T-android-readaloud-stop-stale] One-shot per REPLY (not per turn): the first
    // text delta stops any Read Aloud still playing from the previous reply — scoped
    // outside the turn loop so a multi-turn reply cannot re-fire it mid-sentence.
    var didStopStaleReadAloud = false
    fun stopStaleReadAloud() {
        if (didStopStaleReadAloud) return
        didStopStaleReadAloud = true
        _stopStaleReadAloud.tryEmit(Unit)
    }
    val toolExecutionLedger = ToolExecutionLedger()  // [T-tool-execution-ledger] one per run
    val repeatTracker = ToolRepeatTracker()  // [T-frozen-repeat-hint] hint-only
    // [T-main-session-turn-cap] The runaway guard is user-tunable (Settings → Tool
    // Limits); 200 is only the shipped default. Read once per run so a mid-run settings
    // change can't shift the range end under an in-flight iteration.
    val maxTurns = ToolLimitPrefs.mainSessionMaxTurns()
    // [T-android-queued-message-interrupt-*] Shared by both mid-loop injection sites:
    // switch loop-scope state to the injected bubble.
    fun swapLoopStateToInjectedBubble(newAssistantId: String) {
        goalRunRequested = false
        usedStreamDropContinuation = false
        streamReplyPrefix = null
        receiptUserTurnId = agentHistory.lastOrNull(::receiptUserRow)?.dbMessageId
        mayBypassInitialCompaction = false
        assistantId = newAssistantId
        activeRun?.attachAssistantMessage(newAssistantId)
        accumulatedText = ""
        allToolBlocks.clear()
        allToolInputs.clear()
        toolInputChunkRings.clear()
        toolCallRawArgs.clear()
        _canResume.value = false
    }
    // RUN-scoped: rebuilt on model adoption and on queued-turn injection.
    var activeSystemPrompt = systemPrompt
    agentTurns@ for (turn in 0 until maxTurns) {
        currentCoroutineContext().ensureActive()
        if (!ownsLoop()) return
        // Mark where this turn's blocks start in allToolBlocks so we persist only the NEW
        // parts from this turn. Matches iOS's per-turn RawMessage persistence.
        var turnStartedAtMs = System.currentTimeMillis()
        var turnStartBlockIndex = allToolBlocks.size
        val turnOutput = AgentTurnText(allToolBlocks, turn)
        val turnTextSb = turnOutput.raw
        fun messageTextSnapshot(): String =
            if (turnTextSb.isEmpty()) accumulatedText else accumulatedText + turnTextSb.toString()
        var stallEchoStripper: com.openminis.app.provider.StallEchoStripper? = null
        var echoSource: LLMStreamChunk.Text? = null
        fun appendTurnText(text: String, chunk: LLMStreamChunk.Text? = null) = turnOutput.append(
            text, chunk?.itemId, chunk?.phase, currentProvider.streamTextIsMonolithic, turnStartBlockIndex,
        )
        fun flushStallEcho() {
            stallEchoStripper?.flush()?.let { appendTurnText(it, echoSource) }
            stallEchoStripper = null
            echoSource = null
        }
        fun recordInterruptedText() = activeRun?.updateAssistantText(assistantId, turnOutput.interruptedText())
        suspend fun publishTurnText() {
            turnOutput.materialize()
            lastFlushedLen = turnTextSb.length
            lastUiUpdateMs = System.currentTimeMillis()
            val text = messageTextSnapshot()
            recordInterruptedText()
            withContext(Dispatchers.Main) {
                if (ownsLoop()) updateAssistantMessage(assistantId, text, true, allToolBlocks)
            }
        }
        val turnThinking = StringBuilder()
        // [T-thinking-delta-ui-gate] The gated thinking publish leaves the block
        // content up to 100ms behind the StringBuilder; settles and the stream-end
        // flush materialise the FULL thinking first — buildTurnParts reads block content.
        fun materializeThinkingBlock() {
            val idx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
            if (idx >= 0) allToolBlocks[idx] = allToolBlocks[idx].copy(content = turnThinking.toString())
        }
        // Opaque reasoning_content blob from the provider's ReasoningContent chunk. When
        // set (including empty string) it takes precedence over turnThinking concatenation
        // so the exact server-emitted value round-trips on the next request — DeepSeek V4
        // emits "" legitimately and fabricated text would be in-context-learned.
        var turnReasoningBlob: String? = null
        // T321: capture finish_reason from LLMStreamChunk.Finished so we can
        // log it at turn-end alongside the empty-turn warning.
        var turnFinishReason: String? = null
        // [port-upstream-1.16][FIX-261018-12] Anthropic refusal stop_details.
        var turnRefusalCategory: String? = null
        var turnRefusalExplanation: String? = null
        var lastUsage: LLMUsage? = null
        var dispatched: ProjectedChatRequest? = null
        var usageAttempt: ContextUsageAttempt? = null
        // [GH#61/#83/#146] New turn → previous turn's unparseable tails are
        // stale; a provider id reused across turns must not inherit one.
        toolCallRawArgs.clear()
        val toolCalls = mutableListOf<Triple<String, String, JSONObject>>() // id, name, args
        // [T-android-gemini3-thoughtsig / #179] toolCallId -> Gemini 3.x
        // thoughtSignature for this turn's calls (null for other providers).
        val toolCallSignatures = mutableMapOf<String, String>()

        // [T-android-duplicate-toolcall-replay] Idempotence for re-emitted ToolCallComplete
        // events: a gateway/SSE replay can deliver the same completed call (same raw id,
        // name, args) twice within one stream attempt; the [T-dedupe-toolcallid] renamer
        // turns the second
        val toolReplayGuard = com.openminis.app.provider.ToolReplayGuard()
        val replayRenamedIds = mutableSetOf<String>()

        // [T-dedupe-toolcallid 03fbcbfd] See ToolCallIdDedupe.
        val toolCallIdDedupe = ToolCallIdDedupe()

        // Stream the response — with auto-retry on transient errors, then fallback.
        // callbackFlow wraps throws into CancellationException(cause=LLMError),
        // so we catch at collect level and unwrap.
        var collectDone = false
        var retryAttempt = 0  // per-provider; reset when falling back to the next member
        // [T-stall-resume] Mid-stream stalls (stream alive, then quiet past the idle
        // budget) carry the already-streamed text into the retry so the model continues
        // instead of regenerating from scratch. Set in the retry catch when the
        // TransientError carries stalledAfterFirstEvent, consumed by the retried
        // requestHistory, cleared once a stream attempt succeeds (a normal `continue`
        // never reaches the rollback that sets it).
        var stallResumeContext: String? = null
        // [T-android-queued-message-interrupt-thinking] Set by the dedicated catch below;
        // read after the stream loop to inject as a new turn.
        var queuedPromptInterrupted = false
        // [T-android-queued-message-interrupt-thinking] iOS d14174d3 parity:
        // A queued prompt must not wait out the stream — cut (both branches call this).
        fun cutForQueuedPrompt(phase: String) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "📨[QueueInterrupt] turn=$turn $phase cut — answering queued prompt directly")
            throw QueuedPromptInterrupt()
        }
        // [T-compact-on-context-overflow][T-context-overflow-usage] Cap-guarded
        // compaction shared by all overflow gates; true = compacted once under the cap.
        suspend fun runOverflowCompaction(details: String): Boolean {
            if (contextOverflowCompactions >= 1) return false
            contextOverflowCompactions += 1
            Log.w(ChatViewModel.TAG, "📦 Context overflow — compacting once and retrying (attempt $contextOverflowCompactions): $details")
            withContext(Dispatchers.Main) {
                setTransientInlineError(
                    context.getString(
                        com.openminis.app.R.string.vm_context_filling_compacting,
                        dispatched?.estimate?.tokens ?: 0,
                        dispatched?.window ?: 0,
                    ),
                )
            }
            val compacted = awaitCompaction()
            withContext(Dispatchers.Main) { clearInlineError() }
            return compacted
        }


        // Re-enter the dispatch loop without spending a model turn on compaction.
        suspend fun rebaseAfterCompaction() {
            lastUsage = null
            usageAttempt = null
            dispatched = null
            mayBypassInitialCompaction = false
            val freshAssistantId = "assistant_${System.currentTimeMillis()}_$turn"
            uiBatcher.seal(assistantId)
            if (!ownsLoop()) return
            sealCompactedBubbleAndSwap(assistantId, messageTextSnapshot(), allToolBlocks, freshAssistantId, turnThinkingLevel)
            assistantId = freshAssistantId
            activeRun?.attachAssistantMessage(freshAssistantId)
            accumulatedText = ""
            allToolBlocks.clear()
            // [T-compact-retry-rebase] The seal+swap rewound the list to EMPTY but the
            // caller's `continue` re-enters the WHILE loop, not the turn prologue — rebase
            // the slice lower bound with the clear (a stale pre-clear size made buildTurnParts
            // slice out-of-range, whose empty-slice guard dropped the whole retried turn at
            // persist) and restart the goal-usage clock the retried attempt owns.
            turnStartBlockIndex = 0
            turnStartedAtMs = System.currentTimeMillis()
            allToolInputs.clear()
            toolInputChunkRings.clear()
            toolCallRawArgs.clear()
            streamReplyPrefix = null
            turnOutput.reset()
            echoSource = null
            turnThinking.clear()
            // [T-reasoning-blob-per-attempt] The compact-retried attempt owns the reasoning
            // field; the overflowing attempt's blob must not survive the rebase.
            turnReasoningBlob = null
            toolCalls.clear()
            toolCallSignatures.clear()
            toolReplayGuard.reset()
            replayRenamedIds.clear()
            lastUiUpdateMs = 0L
            lastFlushedLen = 0
            stallResumeContext = null
        }

        suspend fun compactAndRetryForOverflow(details: String): Boolean {
            if (!runOverflowCompaction(details)) return false
            rebaseAfterCompaction()
            return true
        }

        // pi case ② (natural stop + over-window usage): compact ONLY — the answer already
        // streamed and must complete normally. No retry, no turn-state reset, no usage
        // drop (the turn's own usage still gets persisted by the turn-end path).
        suspend fun compactForSilentOverflow(details: String): Boolean =
            runOverflowCompaction(details)
        // Keep event handling in its own coroutine state machine.
        suspend fun consumeStreamChunk(chunk: LLMStreamChunk) {
            // [T-stream-trace-live] 运行时轨迹录制（日志页开关控制，默认关）。
            com.openminis.app.provider.StreamTraceRecorder.record(chunk)
            when (chunk) {
                is LLMStreamChunk.ThinkingDelta -> {
                    turnThinking.append(chunk.text)
                    // Update thinking block in UI
                    val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                    if (thinkIdx < 0) {
                        allToolBlocks.add(AssistantBlock(
                            id = "thinking_$turn",
                            kind = "thinking",
                            content = turnThinking.toString(),
                            toolTitle = "Thinking",
                            // [P-process-card] Record when thinking STARTED so
                            // the block can carry a duration when it settles
                            // (process cards sum block durations; without this
                            // thinking contributed 0 to the card's 时长).
                            startTimeMs = System.currentTimeMillis(),
                        ))
                    }
                    // [T-thinking-delta-ui-gate] The per-delta publish built an O(thinking)
                    // string + an O(reply) snapshot + a full block-list copy at 50-200 deltas/s
                    // — multi-MB/s of throwaway allocation on the collect thread before the
                    // batcher even coalesced. The thinking tail is a human-cadence surface:
                    // re-publish at ~100ms; the next non-thinking branch (text/tool flush) and
                    // the final persist always carry the complete thinking regardless of this
                    // gate.
                    val nowThinkingMs = System.currentTimeMillis()
                    if (nowThinkingMs - lastThinkingUiMs >= 100) {
                        lastThinkingUiMs = nowThinkingMs
                        if (thinkIdx >= 0) {
                            allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(content = turnThinking.toString())
                        }
                        uiBatcher.enqueue(assistantId, messageTextSnapshot(), true, allToolBlocks.toList())
                    }
                    if (hasQueuedPromptDuringPureThinking(toolCalls, turnTextSb)) cutForQueuedPrompt("thinking-phase")
                }
                is LLMStreamChunk.Text -> {
                    // Mark thinking block as done when text starts flowing
                    val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                    if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                        // [P-process-card] Settle the thinking duration here: first text after
                        // reasoning is the end of the thinking phase, so now - startTimeMs is
                        // what the block ran for.
                        materializeThinkingBlock()
                        allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(
                            toolStatus = ToolBlockStatus.SUCCESS,
                            durationMs = settleThinkingDurationMs(allToolBlocks[thinkIdx]),
                        )
                    }
                    val phase = turnOutput.phaseFor(chunk.itemId, chunk.phase)
                    val source = echoSource
                    if (source != null && (source.itemId != chunk.itemId || source.phase != phase)) flushStallEcho()
                    val echo = stallEchoStripper
                    val delta = if (echo == null || phase == AssistantTextPhase.COMMENTARY) chunk.text else {
                        echoSource = chunk.copy(phase = phase)
                        echo.feed(chunk.text)
                    }
                    if (delta.isEmpty()) return
                    appendTurnText(delta, chunk)
                    activeRun?.streamRate?.onTextDelta()
                    val len = turnTextSb.length
                    val unflushed = len - lastFlushedLen
                    val throttle = StreamingThrottle.throttleFor(len, unflushed, firstTierMs = 150L)
                    val newlineFlush = len < 5_000 && delta.contains('\n') && unflushed >= 50
                    if (System.currentTimeMillis() - lastUiUpdateMs >= throttle || newlineFlush) {
                        if (!turnOutput.deferReply && phase != AssistantTextPhase.COMMENTARY) stopStaleReadAloud()
                        publishTurnText()
                    }
                }
                is LLMStreamChunk.TextPhase -> {
                    if (echoSource?.itemId == chunk.itemId) flushStallEcho()
                    turnOutput.notePhase(chunk.itemId, chunk.phase)
                    publishTurnText()
                }
                is LLMStreamChunk.ToolUseStart -> {
                    // [T-dedupe-toolcallid] Rewrite duplicate id ASAP — the renamed value
                    // drives the AssistantBlock.id used by ToolCallComplete / ToolInputDelta
                    // lookups and becomes the persisted tool_call_id on the next request.
                    val toolUseId = toolCallIdDedupe.startId(chunk.id)
                    android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolUseStart id=$toolUseId name=${chunk.name}")
                    // Mark thinking block as done when tool use starts
                    val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                    if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                        // [P-process-card] Same settle as the text path: a tool
                        // call after reasoning also ends the thinking phase.
                        materializeThinkingBlock()
                        allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(
                            toolStatus = ToolBlockStatus.SUCCESS,
                            durationMs = settleThinkingDurationMs(allToolBlocks[thinkIdx]),
                        )
                    }
                    flushStallEcho()
                    turnOutput.requireProcess()
                    recordInterruptedText()
                    if (turnTextSb.length > lastFlushedLen) publishTurnText()
                    if (!currentProvider.streamTextIsMonolithic) turnOutput.closeSpan()
                    // T256 tier 2: zero both gate timestamps to force the next ToolInputDelta
                    // to flush immediately. iOS does the same in .startToolUse
                    // (AIChatViewModel.swift:6075-6116) so the pill name/title arrives without
                    // waiting out the 1s/200ms gate.
                    lastFileToolInputMs = 0L
                    lastOtherToolInputMs = 0L
                    // Guard: only add if not already present (prevent duplicate blocks from repeated ToolUseStart)
                    if (allToolBlocks.none { it.id == toolUseId }) {
                        allToolBlocks.add(AssistantBlock(
                            id = toolUseId,
                            kind = "tool_use",
                            toolName = chunk.name,
                            toolStatus = ToolBlockStatus.STREAMING,
                            toolTitle = resolveToolTitle(chunk.name, ""),
                            startTimeMs = System.currentTimeMillis(),
                        ))
                        uiBatcher.enqueue(assistantId, messageTextSnapshot(), true, allToolBlocks.toList())
                    }
                }
                is LLMStreamChunk.ToolInputDelta -> {
                    // [T-dedupe-toolcallid] Translate to the currently-in-flight
                    // renamed id so the per-tool ring + block lookup match
                    // the block that ToolUseStart created.
                    val toolInputId = toolCallIdDedupe.inputId(chunk.id)
                    val deltaLen = chunk.accumulated.length
                    if (com.openminis.app.text.BoundedText.shouldLogLengthStride(deltaLen)) {
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolInputDelta id=$toolInputId len=$deltaLen")
                    }
                    // Per-tool ring of the most recent `accumulated` snapshots so the
                    // preflight validator below can dump them on an empty/invalid call. Cheap
                    // (single append + bounded trim), outside any throttle so every delta lands.
                    val ring = toolInputChunkRings.getOrPut(toolInputId) { mutableListOf() }
                    ring.add(chunk.accumulated)
                    if (ring.size > ChatViewModel.TOOL_INPUT_CHUNK_RING_MAX) {
                        // Drop from the front so we keep the most recent N.
                        ring.subList(0, ring.size - ChatViewModel.TOOL_INPUT_CHUNK_RING_MAX).clear()
                    }
                    val idx = allToolBlocks.indexOfFirst { it.id == toolInputId }
                    if (idx >= 0) {
                        val prev = allToolBlocks[idx]
                        // [T-tool-title-single-source GH#344] ONE resolver for
                        // the whole lifecycle: model title → prior specific
                        // title → args summary ("Reading /path") → friendly
                        // label. Never a bare snake_case name, even mid-stream.
                        val liveTitle = resolveToolTitle(
                            prev.toolName, chunk.accumulated, prev.toolTitle,
                        )
                        allToolBlocks[idx] = prev.copy(
                            toolArgs = chunk.accumulated,
                            toolTitle = liveTitle,
                            content = "",
                        )
                        // T256 tier 2: gate UI push by tool kind. file_write/file_edit pump
                        // multi-KB JSON through the SSE — pushing every delta pegs the UI thread
                        // for no readable benefit (the user can't skim a partial JSON blob anyway).
                        // Mirrors iOS AIChatViewModel.swift:6229-6259 (1s file / 200ms other).
                        // Local state above is mutated unconditionally so when the gate opens —
                        // or ToolCallComplete force-flushes — the latest accumulated args push.
                        val toolName = prev.toolName
                        val isHeavyFileTool = toolName == "file_write" || toolName == "file_edit"
                        val gateMs = if (isHeavyFileTool) 1_000L else 200L
                        val nowMs = System.currentTimeMillis()
                        val lastTs = if (isHeavyFileTool) lastFileToolInputMs else lastOtherToolInputMs
                        if (nowMs - lastTs >= gateMs) {
                            if (isHeavyFileTool) lastFileToolInputMs = nowMs
                            else lastOtherToolInputMs = nowMs
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
                            }
                        }
                    }
                }
                is LLMStreamChunk.ToolCallComplete -> {
                    flushStallEcho()
                    turnOutput.requireProcess()
                    recordInterruptedText()
                    if (turnTextSb.length > lastFlushedLen) publishTurnText()
                    // [T-android-duplicate-toolcall-replay] Record the RAW id sighting BEFORE
                    // the renamer runs: an identical re-emission (same raw id + name + args) is
                    // flagged so the dispatch loop refuses it; a same-id call with different
                    // name/args is the documented gateway parallel case and still dispatches
                    // under its renamed id. [T-perf-async-db-init] args.toString() can be a
                    // multi-KB linear copy — run on Dispatchers.Default.
                    val argsString = StreamChunkJsonHelper.serializeArgs(chunk.args)
                    val isReplayedComplete = toolReplayGuard.registerAndCheckReplay(
                        chunk.id, chunk.name, argsString,
                    )
                    // [T-dedupe-toolcallid] Rewrite duplicate id so the persisted tool_calls
                    // list, the block lookup and the downstream tool-result join all key on the
                    // same value (matches the rename applied at start).
                    val toolCompleteId = toolCallIdDedupe.completeId(chunk.id)
                    if (isReplayedComplete) {
                        replayRenamedIds += toolCompleteId
                        AppLogger.warning(ChatViewModel.TAG_STREAM, "[ToolReplay] duplicate ToolCallComplete raw=${chunk.id} renamed=$toolCompleteId name=${chunk.name} — will refuse at dispatch, no re-execution")
                    }
                    android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolCallComplete id=$toolCompleteId name=${chunk.name} args=${argsString.take(300)}")
                    toolCalls.add(Triple(toolCompleteId, chunk.name, chunk.args))
                    // [GH#61/#83/#146] 保留真实失败字节，供 preflight 修复/拒绝。
                    chunk.rawArgs?.let { toolCallRawArgs[toolCompleteId] = it }
                    // [T-android-gemini3-thoughtsig / #179] Stash the Gemini
                    // 3.x thought signature keyed by the (deduped) tool call id.
                    chunk.thoughtSignature?.let { toolCallSignatures[toolCompleteId] = it }
                    val idx = allToolBlocks.indexOfFirst { it.id == toolCompleteId }
                    if (idx >= 0) {
                        val title = resolveToolTitle(
                            chunk.name, chunk.args.toString(), allToolBlocks[idx].toolTitle,
                        )
                        // PENDING — JSON params fully received, waiting for execution
                        // dispatcher to invoke the tool. executeTool() flips to RUNNING.
                        allToolBlocks[idx] = allToolBlocks[idx].copy(
                            toolStatus = ToolBlockStatus.PENDING,
                            toolTitle = title,
                            toolArgs = chunk.args.toString(),
                            content = "", // Clear ToolInputDelta JSON accumulation before real output arrives
                            // [T-android-gemini3-thoughtsig / #179] Persist the signature onto
                            // the block so buildTurnParts (the DB path) round-trips it; preserve
                            // any prior value if this chunk lacked one.
                            thoughtSignature = chunk.thoughtSignature ?: allToolBlocks[idx].thoughtSignature,
                        )
                        uiBatcher.enqueue(assistantId, messageTextSnapshot(), true, allToolBlocks.toList())
                    }
                }
                is LLMStreamChunk.Usage -> {
                    // PROGRESSIVE usage: keep max per field (trailing partial chunk must not erase cache/context).
                    val prev = lastUsage
                    val cur = chunk.usage
                    lastUsage = if (prev == null) cur else cur.copy(
                        inputTokens = maxOf(prev.inputTokens, cur.inputTokens),
                        outputTokens = maxOf(prev.outputTokens, cur.outputTokens),
                        cacheReadInputTokens = maxOf(prev.cacheReadInputTokens ?: 0, cur.cacheReadInputTokens ?: 0).takeIf { it > 0 },
                        cacheCreationInputTokens = maxOf(prev.cacheCreationInputTokens ?: 0, cur.cacheCreationInputTokens ?: 0).takeIf { it > 0 },
                        latestContextTokens = maxOf(prev.latestContextTokens, cur.latestContextTokens),
                    )
                    // Usage is provisional until this stream survives the retry and finish checks.
                }
                is LLMStreamChunk.ReasoningContent -> {
                    // Opaque reasoning blob (DeepSeek/Kimi reasoning_content) — recorded on
                    // the last assistant turn so it echoes back on the next request. Empty
                    // strings preserved (DeepSeek V4 emits "" on non-thinking turns; round-trip
                    // exactly). No live UI surface — ThinkingDelta events drive the panel.
                    turnReasoningBlob = chunk.content
                    // DeepSeek/Kimi emit opaque reasoning_content in the same window.
                    if (hasQueuedPromptDuringPureThinking(toolCalls, turnTextSb)) cutForQueuedPrompt("reasoning_content-phase")
                }
                is LLMStreamChunk.Finished -> {
                    // T321: stash for empty-turn diagnostic logging below.
                    turnFinishReason = chunk.stopReason
                    turnRefusalCategory = chunk.refusalCategory
                    turnRefusalExplanation = chunk.refusalExplanation
                }
                is LLMStreamChunk.Started -> { /* no-op */ }
                is LLMStreamChunk.MediaAttachment ->
                    generatedImageMarkdown(chunk.attachment)?.let { md ->
                        flushStallEcho()
                        appendTurnText(if (turnTextSb.isEmpty()) md else "\n\n$md")
                        publishTurnText()
                    }
            }
        }

        suspend fun dispatchTurn(): Boolean {
            var privacyRefreshes = 0
            while (!collectDone) {
                if (!ownsLoop()) return false
                // [T-android-model-switch-midrun-adopt] See resolveModelSwitch.
                resolveModelSwitch(loopModelEpoch, currentProvider)?.let { adopted ->
                    currentProvider = adopted.provider
                    remainingFallbacks = adopted.fallbacks.toMutableList()
                    retryAttempt = 0
                    loopModelEpoch = adopted.epoch
                    // A new model starts a new failure streak.
                    emptyStreakEntryIds.clear()
                    // [T-android-persona-switch-rebuild] Persona keys on providerInstanceId.
                    rebuildSystemPromptForAdoptedEntry()?.let { activeSystemPrompt = it }
                }
                // [GH#521] Per-attempt phase clocks (see [StreamPhaseReport]).
                val streamPhase = StreamPhaseReport()
                try {
                    // [T-android-enhanced-cache] Per-turn stamp at the single choke point every
                    // request passes through; non-Anthropic providers ignore the cast.
                    (currentProvider as? com.openminis.app.provider.anthropic.AnthropicProvider)
                        ?.enhancedCache = _enhancedCacheEnabled.value
                    // effectiveAgentHistory() prepends `<context-summary>` when a compact exists;
                    // raw agentHistory (zero-copy) otherwise. A Goal belongs to the session, not
                    // only to an explicit /goal resume run — read it every request so ordinary
                    // turns carry it.
                    val requestGoalState = withContext(Dispatchers.IO) {
                        goalManager.get(goalSessionId)
                    }
                    if (!ownsLoop()) return false
                    val goalMount = com.openminis.app.goal.goalMount(requestGoalState, goalManager::continuationPrompt)
                    goalMountedForCurrentRequest = goalMount.canContinue
                    // [T-p1-instruction-snapshot] Freeze the instruction values this request is built on.
                    commitInstructionSnapshot(
                        com.openminis.app.data.instruction.InstructionStateStore.goalInstructionValues(requestGoalState),
                    )
                    if (com.openminis.app.goal.shouldSkipGoalRunRequest(goalRunRequested, turn > 0, goalMount)) {
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
                        }
                        return false
                    }
                    commitSessionNoticesOnce(goalObjective = requestGoalState?.objective.orEmpty(),
                        goalPrompt = goalMount.prompt, goalCanContinue = goalMount.canContinue, expectedSessionId = loopSessionId)
                    if (!ownsLoop()) return false
                    val beforeSteering = assistantId
                    drainSteeringIntoRun(assistantId, accumulatedText, allToolBlocks, uiBatcher, expectedSessionId = loopSessionId) {
                        swapLoopStateToInjectedBubble(it)
                        turnStartBlockIndex = 0
                    }
                    if (assistantId != beforeSteering) activeSystemPrompt = refreshRunPromptForInjectedTurn(activeSystemPrompt, loopSessionId)
                    if (!ownsLoop()) return false
                    val tools = agentTools
                    declareToolSetDeltaIfChanged(tools)
                    if (!ownsLoop()) return false
                    // [T-receipt-notice-removed] No request-level receipt nag: its
                    // "Verify before retrying" wording made the model emit "验证 ✓"
                    // prose between tools, and an unresolved receipt re-fired it on
                    // every request. Duplicate protection lives at the tool gate
                    // (needsToolReceipt) and the per-result advisory.
                    val projection = buildDispatchProjection(
                        projectionCache, loopSessionId, currentProvider, activeSystemPrompt, tools, stallResumeContext,
                    ) ?: return false
                    when (inLoopContextCheck(projection, inLoopCompactions, mayBypassInitialCompaction)) {
                        ChatViewModel.InLoopContextAction.PROCEED -> Unit
                        ChatViewModel.InLoopContextAction.COMPACTED -> {
                            if (!ownsLoop()) return false
                            inLoopCompactions++
                            rebaseAfterCompaction()
                            continue
                        }
                        ChatViewModel.InLoopContextAction.STOP -> {
                            if (!ownsLoop()) return false
                            stopAtContextLimit(assistantId, accumulatedText, allToolBlocks, loopResumeEpoch)
                            return false
                        }
                    }
                    lastUsage = null
                    turnFinishReason = null
                    turnRefusalCategory = null
                    turnRefusalExplanation = null
                    dispatched = projection
                    val request = projection.request
                    turnOutput.deferReply = streamReplyPrefix != null || projection.outboundRequest.tools.isNotEmpty() || goalMount.canContinue ||
                        PlanBoardClosePrompt.snapshot(goalSessionId, context).any { it.status != "done" } ||
                        AgentLoopNudges.pendingSubAgentCollects(goalSessionId, context).isNotEmpty()
                    usageAttempt = projection.usageAttempt
                    journal.recordRequestJournal(turn, projection)
                    if (!ownsLoop()) return false
                    publishRequestProjection(projection)
                    val firstEventTimeoutMs = GroupRoutingState.resolveFirstEventTimeoutMs(
                        providerRepository, _selectedGroupId.value, request.thinkingLevel.isEnabled,
                    )
                    val streamIdleTimeoutMs = firstEventTimeoutMs * 2
                    streamPhase.startAttempt()
                    val stream = currentProvider.streamMessage(
                        request.messages, request.systemPrompt,
                        dynamicMaxTokens(currentProvider, projection.estimate.tokens, projection.window),
                        temperature = samplingTemperature(requestEntryId(projection.model)),
                        tools = request.tools,
                        thinkingLevel = request.thinkingLevel,
                        systemStablePrefixLen = projection.prompt?.stablePrefixLen ?: -1,
                        extraHeaders = providerExtraHeaders(projection.model.providerInstanceId, loopSessionId),
                        expectedOutboundMask = projection.outboundMask,
                    ).streamStallWatchdog(
                        firstTimeoutMs = firstEventTimeoutMs,
                        idleTimeoutMs = streamIdleTimeoutMs,
                        // [GH#521] headers 之后单独计首 token；否则 15-28s 静默网关会被 2× idle 掩盖。
                        firstContentTimeoutMs = firstEventTimeoutMs,
                        firstContentReason = "connected (stream established) but no first token " +
                            "within ${firstEventTimeoutMs / 1000}s — retrying",
                    )
                    mayBypassInitialCompaction = false
                    try {
                        stream.collect { chunk ->
                            if (!ownsLoop()) throw CancellationException("Run no longer owns the transcript")
                            if (streamPhase.onChunk(chunk)) projection.markModelProgress(loopSessionId)
                            consumeStreamChunk(chunk)
                        }
                    } finally {
                        flushStallEcho()
                        recordInterruptedText()
                    }
                    uiBatcher.flush()
                    if (!ownsLoop()) return false
                    materializeThinkingBlock()
                    publishTurnText()
                    // T256: reset throttle bookkeeping for the next turn so its first delta
                    // fires immediately rather than coalescing against this turn's baseline.
                    lastFlushedLen = 0
                    lastUiUpdateMs = 0L
                    lastFileToolInputMs = 0L
                    lastOtherToolInputMs = 0L
                    collectDone = true
                    streamPhase.logSuccess(turn, loopSessionId, currentProvider.model.id)
                    // Distinguish an empty truncation (retry) from a delivered natural stop (do not retry).
                    val win = projection.window
                    val measuredInput = reportedRequestInputTokens(
                        lastUsage, currentProvider is com.openminis.app.provider.anthropic.AnthropicProvider,
                    )
                    val successOverflowKind = com.openminis.app.provider.ContextOverflowDetector.successfulOverflowKind(
                        finishReason = turnFinishReason,
                        outputTokens = (lastUsage?.outputTokens ?: 0).toLong(),
                        inputTokens = (measuredInput ?: 0).toLong(),
                        contextWindow = win,
                    )
                    when (successOverflowKind) {
                        com.openminis.app.provider.ContextOverflowDetector.SuccessfulOverflowKind.TRUNCATED -> {
                            if (compactAndRetryForOverflow("truncation overflow on ${currentProvider.model.displayName} (usage=${lastUsage?.latestContextTokens ?: 0}, window=$win)")) {
                                // [P0-fix] collectDone was already set above, so a bare `continue`
                                // fell out of the exited while (two reviews); re-arm for the retry.
                                collectDone = false
                                continue
                            }
                        }
                        com.openminis.app.provider.ContextOverflowDetector.SuccessfulOverflowKind.SILENT -> {
                            compactForSilentOverflow(
                                "silent overflow on ${currentProvider.model.displayName} (usage=${lastUsage?.latestContextTokens ?: 0}, window=$win)",
                            )
                        }
                        null -> {}
                    }
                    // [T-stall-resume] The stream attempt succeeded — no resume context may leak
                    // into a later turn.
                    stallResumeContext = null
                    // Stream completed without error — clear any lingering retry UI state.
                    if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
                        _autoRetryAttempt.value = 0
                        _autoRetryCountdown.value = 0
                    }
                    // [T-first-event-watchdog] The operator cancels itself; no cleanup needed.
                } catch (e: OutboundPrivacyChanged) {
                    if (privacyRefreshes++ > 0) throw e
                    projectionCache.clear()
                } catch (e: QueuedPromptInterrupt) {
                    // [T-android-queued-message-interrupt-thinking] Deliberate cut,
                    // NOT a provider failure — must precede the generic catch.
                    settleInterruptedThinkingTurn(turn, allToolBlocks)
                    stallResumeContext = null
                    queuedPromptInterrupted = true
                    break
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!ownsLoop()) return false
                    if (e is CancellationException && e.cause == null) { CancellationDiagnostic.log(provider.model.displayName, loopSessionId, e, _isStreaming.value); throw e }
                    uiBatcher.flush()
                    if (!ownsLoop()) return false
                    val actual = CanceledIoClassification.asTransientStall(unwrapFlowException(e), turnTextSb.isNotEmpty()) ?: unwrapFlowException(e)
                    streamPhase.logFailure(turn, actual)
                    val isRateLimit = actual is com.openminis.app.data.model.LLMError.RateLimited
                    val is5xx = (actual as? com.openminis.app.data.model.LLMError)?.isCapacityRejection == true
                    val isPermanentCapacity = actual is com.openminis.app.data.model.LLMError.ProviderError &&
                        (actual.httpStatus == 429 ||
                            com.openminis.app.provider.HttpRetryAfter.isPermanentCapacityBody(actual.detail))
                    // [T-transient-5xx-fallback] A capacity 5xx parks the member too (30s default,
                    // Retry-After wins): a dead channel re-picked next turn is the "老是断开" pattern.
                    if (isRateLimit || isPermanentCapacity || is5xx)
                        GroupRoutingState.markRoutingCooldownFor(
                            _activeEntryId.value,
                            _selectedGroupId.value,
                            actual,
                            providerRepository,
                        )
                    // Auto-retry on transient network/5xx/transient errors on the SAME provider.
                    // [T-pi-retry-classifier][T-pi-overflow-detector] Quota/billing wording
                    // suppresses same-provider retry (quota windows reset in hours); generic
                    // provider errors route through the pi-ported overflow table into the
                    // compaction branch — the exclusion table vetoes rate-limit text (LoopErrorGate).
                    val isTransient = com.openminis.app.agent.LoopErrorGate.isTransient(actual, is5xx, isPermanentCapacity)
                    val isContextOverflow = com.openminis.app.agent.LoopErrorGate.isContextOverflow(actual)
                    // [T-compact-on-context-overflow] Compaction on the provider's own context verdict.
                    if (isContextOverflow && compactAndRetryForOverflow("${currentProvider.model.displayName}: ${actual.message?.take(160) ?: actual.javaClass.simpleName}")) {
                        continue  // retry on same provider with compacted history
                    }
                    // Compaction failed or cap spent — fall through to fallback/throw.
                    // [T-android-model-switch-midrun-adopt] No abandon: the adoption at the top of
                    // the loop already moved onto the user's pick, so this retry goes out on it.
                    val maxRetries = effectiveMaxRetries()
                    // 429 with another group member: switch endpoints instead of hammering the same
                    // key through 1/2/4/8/16s (inside a typical 60s relay window). A relay 5xx is the
                    // same capacity verdict (measured on token.zlshag.xyz: instant 503 for every
                    // payload shape while a sibling model on the same key streams 200) — one quick
                    // attempt absorbs a blip, the rest of the budget goes to switching; network
                    // errors and last-candidate 5xx/429 keep the ladder.
                    val skipSameProviderRetry = (isRateLimit && remainingFallbacks.isNotEmpty()) || isPermanentCapacity
                    val sameProviderBudget = when {
                        isRateLimit -> minOf(maxRetries, 1)
                        is5xx && remainingFallbacks.isNotEmpty() -> minOf(maxRetries, 1)
                        else -> maxRetries
                    }
                    if (isTransient && !skipSameProviderRetry && sameProviderBudget > 0 && retryAttempt < sameProviderBudget) {
                        val retryAfter = (actual as? com.openminis.app.data.model.LLMError.RateLimited)?.retryAfterSeconds
                        val delaySec = com.openminis.app.provider.HttpRetryAfter.delaySeconds(
                            retryAttempt, retryAfter, ChatViewModel.AUTO_RETRY_DELAYS_SEC,
                        )
                        retryAttempt += 1
                        val errDesc = actual.message ?: actual.javaClass.simpleName
                        Log.w(ChatViewModel.TAG, "🔁 Transient error on ${currentProvider.model.displayName}, retry $retryAttempt/$maxRetries in ${delaySec}s: $errDesc")
                        withContext(Dispatchers.Main) {
                            _autoRetryAttempt.value = retryAttempt
                            // Inline error on the streaming bubble during countdown; keeps
                            // isStreaming=true so the UI doesn't tear down the stream state.
                            setTransientInlineError("$errDesc — retrying ($retryAttempt/$maxRetries)…")
                        }
                        try {
                            for (remaining in delaySec downTo 1) {
                                _autoRetryCountdown.value = remaining
                                kotlinx.coroutines.delay(1000)
                            }
                            val jitter = com.openminis.app.provider.HttpRetryAfter.jitterMs()
                            if (jitter > 0L) kotlinx.coroutines.delay(jitter)
                        } finally {
                            _autoRetryCountdown.value = 0
                        }
                        // Clear inline error so the retry attempt can start cleanly.
                        withContext(Dispatchers.Main) { clearInlineError() }
                        val stalled = (actual as? LLMError.TransientError)?.stalledAfterFirstEvent == true
                        flushStallEcho()
                        val startedTool = allToolBlocks.asSequence().drop(turnStartBlockIndex).any { it.kind == "tool_use" }
                        val prefix = if (stalled) turnOutput.fragments(forceProcess = startedTool) else emptyList()
                        val partial = if (stalled) turnTextSb.toString() else ""
                        stallResumeContext = com.openminis.app.provider.StallResume.note(partial).takeIf { it.isNotEmpty() }
                        val canStripEcho = prefix.lastOrNull()?.phase != AssistantTextPhase.COMMENTARY
                        stallEchoStripper = stallResumeContext?.takeIf { canStripEcho }?.let {
                            com.openminis.app.provider.StallEchoStripper(com.openminis.app.provider.StallResume.tail(partial))
                        }
                        turnOutput.reset()
                        echoSource = null
                        allToolBlocks.subList(turnStartBlockIndex, allToolBlocks.size).clear()
                        // A resumed stream continues these bytes, but the failed attempt's calls are discarded.
                        for (fragment in prefix) {
                            turnOutput.append(fragment.text, phase = fragment.phase, restored = true)
                            turnOutput.closeSpan()
                        }
                        publishTurnText()
                        turnThinking.clear()
                        // [T-reasoning-blob-per-attempt] The dead attempt's reasoning echo must not survive
                        // onto the retried turn — a fallback on a non-reasoning provider would replay
                        // attempt 1's reasoning_content verbatim.
                        turnReasoningBlob = null
                        toolCalls.clear()
                        toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                        // [T-android-duplicate-toolcall-replay] Reset replay bookkeeping with the dispatch
                        // list: the retried attempt re-streams into an empty toolCalls, so dead-attempt
                        // raw-id sightings must not refuse the fresh ones.
                        toolReplayGuard.reset()
                        replayRenamedIds.clear()
                        // T94 fix 2 + T256: throttle bookkeeping is per-stream attempt; reset with the
                        // partial-block rollback so the next attempt's first delta fires immediately
                        // rather than coalescing against stale baselines.
                        lastUiUpdateMs = 0L
                        lastFlushedLen = 0
                        lastFileToolInputMs = 0L
                        lastOtherToolInputMs = 0L
                        continue  // retry on same provider
                    }
                    // Retries exhausted or non-retryable — proceed to fallback / throw.
                    _autoRetryAttempt.value = 0
                    _autoRetryCountdown.value = 0
                    // [T-android-timeout-while-running] Clear any transient inline error from the
                    // prior retry attempts before we engage the fallback lane.
                    withContext(Dispatchers.Main) { clearInlineError() }
                    val llmErr = actual as? com.openminis.app.data.model.LLMError
                    val shouldFallback = fallbackStrategy == com.openminis.app.data.model.FallbackStrategy.always ||
                        llmErr?.isFallbackable == true ||
                        isRateLimit || is5xx || isPermanentCapacity
                    val curGate = currentProvider.callGateKey
                    val nextCandidate = if (shouldFallback && _selectedGroupId.value != null) {
                        var c: ChatViewModel.FallbackCandidate? = remainingFallbacks.removeFirstOrNull()
                        while (c != null && com.openminis.app.provider.ProviderKeyGate.sameBucket(c.provider.callGateKey, curGate)) {
                            c = remainingFallbacks.removeFirstOrNull()
                        }
                        c
                    } else null
                    val next = nextCandidate?.provider
                    if (next != null && nextCandidate != null) {
                        val reason = when {
                            isRateLimit -> actual.message ?: "Rate limited"
                            actual is com.openminis.app.data.model.LLMError.ProviderError -> actual.detail
                            else -> actual.message ?: "Error"
                        }
                        // [T-android-model-indicator-flash-on-endpoint-retry] Same-model recovery
                        // is a TRANSPARENT retry, not a real
                        val isRealModelChange = next.model.id != currentProvider.model.id
                        fallbackReasons.add("⚠️ ${currentProvider.model.displayName}: $reason")
                        Log.i(ChatViewModel.TAG, "🔀 $reason on ${currentProvider.model.displayName}, switching to ${next.model.displayName} (realModelChange=$isRealModelChange)")
                        currentProvider = next
                        triedEntryIds.add(nextCandidate.entryId)
                        retryAttempt = 0
                        // [T-android-model-switch-midrun-adopt] The run stays on `next` for the
                        // rest of THIS request, but persisting when the user picked another model
                        // mid-stream would clobber that pick and leave the top-of-loop adoption
                        // with nothing to adopt (the old code wrote unconditionally).
                        if (ModelSwitchGate.mayWriteBack(loopModelEpoch, snapshotModelSelectionEpoch())) {
                            persistFallbackModelSelection(next, nextCandidate.entryId, isRealModelChange)
                        } else {
                            Log.i(
                                ChatViewModel.TAG,
                                "[ModelSwitch] user changed model mid-run (epoch $loopModelEpoch -> ${snapshotModelSelectionEpoch()}); " +
                                    "skipping fallback writeback so the next request adopts the user's selection",
                            )
                        }
                        val infoText = fallbackReasons.joinToString("\n") + "\n🔄 Switched to ${currentProvider.model.displayName}"
                        turnOutput.reset()
                        echoSource = null
                        stallResumeContext = null
                        stallEchoStripper = null
                        allToolBlocks.subList(turnStartBlockIndex, allToolBlocks.size).clear()
                        turnStartBlockIndex += 1 - allToolBlocks.count { it.kind == "info" }
                        allToolBlocks.removeAll { it.kind == "info" }
                        allToolBlocks.add(0, AssistantBlock(
                            id = "fallback_info_$turn",
                            kind = "info",
                            content = infoText,
                            toolTitle = "Switched model",
                            toolStatus = ToolBlockStatus.SUCCESS,
                        ))
                        publishTurnText()
                        lastUiUpdateMs = 0L
                        turnThinking.clear()
                        // [T-reasoning-blob-per-attempt] Same contract as the same-provider retry
                        // above: the fallback provider owns the reasoning field now.
                        turnReasoningBlob = null
                        toolCalls.clear()
                        toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                        // [T-android-duplicate-toolcall-replay] Reset replay bookkeeping with the
                        // dispatch list: the retried attempt re-streams into an empty toolCalls, so
                        // dead-attempt raw-id sightings must not refuse the fresh ones.
                        toolReplayGuard.reset()
                        replayRenamedIds.clear()
                        // loop continues — will retry collect with currentProvider
                    } else {
                        // All fallbacks exhausted. Surface the trail of tried
                        // models AND the group members that were silently
                        // skipped (disabled / not logged in / hidden) so the
                        // user can see why fallback never reached them —
                        // mirrors iOS streamWithGroupFallback exhausted path.
                        if (shouldFallback) {
                            val skipped = unavailableGroupMembers()
                            if (fallbackReasons.isNotEmpty() || skipped.isNotEmpty()) {
                                val trail = (fallbackReasons + skipped).joinToString("\n")
                                val finalDesc = actual.message ?: actual.toString()
                                throw com.openminis.app.data.model.LLMError.ProviderError("$trail\n$finalDesc")
                            }
                        }
                        throw actual  // re-throw unwrapped, all fallbacks exhausted
                    }
                }
            }  // end while (!collectDone)
            return true
        }
        if (!dispatchTurn()) {
            loopExitedNormally = true
            break
        }
        if (!ownsLoop()) return

        // [T-android-queued-message-interrupt-thinking] The thinking-phase
        // cut landed here: skip normal bookkeeping and inject as a standalone
        // turn. Not persisted: a row here would sort AFTER the queued user
        // row and render below the prompt it preceded on every reload.
        if (queuedPromptInterrupted) {
            if (turn == 0) generateSessionTitleIfNeeded()
            val handled = drainQueueIntoStandaloneTurn(assistantId, accumulatedText, allToolBlocks, uiBatcher, expectedSessionId = loopSessionId)
            if (!ownsLoop()) return
            if (handled != null) {
                swapLoopStateToInjectedBubble(handled)
                activeSystemPrompt = refreshRunPromptForInjectedTurn(activeSystemPrompt, loopSessionId)
                continue
            }
            // null = queue drained concurrently / no content — the normal
            // empty-turn handling below persists the thinking-only turn once.
        }

        // T307: materialise the per-turn StringBuilder ONCE at the
        // turn boundary. After this point everything is plain String
        // semantics — `turnText` participates in cross-turn accumulation
        // and gets persisted into agentHistory below.
        val turnText = turnTextSb.toString()
        // Accumulate text across turns
        accumulatedText += turnText
        recordInterruptedText()

        // Map toolUseId -> input JSON string for persistence (accumulated across turns)
        toolCalls.forEach { (id, _, args) -> allToolInputs[id] = args.toString() }
        val toolInputMap = allToolInputs
        // Prefer the opaque blob from LLMStreamChunk.ReasoningContent when the
        // provider emitted one — that path preserves empty strings (DeepSeek V4
        // `reasoning_content: ""` on non-thinking turns). Fall back to the
        // ThinkingDelta concatenation only when no blob arrived; in that case
        // an empty buffer becomes null (no field to round-trip).
        val turnReasoningContent: String? = turnReasoningBlob
            ?: turnThinking.toString().takeIf { it.isNotEmpty() } // fallback: no blob arrived

        if (toolCalls.isEmpty()) appendBoundedHistory(LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = turnText,
            contentParts = if (turnText.isEmpty()) emptyList() else listOf(AgentContentPart.Text(turnText)),
            reasoningContent = turnReasoningContent,
        ))
        // [port-upstream-1.16][T-b13-empty-fallback] A turn with content ends
        // the empty streak.
        if (toolCalls.isNotEmpty() || EmptyTurnRecovery.turnHasText(turnText, allToolBlocks, turnStartBlockIndex)) {
            emptyStreakEntryIds.clear()
        }
        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
        // T321: empty-turn diagnostic — fires when GPT-5.5 (or any other
        // provider) returns a turn with no visible text AND no tool calls.
        // Log only; UI behavior unchanged. Pair with OpenAIProvider SSE
        // logs to triage server-empty vs parser-drop vs swallowed-exception.
        if (turnText.isEmpty() && toolCalls.isEmpty()) {
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "empty turn detected: turn=$turn finishReason=$turnFinishReason " +
                    "reasoningLen=${turnThinking.length} reasoningBlobLen=${turnReasoningBlob?.length ?: -1} " +
                    "model=${provider.model.id} provider=${provider.name}"
            )
        }

        // [port-upstream-1.16][T-android-dsml-leak] DeepSeek wrote its tool
        // call while still thinking: the markup and the argument body landed
        // in reasoning_content, tool_calls is empty and the turn "stopped".
        // A malformed tool call, not an answer — drop the leaked turn and
        // resend the same request (MAX_RETRIES guard). The leaked markup is
        // never replayed to the model or persisted.
        if (toolCalls.isEmpty()) {
            val dsmlReasoning = turnReasoningContent ?: turnThinking.toString()
            val dsmlMarker = com.openminis.app.provider.DsmlLeak.marker(dsmlReasoning)
            if (dsmlMarker != null && com.openminis.app.provider.DsmlLeak.isLeak(
                    turnHasText = EmptyTurnRecovery.turnHasText(turnText, allToolBlocks, turnStartBlockIndex),
                    hasToolCalls = false,
                    finishReason = turnFinishReason,
                    reasoning = dsmlReasoning,
                )
            ) {
                agentHistory.removeAt(agentHistory.lastIndex)
                allToolBlocks.subList(turnStartBlockIndex, allToolBlocks.size).clear()
                accumulatedText = accumulatedText.dropLast(turnText.length)
                turnOutput.reset()
                recordInterruptedText()
                when (val step = com.openminis.app.provider.DsmlLeak.next(dsmlLeakRetries)) {
                    is com.openminis.app.provider.DsmlLeak.Step.Retry -> {
                        dsmlLeakRetries = step.attempt
                        AppLogger.warning(
                            ChatViewModel.TAG_STREAM,
                            "[dsml-leak] turn=$turn retry=${step.attempt}/${com.openminis.app.provider.DsmlLeak.MAX_RETRIES} " +
                                "marker=$dsmlMarker model=${provider.model.id} — resending the same request",
                        )
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                        }
                        continue
                    }
                    com.openminis.app.provider.DsmlLeak.Step.GiveUp -> {
                        // Recovery expects the current assistant stub at the tail.
                        appendBoundedHistory(LLMMessage(LLMMessage.Role.ASSISTANT, ""))
                    }
                }
            }
        }

        val completed = dispatched?.takeIf {
            collectDone && eligibleContextReceipt(turnFinishReason, turnText.isNotBlank(), toolCalls.isNotEmpty())
        }
        val contextUsageAnchor = completed?.let { request ->
            acceptRequestProjection(currentProvider, request, usageAttempt, lastUsage, receiptUserTurnId)
        }
        val servedModel = dispatched?.model
        val requestedThinking = dispatched?.request?.thinkingLevel ?: turnThinkingLevel
        suspend fun persistTextTurn(asReply: Boolean = false, recoveryNote: String? = null): String? {
            // Keep Stop's snapshot provisional until the durable write wins.
            val parts = turnOutput.parts(asReply)
            val acceptedPrefix = streamReplyPrefix.takeIf { asReply }
            val id = persistAgentTextTurn(
                checkNotNull(activeRun), loopSessionId, loopResumeEpoch, assistantId,
                parts, lastUsage, turnReasoningContent, requestedThinking, servedModel, contextUsageAnchor,
                recoveryNote, acceptedPrefix,
                onCommitted = {
                    turnOutput.settle(asReply)
                    acceptedPrefix?.applyTo(allToolBlocks)
                    if (asReply) drainLiveAssistantUi(assistantId, accumulatedText, allToolBlocks)
                },
            )
            if (id != null && asReply) streamReplyPrefix = null
            return id
        }
        suspend fun publishSettledTurn(awaiting: Boolean = false) {
            if (!awaiting) uiBatcher.seal(assistantId)
            withContext(Dispatchers.Main.immediate) {
                if (ownsLoop()) updateAssistantMessage(assistantId, accumulatedText, awaiting, allToolBlocks, awaiting)
            }
        }
        suspend fun continueWith(note: String): Boolean {
            persistTextTurn(recoveryNote = note)
            streamReplyPrefix = null
            if (!ownsLoop()) return false
            publishSettledTurn(awaiting = true)
            return ownsLoop()
        }

        if (toolCalls.isEmpty()) {
            val canDispatchAgain = turn < maxTurns - 1
            val hasReply = turnOutput.hasReplyCandidate()
            var goalAccountingSucceeded = true
            if (goalMountedForCurrentRequest) {
                val tokens = (lastUsage?.inputTokens ?: 0).toLong() + (lastUsage?.outputTokens ?: 0).toLong()
                goalAccountingSucceeded = goalBookkeeping("Goal usage accounting") {
                    // Only the committed tool pair below advances the stall counter.
                    goalManager.recordUsage(goalSessionId, tokens, (System.currentTimeMillis() - turnStartedAtMs).coerceAtLeast(0))
                }.isSuccess
            }
            if (!ownsLoop()) return

            if (turnFinishReason == null && turnText.isNotBlank()) {
                val end = resolveReasonlessStreamEnd(usedStreamDropContinuation, hasReply, canDispatchAgain)
                if (end == ReasonlessEnd.COMPLETE) {
                    turnFinishReason = "end_turn"
                } else {
                    val rowId = persistTextTurn(recoveryNote = StreamDropReminder.TEXT)
                    if (!ownsLoop()) return
                    if (end == ReasonlessEnd.TEXT_CONTINUE && rowId != null) {
                        usedStreamDropContinuation = true
                        if (hasReply) streamReplyPrefix = StreamReplyPrefix(rowId, turnOutput.replyBlockIds(), turnOutput.parts(asReply = true))
                        publishSettledTurn(awaiting = true)
                        continue
                    }
                    publishSettledTurn()
                    withContext(Dispatchers.Main) {
                        if (ownsLoop()) setInlineError(context.getString(R.string.chat_error_stream_dropped_partial))
                    }
                    if (ownsLoop()) markLiveInterruptionAndArmResume(loopResumeEpoch)
                    loopExitedNormally = true
                    break
                }
            }

            if (canDispatchAgain && isNaturalAgentStop(turnFinishReason)) {
                if (hasReply && goalAccountingSucceeded && goalRunRequested &&
                    goalContinuations < ChatViewModel.MAX_GOAL_CONTINUATIONS &&
                    com.openminis.app.service.SessionActivityTracker.isActive(goalSessionId)
                ) {
                    val goal = goalBookkeeping("Goal continuation check") {
                        goalManager.get(goalSessionId)?.takeIf { goalManager.mayContinue(goalSessionId, usefulActivity = true) }
                    }.getOrNull()
                    if (goal != null) {
                        if (!continueWith(com.openminis.app.goal.GoalContinuation.note(goal))) return
                        goalContinuations++
                        continue
                    }
                }
                val planNote = goalBookkeeping("Plan close check") { planBoardCloseNoteFor(goalSessionId, planCloseNudges) }.getOrNull()
                if (planNote != null) {
                    if (!continueWith(planNote)) return
                    planCloseNudges++
                    continue
                }
                if (subAgentCollectNudges < ChatViewModel.MAX_SUBAGENT_COLLECT_NUDGES) {
                    val pending = AgentLoopNudges.pendingSubAgentCollects(goalSessionId, context)
                    if (pending.isNotEmpty()) {
                        if (!continueWith(AgentLoopNudges.renderSubAgentCollectNudge(pending))) return
                        subAgentCollectNudges++
                        continue
                    }
                }
                if (goalMountedForCurrentRequest && ownsLoop()) {
                    val note = goalBookkeeping("Goal drift reminder") { goalManager.driftNudgeIfOwed(goalSessionId, context) }.getOrNull()
                    if (note != null) {
                        if (!continueWith(note)) return
                        AppLogger.info(ChatViewModel.TAG_STREAM, "goal drift nudge delivered (session=$goalSessionId)")
                        continue
                    }
                }
            }

            // [port-upstream-1.16][T-android-model-refusal] The model DECLINED
            // the request (Anthropic stop_reason=refusal: HTTP 200, input
            // billed, empty content; OpenAI content_filter). A refusal is
            // deterministic — no empty-turn nudge and no retry: say so on the
            // reply and let the user rephrase or switch model.
            if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)) {
                persistTextTurn()
                if (!ownsLoop()) return
                publishSettledTurn()
                AppLogger.warning(
                    ChatViewModel.TAG_STREAM,
                    "Agent loop ended with stop_reason=$turnFinishReason — model declined the request " +
                        "(turn=$turn model=${provider.model.id} textLen=${accumulatedText.length})",
                )
                val refusalText = com.openminis.app.provider.StopReasons.refusalErrorText(
                    categoryMessage = turnRefusalCategory?.let {
                        context.getString(R.string.chat_error_model_refused_category, it)
                    },
                    genericMessage = context.getString(R.string.chat_error_model_refused),
                    explanation = turnRefusalExplanation,
                )
                if (activeRun?.isStopped != true) {
                    withContext(Dispatchers.Main) { setInlineError(refusalText) }
                }
                _canResume.value = false
                loopExitedNormally = true
                break
            }

            // [T-android-empty-turn-stopreason] Gate the empty-turn path on
            // BOTH "nothing visible" and a terminal stop reason; a
            // tool_calls/tool_use stop with no call is empty too
            // (EmptyTurnRecovery.shouldClassifyAsEmptyTurn). THIS turn's text
            // decides, not the bubble's (T-android-empty-turn-this-turn).
            val isEmptyTurn = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(
                hasText = hasReply,
                hasToolCall = false,
                stopReason = turnFinishReason,
            )
            if (isEmptyTurn && turnText.isBlank() && canDispatchAgain) {
                streamReplyPrefix = null
                // [port-upstream-1.16][T-android-empty-turn-reminder-any-tail]
                // Recover from ANY empty turn, not only one that follows a
                // tool_result; the object owns the decision, the loop the
                // mutation and the once-per-run guard.
                val plan = EmptyTurnRecovery.plan(agentHistory, didInjectEmptyToolReminder)
                fun injectEmptyTurnReminder(): Boolean {
                    val (injected, didInject) = injectEmptyTurnReminderImpl(plan, agentHistory)
                    if (didInject) didInjectEmptyToolReminder = true
                    return injected
                }
                // [port-upstream-1.16][T-b13-empty-fallback] In a model group
                // an empty turn IS a failure: Always switches member at once
                // (reminder rides along); the default strategy first spends
                // the same-model reminder round, then moves on. A member that
                // came back empty in this streak is never picked again.
                _activeEntryId.value?.let { emptyStreakEntryIds.add(it) }
                val inGroup = _selectedGroupId.value != null
                var emptyRoute = EmptyTurnFallback.route(
                    fallbackStrategy, inGroup, plan !is EmptyTurnRecovery.Plan.GiveUp, hasNextMember = true,
                )
                val emptyNext = if (EmptyTurnFallback.movesMember(emptyRoute)) {
                    buildFallbackProviders(currentProvider).firstOrNull {
                        it.entryId !in emptyStreakEntryIds && it.entryId !in triedEntryIds
                    }
                } else null
                if (emptyNext == null && EmptyTurnFallback.movesMember(emptyRoute)) {
                    emptyRoute = EmptyTurnFallback.route(
                        fallbackStrategy, inGroup, plan !is EmptyTurnRecovery.Plan.GiveUp, hasNextMember = false,
                    )
                }
                if (emptyRoute == EmptyTurnFallback.Route.SAME_MODEL_REMINDER && injectEmptyTurnReminder()) continue
                if (emptyNext != null && EmptyTurnFallback.movesMember(emptyRoute)) {
                    if (emptyRoute == EmptyTurnFallback.Route.NEXT_MEMBER_WITH_REMINDER) {
                        injectEmptyTurnReminder()
                    } else {
                        // The same request: drop the empty assistant turn so
                        // the next member answers from the same tail.
                        agentHistory.removeAt(agentHistory.size - 1)
                    }
                    val next = emptyNext.provider
                    val reasoningOnly = !turnReasoningContent.isNullOrEmpty() || turnThinking.isNotEmpty()
                    val reason = EmptyTurnFallback.trailReason(reasoningOnly, afterReminder = didInjectEmptyToolReminder)
                    val isRealModelChange = next.model.id != currentProvider.model.id
                    fallbackReasons.add("⚠️ ${currentProvider.model.displayName}: $reason")
                    AppLogger.warning(
                        ChatViewModel.TAG_STREAM,
                        "🔀 empty turn on ${currentProvider.model.displayName}, switching to ${next.model.displayName} " +
                            "(route=$emptyRoute reasoningOnly=$reasoningOnly spent=${emptyStreakEntryIds.size} turn=$turn)",
                    )
                    remainingFallbacks.removeAll { it.entryId == emptyNext.entryId }
                    currentProvider = next
                    retryAttempt = 0
                    if (ModelSwitchGate.mayWriteBack(loopModelEpoch, snapshotModelSelectionEpoch())) {
                        persistFallbackModelSelection(next, emptyNext.entryId, isRealModelChange)
                    }
                    val infoText = fallbackReasons.joinToString("\n") + "\n🔄 Switched to ${next.model.displayName}"
                    allToolBlocks.removeAll { it.kind == "info" }
                    allToolBlocks.add(0, AssistantBlock(
                        id = "fallback_info_$turn",
                        kind = "info",
                        content = infoText,
                        toolTitle = "Switched model",
                        toolStatus = ToolBlockStatus.SUCCESS,
                    ))
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                    }
                    continue
                }
            }

            val naturalReply = !isEmptyTurn && hasReply && isNaturalAgentStop(turnFinishReason)
            persistTextTurn(asReply = naturalReply, recoveryNote = StreamDropReminder.TEXT.takeUnless { naturalReply })
            if (!ownsLoop()) return
            publishSettledTurn()
            if (!ownsLoop()) return
            if (naturalReply) {
                stopStaleReadAloud()
                completedReply = replyContent(accumulatedText, allToolBlocks)
                if (turn == 0) generateSessionTitleIfNeeded()
                if (canDispatchAgain && steeringKeepAlive()) {
                    val settledId = assistantId
                    drainSteeringIntoRun(assistantId, accumulatedText, allToolBlocks, uiBatcher, expectedSessionId = loopSessionId) {
                        swapLoopStateToInjectedBubble(it)
                    }
                    if (!ownsLoop()) return
                    if (assistantId != settledId) {
                        completedReply = null
                        activeSystemPrompt = refreshRunPromptForInjectedTurn(activeSystemPrompt, loopSessionId)
                        continue
                    }
                }
            } else {
                val window = effectiveContextWindowTokens()
                val usedCtx = lastUsage?.latestContextTokens ?: 0
                val hint = when {
                    !isEmptyTurn -> context.getString(R.string.chat_response_interrupted)
                    didInjectEmptyToolReminder -> context.getString(R.string.error_empty_response_after_tool)
                    window != null && window > 0 && usedCtx.toDouble() / window > 0.70 ->
                        context.getString(R.string.error_empty_response_context_large)
                    else -> context.getString(R.string.error_empty_response_generic)
                }
                withContext(Dispatchers.Main) { if (ownsLoop()) setInlineError(hint) }
                markLiveInterruptionAndArmResume(loopResumeEpoch)
            }
            if (!naturalReply && turn == 0) generateSessionTitleIfNeeded()
            drainToPromptQueueFallback()
            loopExitedNormally = true
            break
        }
        streamReplyPrefix = null
        turnOutput.settle(asReply = false)
        recordInterruptedText()
        AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop turn=$turn dispatching ${toolCalls.size} tool call(s), continuing")

        // [T-android-session-last-message-live-tool-call] Push a live preview to the
        // session list NOW, before the (possibly long-running) tool dispatch; the
        // list row otherwise lags one turn behind.
        pushLiveSessionPreview(
            allToolBlocks = allToolBlocks,
            turnStartBlockIndex = turnStartBlockIndex,
            toolInputMap = toolInputMap,
        )

        // [T-truncated-tool-call-reject] finish_reason=length/max_tokens 由 ToolCallPreflight 统一拒绝。
        val turnCtx = ToolTurnContext(
            ledger = toolExecutionLedger,
            activeSessionId = loopSessionId,
            assistantId = assistantId,
            accumulatedText = accumulatedText,
            turn = turn,
            turnFinishReason = turnFinishReason,
            agentTools = dispatched?.request?.tools.orEmpty(),
            replayRenamedIds = replayRenamedIds,
            allToolBlocks = allToolBlocks,
            toolInputChunkRings = toolInputChunkRings,
            toolCallRawArgs = toolCallRawArgs,
            receiptUserTurnId = receiptUserTurnId, generationRunId = generationRunId,
            // [T-receipt-after-result] The boundary is what THIS attempt actually
            // carried: the last result row (with its host proof) visible in the
            // projected request, never an attempt counter or a live UI bubble.
            receiptAfterResultId = receiptAfterResultRowId(dispatched?.request?.messages.orEmpty(), receiptUserTurnId),
        )
        validateCompletedToolCalls(toolCalls)
        val plan = preflightToolTurn(turnCtx, toolCalls)
        toolInputMap.putAll(plan.repairedArgsById)
        if (!ownsLoop()) return
        val turnParts = completedToolTurnParts(
            buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap),
            toolCalls, toolInputMap, toolCallSignatures, turnText,
        )
        if (goalMountedForCurrentRequest) goalBookkeeping("Goal drift observation") {
            goalManager.observeDriftBoard(goalSessionId, context)
        }
        if (!ownsLoop()) return
        val execution = executePlannedToolTurn(turnCtx, plan)

        // ---- Phase 3: assemble resultParts in emission order ----
        val resultParts = assembleTurnResultParts(toolCalls, plan, execution)

        // [T-frozen-repeat-hint] One hint inside the tool result; never a USER turn.
        ToolRepeatTracker.applyHint(repeatTracker, toolCalls, resultParts)

        currentCoroutineContext().ensureActive()
        if (!ownsLoop()) return
        // [T-atomic-tool-turn] Only committed calls and results enter request history.
        val (assistantDbId, toolResultDbId) = persistToolTurnPairForRun(
            checkNotNull(activeRun) { "Agent loop has no persistence owner" },
            turnParts,
            allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id },
            lastUsage, turnReasoningContent, resultParts,
            targetSessionId = goalSessionId,
            expectedEpoch = loopResumeEpoch,
            uiMessageId = assistantId,
            requestedLevel = requestedThinking,
            modelSnapshot = servedModel,
            contextUsageAnchor = contextUsageAnchor,
        ) ?: return
        if (!ownsLoop()) return
        reconcilePersistedToolTurn(goalSessionId, receiptUserTurnId, assistantDbId, toolResultDbId, turnParts, resultParts)
        withContext(Dispatchers.Main.immediate) {
            if (ownsLoop()) updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks, isAwaitingModelResponse = true)
        }
        if (!ownsLoop()) return
        val goalUsageResult = goalManager.recordCommittedTurnUsage(goalMountedForCurrentRequest, goalSessionId, lastUsage, turnStartedAtMs, context)
        // Goal completion dismounts steering; only hard stops end this reply.
        if (com.openminis.app.goal.shouldExitForGoalState(goalUsageResult, goalMountedForCurrentRequest)) {
            withContext(Dispatchers.Main) {
                updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
            }
            loopExitedNormally = true
            break
        }

        // [T-subagent-completion-timeliness] After-batch drain of finished waves.
        val waveNote = AgentLoopNudges.drainFinishedWaves(goalSessionId, context, announcedFinishedBatches)
        if (waveNote != null && continueWith(waveNote)) {
            subAgentCollectNudges++
            continue
        }

        // [T-goal-drift-reconciler] Per-tool-turn stall accounting rides
        // recordUsage above (context passed there). Delivery at natural end below.

        // [T-no-mid-task-speech] No stall nudge; repeats get a hint inside the tool result.

        if (turn == 0) generateSessionTitleIfNeeded()  // auto-title after first exchange

    // [T-android-queued-message-interrupt-on-toolclose] queue at a tool boundary -> standalone turn.
        if (_promptQueue.value.isNotEmpty()) {
            AppLogger.info(
                ChatViewModel.TAG_STREAM,
                "📨[QueueInterrupt] turn=$turn ${_promptQueue.value.size} queued prompt(s) — interrupting after current tool call to start a standalone turn",
            )
            val handled = drainQueueIntoStandaloneTurn(assistantId, accumulatedText, allToolBlocks, uiBatcher, expectedSessionId = loopSessionId)
            if (!ownsLoop()) return
            if (handled != null) {
                swapLoopStateToInjectedBubble(handled)
                activeSystemPrompt = refreshRunPromptForInjectedTurn(activeSystemPrompt, loopSessionId)
                continue
            }
            // null = empty-after-build / drain rejected; fall through to normal next-turn dispatch so the queue doesn't pin the loop.
        }
    }
    if (!ownsLoop()) return
    if (!loopExitedNormally) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "runAgentLoop EXIT — hit turn cap=$maxTurns, finalizing as resumable",
        )
        // [T-generation-run] Not a clean finish — close the ledger so the launch scan reserves ABANDONED for a process that actually died.
        withContext(Dispatchers.Main) {
            finalizeAtTurnLimit(assistantId, accumulatedText, allToolBlocks, loopResumeEpoch)
        }
    } else {
        AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
    }
    // [T-context-ring] Refresh after settlement. [T-usage-display-caliber] A
    // settled MEASURED reading is the most truthful number for the last request;
    // this tail refresh only carries an unbudgeted raw estimate of a different
    // caliber — skip it while a measured reading is showing.
    if ((contextUsage.value?.measuredPrefixTokens ?: 0) == 0L) {
        refreshContextUsage()
    }
    // [GH#131] One assistant response has settled. A cancelled turn (Stop) deliberately
    // does NOT fire: there is no complete reply, and user scripts treat OnResponse as
    // "the answer is final" (notify, sync out, downstream automation). Cancellation
    // normally exits the loop first; the isActive guard closes the race where it lands here.
    val answer = completedReply
    if (answer != null && currentCoroutineContext().isActive && ownsLoop()) {
        com.openminis.app.agent.GenerationRunLifecycle.succeeded(context, generationRunId)
        runCatching {
            com.openminis.app.agent.lifecycle.LifecycleHooks.fire(
                com.openminis.app.agent.lifecycle.LifecycleHooks.LifecycleEvent.RESPONSE,
                loopSessionId,
                answer.takeLast(500),
            )
        }
        if (ownsLoop()) runCatching {
            announcementSink?.let {
                com.openminis.app.speech.VoiceAnnouncementCoordinator(it).onScene(context, com.openminis.app.speech.AnnouncementScene.IDLE_AGENT_DONE)
            }
        }
    }
}
