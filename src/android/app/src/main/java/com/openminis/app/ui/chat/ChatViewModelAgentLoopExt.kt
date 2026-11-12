package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.data.GroupRoutingState
import com.openminis.app.data.ToolLimitPrefs
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.ToolIdNormalization
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.countsAsFirstTokenProgress
import com.openminis.app.provider.streamStallWatchdog
import com.openminis.app.tools.SubAgentKind
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
    systemPrompt: String?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate> = emptyList(),
    fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
    goalExecutionRun: Boolean = false,
    /** [T-android-instant-thinking] send 路径已预插占位气泡，复用其 id。 */
    overrideAssistantId: String? = null,
) {
    // Room is the transcript authority. Rebuild immediately before the first
    // request so UI paging, cold-open tail limits, and the resident hot-cache
    // cannot hide older user instructions from the model. The current user row
    // is already persisted by sendMessage; this also restores raw BodyStore
    // bodies instead of the display projection.
    withContext(Dispatchers.IO) {
        rebuildAgentHistoryFromDatabase(activeSessionId)
    }
    // [T-android-resume-banner-race] Snapshot the resume epoch at run start:
    // every `_canResume = true` this loop's tail may emit is only allowed to
    // land while this snapshot is still current. A newer send bumps the
    // epoch, so a tail that outlives its replacement drops the raise instead
    // of re-showing the resume banner over the fresh turn.
    val loopResumeEpoch = snapshotResumeEpoch()
    // [T-android-model-switch-midrun-adopt] Epoch this run last acted on.
    // var: resolveModelSwitch reassigns it when a mid-run pick is adopted,
    // so the fallback writeback below may still persist the ADOPTED model.
    var loopModelEpoch = snapshotModelSelectionEpoch()
    AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${agentHistory.size}")
    // [T-generation-run] 每轮生成落账：崩溃后可定位半截流归属哪个会话/模型。
    // 成功时 succeeded()；失败路径不落 FINISH，留 RUNNING 由
    // GenerationRunLifecycle.closeOpen()（runAgentLoop 包裹层）或下次启动扫描收尾。
    val runAnchor = agentHistory.lastOrNull { it.dbMessageId != null }?.dbMessageId.orEmpty()
    val generationRunId = com.openminis.app.agent.GenerationRunLifecycle.start(
        context, activeSessionId, provider.model.displayName, runAnchor,
    )
    // [T-android-mem-probe-trust] Send-path context shape. The existing
    // `messages-shape` probe only runs on session LOAD, so the 2026-08-15
    // log described the session as it was opened, never as it was sent —
    // and the send is where the memory goes. `historySize` alone says
    // nothing about payload: 17 messages carrying a 100 KB tool_result each
    // is a very different request from 1500 short ones. Logged once per
    // agent loop (not per turn) to stay cheap; the walk is O(parts) over
    // already-resident strings.
    runCatching {
        var chars = 0L
        var maxOne = 0
        var toolResults = 0
        var images = 0
        var imageBytes = 0L
        var audioChars = 0L
        var biggestRole = ""
        for (m in agentHistory) {
            var perMsg = m.content.length
            for (p in m.contentParts) {
                when (p) {
                    is AgentContentPart.ToolResult -> {
                        perMsg += p.content.length
                        toolResults++
                        // Inline image bytes never reach the char count, so
                        // track them separately — an image-heavy request is
                        // a different failure shape from a text-heavy one.
                        p.imageData?.let { images++; imageBytes += it.size }
                    }
                    is AgentContentPart.Text -> perMsg += p.text.length
                    is AgentContentPart.ImageData -> { images++; imageBytes += p.data.size }
                    else -> {}
                }
            }
            for (a in m.audioParts) audioChars += a.base64Data.length
            images += m.imageParts.size
            chars += perMsg
            if (perMsg > maxOne) { maxOne = perMsg; biggestRole = m.role.name }
        }
        AppLogger.info(
            ChatViewModel.TAG_STREAM,
            "[CtxShape] historySize=${agentHistory.size} totalChars=$chars " +
                "maxMsgChars=$maxOne maxMsgRole=$biggestRole toolResultParts=$toolResults " +
                "imageParts=$images imageBytes=$imageBytes audioB64Chars=$audioChars " +
                "approxTokens=${chars / 4} " +
                "${com.openminis.app.diagnostics.MemorySnapshot.capture().toLogString()}",
        )
    }
    // [T-android-queued-message-interrupt-on-toolclose] `assistantId` is
    // normally a single message id for the whole agent loop (iOS-parity:
    var assistantId = overrideAssistantId ?: "assistant_${System.currentTimeMillis()}"
    val allToolBlocks = mutableListOf<AssistantBlock>()
    // Per-tool ring of the most recent `accumulated` JSON snapshots emitted
    // by `LLMStreamChunk.ToolInputDelta`. Capped at TOOL_INPUT_CHUNK_RING_MAX
    // entries per tool id so memory stays bounded even on long streams.
    // The preflight validator below drains this on a blocked call so we
    // can reconstruct how the model assembled (or failed to assemble) the
    // args.
    val toolInputChunkRings: MutableMap<String, MutableList<String>> = mutableMapOf()
    // [GH#61/#83/#146] 本轮无法解析的参数尾巴（keyed by toolCall id，每轮清空）。
    val toolCallRawArgs = mutableMapOf<String, String>()
    var accumulatedText = ""
    var lastContextTokens = 0  // updated each turn from API usage
    val activeRun = com.openminis.app.service.ActiveRunContext.current()
    activeRun?.associateSession(activeSessionId)
    activeRun?.attachAssistantMessage(assistantId)
    // T94 fix 2: throttle text-delta UI updates to ~20fps (50ms).
    var lastUiUpdateMs = 0L
    var lastFlushedLen = 0
    // T307: per-delta String += chunk.text on Pixel-class heaps was O(n²)
    // — every SSE chunk allocated a fresh String the size of turnText so
    val pendingChunkSb = StringBuilder()
    // T256 tier 2: per-tool-kind input-delta gates. file_write/file_edit
    // pills churn JSON the user can't read anyway — 1Hz update is plenty;
    // other tools get 5Hz so command/url previews stay legible.
    var lastFileToolInputMs = 0L
    var lastOtherToolInputMs = 0L
    // [T-android-throttle-ladder-converge] Ladder body moved to
    // [StreamingThrottle.ladderFor]. This surface deliberately keeps its
    // FASTER 150ms first tier (vs the flush/parse surfaces' 200ms) — the
    // delta gate only applies to short inputs, where the pill's command/url
    // preview benefits from the lower latency; the rest of the ladder is
    // shared.
    // Fallback state — mirrors iOS streamWithGroupFallback.
    // [T-android-model-switch-midrun-adopt] Both are reassigned at the top of
    // every request when the user picked a different model since the previous
    // one started: the run keeps going, and the next request uses the new
    // model with the fallback chain rebuilt for it.
    var currentProvider = provider
    var remainingFallbacks = fallbackProviders.toMutableList()
    val fallbackReasons = mutableListOf<String>()
    // [port-upstream-1.16][T-b13-empty-fallback] Members that failed with an
    // error this run — the empty-turn walk never picks them again.
    val triedEntryIds = mutableSetOf<String>()

    // Accumulate tool inputs across all turns (so persist includes all, not just current turn)
    val allToolInputs = mutableMapOf<String, String>()
    val goalManager = com.openminis.app.goal.GoalManager(chatRepository)
    val goalSessionId = activeSessionId
    var goalMountedForCurrentRequest = false
    var goalContinuations = 0
    // [T-subagent-background] Bounded nudges for uncollected detached waves.
    var subAgentCollectNudges = 0
    val goalRunRequested = goalExecutionRun
    // [T-perf-ui-batch] Non-text mutations batch through a 200ms window on Dispatchers.Default.
    val uiBatcher = UiUpdateBatcher(viewModelScope, ::updateAssistantMessage)
    uiBatcher.start()
    // [T-android-stream-drop-autocontinue] Per-run budget of silent
    // auto-continuations after a relay-side stream cut (see the
    // turnFinishReason == null branch below).
    var streamDropContinuations = 0

    // Add placeholder assistant message (once). Mark as awaiting so the
    // "Minis is thinking" indicator shows during the initial request gap
    // before the first stream chunk arrives. Mirrors iOS isAwaitingModelResponse.
    // T300: snapshot the user's current thinking level at message
    // creation so the renderer can hide Deep Thinking blocks for
    // turns the user explicitly asked not to surface, even when a
    // forced-reasoning model still streams reasoning_content.
    // turnThinkingLevel 保留在块外：in-loop compact 的 freshAssistantId
    // 分支同样引用它。
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

    // Tracks whether the loop was exited via a `break` (any reason — no
    // tool calls, msgIdx safety, etc.) or fell off the end of the range.
    // Set false by every break path that *isn't* "the model wanted to
    // keep going past MAX_AGENT_TURNS". Without this flag the post-loop
    // tail can't tell the runaway path apart from a normal turn ending,
    // which previously slapped a fake "200 turns hit" error on every
    // ordinary completion.
    var loopExitedNormally = false
    // [T-android-auto-compact-inloop] How many times the in-loop guard has
    // compacted during THIS runAgentLoop. Bounds compact-thrash: once the
    // cap is hit, a still-over-threshold history stops the turn rather than
    // compacting forever. Mirrors iOS maxInLoopCompactions.
    var inLoopCompactions = 0
    // [T-compact-on-context-overflow] How many times THIS runAgentLoop has
    // compacted in response to the provider's own context verdict
    // (LLMError.ContextLengthExceeded) rather than the token estimate.
    // Capped at 1: if the provider still refuses after a compaction, the
    // history isn't the problem (or the model's real window is smaller
    // than configured) and fallback/terminal-error handling takes over.
    var contextOverflowCompactions = 0
    // [T-android-empty-after-toolresult-reminder] One-shot guard for the
    // "<system-reminder> + retry one round" recovery when the server returns
    // an empty response right after a tool result. Fires at most once per
    // runAgentLoop so it can never loop; if the reminder round is also empty
    // we surface a real error instead of a silent blank bubble. Mirrors iOS
    // AIChatViewModel.didInjectEmptyToolReminderThisRun.
    var didInjectEmptyToolReminder = false
    // [port-upstream-1.16][T-android-dsml-leak] Resends spent on a leaked DSML
    // tool call this run; [T-b13-empty-fallback] entries that already came
    // back empty so the empty-turn walk never revisits them.
    var dsmlLeakRetries = 0
    val emptyStreakEntryIds = mutableSetOf<String>()
    // [T-android-readaloud-stop-stale] One-shot per REPLY (not per turn):
    // the first text delta stops any Read Aloud still playing from the
    // previous reply. Scoped outside the turn loop so a tool-loop reply
    // that emits text across several turns doesn't re-fire it and cut off
    // its own speech mid-sentence.
    var didStopStaleReadAloud = false
    // [T-android-loop-stall-guard] Per-run stall accounting (fresh instance
    // per runAgentLoop = the run-start clear); rule 1/3 nudges ride the next
    // request assembly (see applyStallNudge); rule 2 breaks with a report.
    val stallGuard = ChatViewModelStallGuard()
    var stallGuardNudge: String? = null
    // [T-main-session-turn-cap] The runaway guard is user-tunable
    // (Settings → Tool Limits); 200 is only the shipped default. Read once
    // per run so a mid-run settings change can't shift the range end under
    // an in-flight iteration.
    val maxTurns = ToolLimitPrefs.mainSessionMaxTurns()
    // [T-android-queued-message-interrupt-*] Shared by both mid-loop
    // injection sites: switch loop-scope state to the injected bubble.
    fun swapLoopStateToInjectedBubble(newAssistantId: String) {
        assistantId = newAssistantId
        accumulatedText = ""
        allToolBlocks.clear()
        allToolInputs.clear()
        toolInputChunkRings.clear()
        toolCallRawArgs.clear()
        _canResume.value = false
    }
    for (turn in 0 until maxTurns) {
        currentCoroutineContext().ensureActive()
        // Sanitize history before each API call (mirrors iOS pre-API validation)
        sanitizeAgentHistory()
        // [T-work-report-staleness] Per-turn count (retries re-collect the same request
        // and must not count): STALE_REQUEST_LIMIT unrefreshed rounds degrades the snapshot.
        com.openminis.app.tools.AgentReportStore.noteRequestServed(activeSessionId)

        // Context window management: offload large tool outputs in older
        // messages to disk when this model's window threshold is crossed;
        // stubs in agentHistory still tell the model where to file_read.
        // [T-anthropic-context-window] Heuristic-backed contextWindowTokens
        // so offload triggers for heuristic-only 1M Claude/Gemini models.
        // [T-context-window-live-read] Live read per loop turn — a stale
        // snapshot inside a long turn is exactly the iOS fcc22b66 item-3 bug.
        effectiveContextWindowTokens()?.takeIf { it > 0 }?.let { window ->
            offloadContextIfNeeded(
                contextWindow = window,
                lastContextTokens = lastContextTokens,
            )
        }

        // [T-android-auto-compact-inloop] In-loop context guard (iOS
        // f70ac173). checkContextBeforeSend only runs at the SEND entry
        // point, so a single turn fanning out into many tool iterations
        // could blow past the thresholds mid-loop; offload alone can't
        // recover when the bulk is the model's own text. Runs AFTER offload
        // so it judges the post-offload size.
        when (inLoopContextCheck(inLoopCompactions)) {
            ChatViewModel.InLoopContextAction.PROCEED -> {}
            ChatViewModel.InLoopContextAction.COMPACTED -> {
                // The next API call reads the freshly-compacted
                // effectiveAgentHistory automatically — compaction already
                inLoopCompactions++

                // [T-android-inloop-compact-divider-order / GH#235] Seal the
                // bubble this run has been writing into and continue in a
                // FRESH one below the divider.
                val sealedId = assistantId
                val freshAssistantId = "assistant_${System.currentTimeMillis()}_${turn}"
                withContext(Dispatchers.Main) {
                    // Flush whatever the sealed bubble accumulated and stop
                    // it streaming, so it renders as finished history.
                    updateAssistantMessage(
                        sealedId,
                        accumulatedText,
                        false,
                        allToolBlocks,
                        isAwaitingModelResponse = false,
                    )
                    // If compaction fired before the bubble produced
                    // anything, it would render as an empty row stranded
                    // above the divider — drop it. Mirrors the iOS branch.
                    val sealed = _messages.value.firstOrNull { it.id == sealedId }
                    if (sealed != null &&
                        sealed.content.isEmpty() &&
                        sealed.toolBlocks.isEmpty()
                    ) {
                        _messages.value = _messages.value.filterNot { it.id == sealedId }
                        AppLogger.info(
                            ChatViewModel.TAG,
                            "[Compact] in-loop: dropped empty sealed bubble $sealedId",
                        )
                    }
                    _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
                        id = freshAssistantId,
                        role = "assistant",
                        content = "",
                        isStreaming = true,
                        isAwaitingModelResponse = true,
                        thinkingLevel = turnThinkingLevel,
                    ))
                }
                dropStreamingEntry(sealedId)
                // Same loop-scope reset the queued-prompt swap performs, so
                // the new bubble starts empty and buildTurnParts slices only
                // the new turn's blocks.
                assistantId = freshAssistantId
                accumulatedText = ""
                allToolBlocks.clear()
                allToolInputs.clear()
                toolInputChunkRings.clear()
                toolCallRawArgs.clear()
                AppLogger.info(
                    ChatViewModel.TAG,
                    "[Compact] in-loop: sealed $sealedId, continuing in $freshAssistantId below the divider",
                )
                continue
            }
            ChatViewModel.InLoopContextAction.STOP -> {
                // The loop cannot present a modal mid-flight, so stop
                // safely: user-visible notice + resumable, without the
                // turn-limit error overwrite.
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[AutoCompact] stopping turn: context exhausted and compaction cannot recover",
                )
                // [T-android-inloop-stop-thinking-orphan] Finalize the
                // assistant message before leaving the loop.
                withContext(Dispatchers.Main) {
                    drainLiveAssistantUi(assistantId, accumulatedText, allToolBlocks)
                }
                // No persistAssistantTurn here: this guard runs BEFORE the
                // turn body, so nothing new has been produced yet and the
                // per-turn accumulators it would need
                // (turnStartBlockIndex / lastUsage / turnReasoningContent)
                // are not in scope. Everything from previous turns was
                // already persisted by those turns.
                appendSystemInfo(
                    text = context.getString(R.string.vm_context_full_unreduced),
                    iconKind = "compact",
                )
                // [T-android-group-pause-badge-restamp] A LIVE interruption
                // just happened: real entry into the paused state.
                markLiveInterruptionAndArmResume(loopResumeEpoch)
                // Android's equivalent of iOS's `hitTurnLimit = false`: this
                // is a deliberate stop, NOT the runaway-ceiling path, so the
                // post-loop tail must not slap a fake "hit 200 turns" error
                // on it. finalizeAtTurnLimit is skipped; the notice above is
                // the user-visible explanation.
                loopExitedNormally = true
                break
            }
        }

        // Mark where this turn's blocks start in allToolBlocks so we persist
        // only the NEW parts from this turn. Matches iOS's per-turn RawMessage persistence.
        val turnStartedAtMs = System.currentTimeMillis()
        val turnStartBlockIndex = allToolBlocks.size
        // T307: per-delta StringBuilder for the running turn text + the
        // currently-open trailing text block, so GROWTH is amortised. The
        // snapshot is still O(message length) per publish (see
        // `messageTextSnapshot` below). `currentTextBlockSb` mirrors the
        // trailing block's growing content; reset when a new text block
        // opens (after a tool_use / thinking break).
        val turnTextSb = StringBuilder()
        // [T-android-stream-snapshot-helper] The single source for the bubble
        // text published mid-turn: the cross-turn prefix + THIS turn's streamed
        fun messageTextSnapshot(): String =
            if (turnTextSb.isEmpty()) accumulatedText else accumulatedText + turnTextSb.toString()
        // [T-stall-echo-strip] Armed only when the previous attempt stalled
        // mid-stream (see the retry-rollback path); the retried attempt's
        // text deltas route through it before reaching any consumer.
        var stallEchoStripper: com.openminis.app.provider.StallEchoStripper? = null
        var currentTextBlockSb: StringBuilder? = null
        // [T-android-tool-splits-reply-fix] Index (into allToolBlocks) of
        // THIS turn's single text block, used only when the provider's
        // streamed content is monolithic (streamTextIsMonolithic — OpenAI
        // Chat Completions). -1 until the turn's first text delta. The
        // merge scope is ONE streamed response: text arriving after a
        // tool RESULT round-trip belongs to the NEXT agent-loop turn,
        // which is a separate assistant message — so genuine
        // multi-segment turns are unaffected by the merge.
        var turnTextBlockIdx = -1
        // One-shot observability: future endpoints that adopt qwen-style
        // post-tool_calls content chunking show up in the log.
        var loggedPostToolTextMerge = false
        // Materialise the active text block's StringBuilder into its
        // immutable content. Monolithic mode targets the tracked turn
        // text block — which may NOT be the last block once trailing
        // content arrived after tool_calls; ordered mode keeps the
        // original trailing-block behaviour.
        fun materializeActiveTextBlock() {
            val sb = currentTextBlockSb ?: return
            val idx = if (currentProvider.streamTextIsMonolithic) turnTextBlockIdx else allToolBlocks.lastIndex
            if (idx >= 0 && idx < allToolBlocks.size && allToolBlocks[idx].kind == "text") {
                allToolBlocks[idx] = allToolBlocks[idx].copy(content = sb.toString())
            }
        }
        val turnThinking = StringBuilder()
        // Opaque reasoning_content blob captured from the provider's
        // ReasoningContent stream chunk. When set (including empty string),
        // takes precedence over turnThinking concatenation so the exact
        // server-emitted value round-trips on the next request — DeepSeek V4
        // emits "" legitimately and fabricated text would be in-context-learned.
        var turnReasoningBlob: String? = null
        // T321: capture finish_reason from LLMStreamChunk.Finished so we can
        // log it at turn-end alongside the empty-turn warning.
        var turnFinishReason: String? = null
        // [port-upstream-1.16][FIX-261018-12] Anthropic refusal stop_details.
        var turnRefusalCategory: String? = null
        var turnRefusalExplanation: String? = null
        var lastUsage: LLMUsage? = null
        val maxTokens = dynamicMaxTokens(provider, lastContextTokens)
        // [GH#61/#83/#146] New turn → previous turn's unparseable tails are
        // stale; a provider id reused across turns must not inherit one.
        toolCallRawArgs.clear()
        val toolCalls = mutableListOf<Triple<String, String, JSONObject>>() // id, name, args
        // [T-android-gemini3-thoughtsig / #179] toolCallId -> Gemini 3.x
        // thoughtSignature for this turn's calls (null for other providers).
        val toolCallSignatures = mutableMapOf<String, String>()

        // [T-android-duplicate-toolcall-replay] Idempotence for re-emitted
        // ToolCallComplete events. A gateway/SSE replay can deliver the same
        // completed call (same raw id, same name, same args) twice within one
        // stream attempt; the [T-dedupe-toolcallid] renamer turns the second
        val toolReplayGuard = com.openminis.app.provider.ToolReplayGuard()
        val replayRenamedIds = mutableSetOf<String>()

        // [T-dedupe-toolcallid 03fbcbfd] See ToolCallIdDedupe.
        val toolCallIdDedupe = ToolCallIdDedupe()

        // Stream the response — with auto-retry on transient errors, then fallback.
        // callbackFlow wraps throws into CancellationException(cause=LLMError),
        // so we catch at collect level and unwrap.
        var collectDone = false
        var retryAttempt = 0  // per-provider; reset when falling back to the next member
        // [T-stall-resume] Mid-stream stalls (stream was alive, then went quiet
        // past the idle budget) carry the already-streamed text into the retry
        // so the model continues instead of regenerating from scratch. Set in
        // the retry catch when the TransientError carries stalledAfterFirstEvent,
        // consumed by the retried requestHistory, cleared once a stream attempt
        // succeeds (a normal `continue` never reaches the rollback that sets it).
        var stallResumeContext: String? = null
        // [T-android-queued-message-interrupt-thinking] Set by the dedicated
        // catch below; read after the stream loop to inject as a new turn.
        var queuedPromptInterrupted = false
        // [T-android-queued-message-interrupt-thinking] iOS d14174d3 parity:
        // a prompt queued while the model is still reasoning must not wait
        // out the stream — cut it (both branches below call this).
        fun cutForQueuedPrompt(phase: String) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "📨[QueueInterrupt] turn=$turn $phase cut — answering queued prompt directly")
            throw QueuedPromptInterrupt()
        }
        while (!collectDone) {
            // [T-android-model-switch-midrun-adopt] See resolveModelSwitch.
            resolveModelSwitch(loopModelEpoch, currentProvider)?.let { adopted ->
                currentProvider = adopted.provider
                remainingFallbacks = adopted.fallbacks.toMutableList()
                retryAttempt = 0
                loopModelEpoch = adopted.epoch
                // A new model starts a new failure streak.
                emptyStreakEntryIds.clear()
            }
            // [GH#521] Per-attempt phase clocks (see [StreamPhaseReport]).
            val streamPhase = StreamPhaseReport()
            try {
                // [T-android-enhanced-cache] Stamp the per-turn Enhanced
                // Cache flag onto the active provider here — the single
                // choke point every turn passes through, regardless of how
                // currentProvider was (re)assigned by the fallback loop.
                // Non-Anthropic providers ignore it (cast fails silently).
                (currentProvider as? com.openminis.app.provider.anthropic.AnthropicProvider)
                    ?.enhancedCache = _enhancedCacheEnabled.value
                // Route through effectiveAgentHistory() so a populated
                // [_compactSummary] is prepended as a `<context-summary>`
                // user message. Falls through to the raw agentHistory when
                // no compact has happened, so the common path stays zero-copy.
                // A Goal belongs to the session, not only to an explicit /goal
                // resume run — read it every request so ordinary turns carry it.
                val requestGoalState = withContext(Dispatchers.IO) {
                    goalManager.get(goalSessionId)
                }
                val goalMount = com.openminis.app.goal.goalMount(requestGoalState, goalManager::continuationPrompt)
                goalMountedForCurrentRequest = goalMount.canContinue
                // [T-p1-instruction-snapshot] Freeze the instruction values this request is built on.
                commitInstructionSnapshot(
                    com.openminis.app.data.instruction.InstructionStateStore.goalInstructionValues(requestGoalState),
                )
                if (goalRunRequested && !goalMount.canContinue) {
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
                    }
                    loopExitedNormally = true
                    break
                }
                val requestHistory = ToolIdNormalization.normalizeMessages(
                    withGoalAndPlanContext(
                    effectiveAgentHistory(),
                    goalMount.prompt,
                    appendNewUser = goalExecutionRun && turn == 0,
                ).let { history ->
                    // [T-stall-resume] A previous attempt stalled mid-stream:
                    // append the bounded continuation note so this attempt
                    // picks up where it stopped instead of regenerating the
                    // same text. [T-android-loop-stall-guard] The rule 1/3
                    // nudge rides the same one-shot USER-note lane (assembly
                    // artifact, never persisted; cleared on first use — see
                    // applyStallNudge).
                    val withResume = applyStallNudge(history, stallResumeContext)
                    val guardNudge = stallGuardNudge
                    stallGuardNudge = null
                    applyStallNudge(withResume, guardNudge)
                })
                // [T-first-event-watchdog] A hung relay never throws — this stream
                // cancels itself and surfaces a TransientError for the retry chain.
                // [T-routing-strategy-x] First-token budget via GroupRoutingState so the main loop and the group-chat lane agree.
                val firstEventTimeoutMs = GroupRoutingState.resolveFirstEventTimeoutMs(providerRepository,
                    _selectedGroupId.value,
                    (if (currentModelSupportsReasoning) _thinkingLevel.value else ThinkingLevel.OFF).isEnabled)
                // [T-stream-stall-watchdog] Phase 2: silence past twice the first-event
                // budget means the stream stalled, not started slow.
                val streamIdleTimeoutMs = firstEventTimeoutMs * 2
                // [GH#221] 用户头 + OpenCode 会话头走同一通道。
                val requestHeaders = requestExtraHeaders()
                // [GH#563] 每次请求前重刷日期；日期在动态尾部，不扰动缓存前缀。
                val turnSystemPrompt = refreshRuntimeContextDate(
                    systemPrompt,
                    java.time.LocalDate.now().toString(),
                )
                streamPhase.startAttempt()
                currentProvider.streamMessage(
                    applyRequestImageBudget(requestHistory),
                    turnSystemPrompt, dynamicMaxTokens(currentProvider, lastContextTokens),
                    temperature = samplingTemperature(_activeEntryId.value),
                    tools = agentTools,
                    thinkingLevel = if (currentModelSupportsReasoning) _thinkingLevel.value else ThinkingLevel.OFF,
                    systemStablePrefixLen = systemPromptStablePrefixLen,
                    extraHeaders = requestHeaders,
                ).streamStallWatchdog(
                    firstTimeoutMs = firstEventTimeoutMs,
                    idleTimeoutMs = streamIdleTimeoutMs,
                    // [GH#521] headers 之后单独计首 token；否则 15-28s 静默网关会被 2× idle 掩盖。
                    firstContentTimeoutMs = firstEventTimeoutMs,
                    firstContentReason = "connected (stream established) but no first token " +
                        "within ${firstEventTimeoutMs / 1000}s — retrying",
                ).collect { chunk ->
            streamPhase.onChunk(chunk)
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
                    } else {
                        allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(content = turnThinking.toString())
                    }
                    uiBatcher.enqueue(assistantId, messageTextSnapshot(), true, allToolBlocks.toList())
                    if (hasQueuedPromptDuringPureThinking(toolCalls, turnTextSb)) cutForQueuedPrompt("thinking-phase")
                }
                is LLMStreamChunk.Text -> {
                    // [T-android-readaloud-stop-stale] First actual text of
                    // this reply — stop the previous reply's Read Aloud so
                    // old and new audio don't overlap. Deferred to here
                    // rather than fired from send() on purpose: while the
                    // model is still thinking there is nothing to supersede
                    // the old speech with, so it keeps playing until real
                    // new text arrives.
                    if (!didStopStaleReadAloud) {
                        didStopStaleReadAloud = true
                        _stopStaleReadAloud.tryEmit(Unit)
                    }
                    // Mark thinking block as done when text starts flowing
                    val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                    if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                        // [P-process-card] Settle the thinking duration here:
                        // first text after reasoning is the end of the thinking
                        // phase, so now - startTimeMs is what the block ran for.
                        allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(
                            toolStatus = ToolBlockStatus.SUCCESS,
                            durationMs = settleThinkingDurationMs(allToolBlocks[thinkIdx]),
                        )
                    }
                    // [T-stall-echo-strip] Route the delta through the echo
                    // suppressor when the previous attempt stalled mid-stream:
                    // the retried request carried a verbatim tail of the
                    // already-shown text as a continuation seed, and echo-prone
                    // models restate it before continuing. Empty output means
                    // the delta is still aligned inside the seed (probe phase)
                    // — hold every UI-side consumer until it resolves.
                    val strippedDelta = stallEchoStripper?.feed(chunk.text) ?: chunk.text
                    if (strippedDelta.isEmpty() && stallEchoStripper != null) {
                        return@collect
                    }
                    // T307: append-only on the StringBuilder; the snapshot the
                    // overlay reads is taken by `messageTextSnapshot()`.
                    turnTextSb.append(strippedDelta)
                    activeRun?.streamRate?.onTextDelta() // [T-android-usage-tok-s]
                    activeRun?.updateAssistantText(messageTextSnapshot())
                    // Append to the trailing text block — or open a new one if the last
                    // block isn't a text block (i.e. a tool call or thinking was in between).
                    // This preserves the chronological interleaving of text and tool calls
                    // across a single assistant turn. The block's `content` field stays
                    // immutable String — we keep a parallel StringBuilder for the active
                    // block and materialise via .toString() only on flush.
                    val lastIdx = allToolBlocks.lastIndex
                    val monolithic = currentProvider.streamTextIsMonolithic
                    val activeSb = if (monolithic && turnTextBlockIdx >= 0 && currentTextBlockSb != null) {
                        // [T-android-tool-splits-reply-fix] Chat Completions
                        // content is ONE string per response — a content
                        // delta arriving after tool_calls deltas (qwen
                        // chunking artifact) is still part of the same
                        // pre-tool sentence. Merge it back instead of
                        // fabricating a post-tool text block, which split
                        // sentences mid-word in the chat UI. Scope: this
                        // streamed response only (see turnTextBlockIdx).
                        if (!loggedPostToolTextMerge &&
                            allToolBlocks.subList(turnTextBlockIdx + 1, allToolBlocks.size).any { it.kind == "tool_use" }
                        ) {
                            loggedPostToolTextMerge = true
                            AppLogger.info(
                                ChatViewModel.TAG_STREAM,
                                "[T-android-tool-splits-reply-fix] post-tool_calls content delta merged into pre-tool text block (model=${currentProvider.model.id})",
                            )
                        }
                        currentTextBlockSb!!.append(strippedDelta)
                        currentTextBlockSb!!
                    } else if (!monolithic && lastIdx >= 0 && allToolBlocks[lastIdx].kind == "text" && currentTextBlockSb != null) {
                        currentTextBlockSb!!.append(strippedDelta)
                        currentTextBlockSb!!
                    } else {
                        // New text run — either first text after a tool_use/thinking
                        // break, or first text in this turn. Open a fresh block AND
                        // a fresh accumulator. The new block's content carries the
                        // first delta verbatim; subsequent deltas append to the SB.
                        val freshSb = StringBuilder(strippedDelta)
                        currentTextBlockSb = freshSb
                        val block = AssistantBlock(
                            id = "text_${turn}_${allToolBlocks.size}",
                            kind = "text",
                            content = strippedDelta,
                        )
                        if (monolithic) {
                            // Single text block per response. If tool blocks
                            // already arrived (content-after-tool_calls
                            // chunking with no preface text), insert BEFORE
                            // the first tool block of this turn so the
                            // persisted order matches the canonical
                            // {content, tool_calls} message shape.
                            val firstToolIdx = (turnStartBlockIndex until allToolBlocks.size)
                                .firstOrNull { allToolBlocks[it].kind == "tool_use" }
                            if (firstToolIdx != null) {
                                allToolBlocks.add(firstToolIdx, block)
                                turnTextBlockIdx = firstToolIdx
                            } else {
                                allToolBlocks.add(block)
                                turnTextBlockIdx = allToolBlocks.lastIndex
                            }
                        } else {
                            allToolBlocks.add(block)
                        }
                        freshSb
                    }
                    // T94 fix 2 + T256: tiered text-delta throttle. Mutate local
                    // state every delta (above) so block boundaries stay correct
                    // for ToolUseStart / ToolInputDelta which read allToolBlocks
                    // directly. Only push to _messages when the length-aware gate
                    // opens (or a newline lands during a short reply). Pending
                    // text lives in `pendingChunkSb` so the stream-end final
                    // flush at line ~3580 can drain it.
                    pendingChunkSb.append(strippedDelta)
                    val len = turnTextSb.length
                    val unflushed = len - lastFlushedLen
                    val throttle = StreamingThrottle.throttleFor(len, unflushed, firstTierMs = 150L)
                    val newlineFlush = len < 5_000 && chunk.text.contains('\n') && unflushed >= 50
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastUiUpdateMs >= throttle || newlineFlush) {
                        lastUiUpdateMs = nowMs
                        lastFlushedLen = len
                        pendingChunkSb.setLength(0)
                        // Materialise SB → String for the active block's
                        // content (so Compose sees an immutable snapshot); the
                        // message body itself comes from
                        // `messageTextSnapshot()`. Both are O(n) calls but
                        // happen at throttled cadence, not per delta.
                        // (activeSb === currentTextBlockSb by construction.)
                        materializeActiveTextBlock()
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
                        }
                    }
                }
                is LLMStreamChunk.ToolUseStart -> {
                    // [T-dedupe-toolcallid] Rewrite duplicate id ASAP — the
                    // renamed value drives the AssistantBlock.id used by
                    // ToolCallComplete / ToolInputDelta lookups and ends
                    // up as the persisted tool_call_id on the next request.
                    val toolUseId = toolCallIdDedupe.startId(chunk.id)
                    android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolUseStart id=$toolUseId name=${chunk.name}")
                    // Mark thinking block as done when tool use starts
                    val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                    if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                        // [P-process-card] Same settle as the text path: a tool
                        // call after reasoning also ends the thinking phase.
                        allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(
                            toolStatus = ToolBlockStatus.SUCCESS,
                            durationMs = settleThinkingDurationMs(allToolBlocks[thinkIdx]),
                        )
                    }
                    // T154: when the last few text deltas landed inside the 50ms throttle
                    // window, the UI hadn't yet been pushed with the trailing text — and
                    if (turnTextSb.isNotEmpty() && pendingChunkSb.isNotEmpty()) {
                        pendingChunkSb.setLength(0)
                        lastUiUpdateMs = System.currentTimeMillis()
                        lastFlushedLen = turnTextSb.length
                        // T307: pre-tool-use flush also materialises the
                        // active text block + the message snapshot.
                        materializeActiveTextBlock()
                        // [T-android-tool-splits-reply-fix] Ordered mode:
                        // the tool block breaks the text run, so the next
                        // text delta opens a new block. Monolithic mode
                        // keeps the accumulator alive — same-response
                        // content deltas arriving after tool_calls merge
                        // back into the pre-tool text block instead.
                        if (!currentProvider.streamTextIsMonolithic) {
                            currentTextBlockSb = null
                        }
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
                        }
                        yield()
                    }
                    // T256 tier 2: force the next ToolInputDelta to flush
                    // immediately by zeroing both gate timestamps. iOS does the
                    // same in .startToolUse (AIChatViewModel.swift:6075-6116) so
                    // the user sees the pill name/title arrive without waiting
                    // out the 1s/200ms gate.
                    lastFileToolInputMs = 0L
                    lastOtherToolInputMs = 0L
                    // Guard: only add if not already present (prevent duplicate blocks from repeated ToolUseStart)
                    if (allToolBlocks.none { it.id == toolUseId }) {
                        allToolBlocks.add(AssistantBlock(
                            id = toolUseId,
                            kind = "tool_use",
                            toolName = chunk.name,
                            toolStatus = ToolBlockStatus.STREAMING,
                            toolTitle = friendlyToolTitle(chunk.name),
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
                    // Maintain a per-tool ring of the most recent `accumulated`
                    // snapshots so the preflight validator below can dump them
                    // when an empty/invalid call is detected. Cheap (single
                    // append + bounded trim) and lives outside any throttle so
                    // every delta lands here.
                    val ring = toolInputChunkRings.getOrPut(toolInputId) { mutableListOf() }
                    ring.add(chunk.accumulated)
                    if (ring.size > ChatViewModel.TOOL_INPUT_CHUNK_RING_MAX) {
                        // Drop from the front so we keep the most recent N.
                        ring.subList(0, ring.size - ChatViewModel.TOOL_INPUT_CHUNK_RING_MAX).clear()
                    }
                    val idx = allToolBlocks.indexOfFirst { it.id == toolInputId }
                    if (idx >= 0) {
                        val prev = allToolBlocks[idx]
                        // Stream-parse partial JSON (mirrors iOS extractPartialStringValue):
                        //   - pull "tool_title" out early so the pill header updates live
                        val liveTitle = extractToolTitle(
                            chunk.accumulated,
                            fallback = if (prev.toolTitle.isNotEmpty() && prev.toolTitle != prev.toolName) {
                                prev.toolTitle
                            } else {
                                friendlyToolTitle(prev.toolName)
                            },
                        ) ?: friendlyToolTitle(prev.toolName)
                        allToolBlocks[idx] = prev.copy(
                            toolArgs = chunk.accumulated,
                            toolTitle = liveTitle,
                            content = "",
                        )
                        // T256 tier 2: gate UI push by tool kind. file_write/file_edit
                        // pump multi-KB JSON through the SSE — pushing every delta
                        // pegs the UI thread for no readable benefit (the user can't
                        // skim a partial JSON blob anyway). Mirrors iOS
                        // AIChatViewModel.swift:6229-6259 (1s file / 200ms other).
                        // Local state above is mutated unconditionally so when the
                        // gate eventually opens — or ToolCallComplete force-flushes —
                        // the latest accumulated args are pushed.
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
                    // [T-android-duplicate-toolcall-replay] Record the RAW id
                    // sighting BEFORE the renamer runs. An identical re-emission
                    // (same raw id + name + serialized args) is flagged so the
                    // dispatch loop refuses it; a same-id call with different
                    // name/args is the documented gateway parallel case and
                    // still dispatches under its renamed id.
                    // [T-perf-async-db-init] args.toString() can be multi-KB
                    // linear copy — run on Dispatchers.Default.
                    val argsString = StreamChunkJsonHelper.serializeArgs(chunk.args)
                    val isReplayedComplete = toolReplayGuard.registerAndCheckReplay(
                        chunk.id, chunk.name, argsString,
                    )
                    // [T-dedupe-toolcallid] Rewrite duplicate id so the
                    // persisted tool_calls list, the block lookup, and
                    // the downstream tool-result join all key on the
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
                        val providedTitle = chunk.args.optString("tool_title", "").takeIf { it.isNotEmpty() }
                        val title = providedTitle ?: friendlyToolTitle(chunk.name)
                        // PENDING — JSON params fully received, waiting for execution
                        // dispatcher to invoke the tool. executeTool() flips to RUNNING.
                        allToolBlocks[idx] = allToolBlocks[idx].copy(
                            toolStatus = ToolBlockStatus.PENDING,
                            toolTitle = title,
                            toolArgs = chunk.args.toString(),
                            content = "", // Clear ToolInputDelta JSON accumulation before real output arrives
                            // [T-android-gemini3-thoughtsig / #179] Persist the
                            // signature onto the block so buildTurnParts (the DB
                            // path) round-trips it. Preserve any prior value if
                            // this chunk lacked one.
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
                    val effective = lastUsage ?: cur
                    if (effective.latestContextTokens > 0) lastContextTokens = effective.latestContextTokens
                    else if (effective.inputTokens > 0) lastContextTokens = effective.inputTokens +
                        (effective.cacheReadInputTokens ?: 0) + (effective.cacheCreationInputTokens ?: 0)
                    if (lastContextTokens > 0) {
                        _lastTurnContextTokens.value = lastContextTokens
                        // [T-token-usage-calibration] 用真实 usage 校准估算器。
                        val modelKey = currentModel?.id ?: ""
                        if (modelKey.isNotEmpty()) {
                            com.openminis.app.data.TokenUsageCalibration.observe(
                                modelKey,
                                estimated = estimateAgentHistoryTokens(),
                                actual = lastContextTokens,
                            )
                        }
                        // [T-cache-hit-rate] Per-turn cache hit rate for TokenUsageSheet.
                        val inputTotal = chunk.usage.inputTokens + (chunk.usage.cacheReadInputTokens ?: 0) + 
                            (chunk.usage.cacheCreationInputTokens ?: 0)
                        if (inputTotal > 0) {
                            _lastCacheHitRate.value =
                                ((chunk.usage.cacheReadInputTokens ?: 0).toDouble() / inputTotal).coerceIn(0.0, 1.0)
                        }
                    }
                }
                is LLMStreamChunk.ReasoningContent -> {
                    // Opaque reasoning blob (DeepSeek/Kimi reasoning_content) — record
                    // on the last assistant turn so it echoes back on the next request.
                    // Empty strings are preserved (DeepSeek V4 emits "" on non-thinking
                    // turns and we must round-trip exactly that). No live UI surface;
                    // the thinking panel is driven by ThinkingDelta events above.
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
                        if (turnTextSb.isNotEmpty()) turnTextSb.append("\n\n")
                        turnTextSb.append(md)
                        uiBatcher.enqueue(assistantId, messageTextSnapshot(), true, allToolBlocks.toList())
                    }
            }
                }  // end collect
                // [T-stall-echo-strip] Stream finished: resolve any seed-
                // aligned bytes the stripper still holds (and its final echo
                // determination) before the finalize snapshot below —
                // otherwise a short resumed reply would never surface.
                stallEchoStripper?.let { stripper ->
                    val tail = stripper.flush()
                    if (tail.isNotEmpty()) {
                        turnTextSb.append(tail)
                        val blockSb = currentTextBlockSb
                        if (blockSb == null) {
                            // No text block open (the whole attempt was held
                            // in the probe buffer) — open one for the tail.
                            val freshSb = StringBuilder(tail)
                            currentTextBlockSb = freshSb
                            allToolBlocks.add(
                                AssistantBlock(
                                    id = "text_${turn}_${allToolBlocks.size}",
                                    kind = "text",
                                    content = tail,
                                ),
                            )
                        } else {
                            blockSb.append(tail)
                        }
                        pendingChunkSb.append(tail)
                    }
                    stallEchoStripper = null
                }
                // T94 fix 2: flush any text that landed in the throttle
                // window after the last UI tick. The retry-rollback /
                // turn-finalize paths below assume _messages reflects all
                // accumulated text-deltas, so we must not leave the last
                // 0-50ms worth on the floor.
                if (pendingChunkSb.isNotEmpty()) {
                    pendingChunkSb.setLength(0)
                    // T307: also flush the active text block's pending
                    // tail and publish the message snapshot.
                    materializeActiveTextBlock()
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
                    }
                }
                // T256: reset throttle bookkeeping for the next turn so the
                // first delta of the next assistant message fires immediately
                // rather than coalescing against this turn's stale baseline.
                lastFlushedLen = 0
                lastUiUpdateMs = 0L
                lastFileToolInputMs = 0L
                lastOtherToolInputMs = 0L
                collectDone = true
                streamPhase.logSuccess(turn, activeSessionId, currentProvider.model.id)
                com.openminis.app.agent.GenerationRunLifecycle.succeeded(context, generationRunId)
                // [T-stall-resume] The stream attempt succeeded — no resume
                // context should leak into a later turn.
                stallResumeContext = null
                // Stream completed without error — clear any lingering retry UI state.
                if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
                    _autoRetryAttempt.value = 0
                    _autoRetryCountdown.value = 0
                }
                // [T-first-event-watchdog] The operator cancels itself; no cleanup needed.
            } catch (e: QueuedPromptInterrupt) {
                // [T-android-queued-message-interrupt-thinking] Deliberate cut,
                // NOT a provider failure — must precede the generic catch.
                settleInterruptedThinkingTurn(turn, allToolBlocks, generationRunId)
                stallResumeContext = null
                queuedPromptInterrupted = true
                break
            } catch (e: Exception) {
                if (e is CancellationException && e.cause == null) { CancellationDiagnostic.log(provider.model.displayName, activeSessionId, e, _isStreaming.value); throw e }  // [T-cancel-diag] record timeline, real job cancellation
                val actual = unwrapFlowException(e)
                streamPhase.logFailure(turn, actual)
                val isRateLimit = actual is com.openminis.app.data.model.LLMError.RateLimited
                val is5xx = actual is com.openminis.app.data.model.LLMError.ProviderError &&
                    ChatViewModel.HTTP_5XX_STATUS_RE.containsMatchIn(actual.detail)
                val isPermanentCapacity = actual is com.openminis.app.data.model.LLMError.ProviderError &&
                    (actual.detail.contains("[429]") ||
                        com.openminis.app.provider.HttpRetryAfter.isPermanentCapacityBody(actual.detail))
                if (isRateLimit || isPermanentCapacity)
                    GroupRoutingState.markRoutingCooldownFor(
                        _activeEntryId.value,
                        _selectedGroupId.value,
                        actual,
                        providerRepository,
                    )
                // Auto-retry on transient network/5xx/transient errors on the SAME provider
                val isTimeoutConnect = actual is com.openminis.app.data.model.LLMError.Timeout &&
                    actual.phase == com.openminis.app.data.model.LLMError.Timeout.TimeoutPhase.CONNECT
                val isTransient = (actual is com.openminis.app.data.model.LLMError.NetworkError ||
                    actual is com.openminis.app.data.model.LLMError.TransientError ||
                    actual is com.openminis.app.data.model.LLMError.RateLimited ||
                    is5xx || isTimeoutConnect) && !isPermanentCapacity
                // [T-compact-on-context-overflow] Upstream 1.14 compaction
                // robustness: compaction is triggered by the provider's
                if (actual is com.openminis.app.data.model.LLMError.ContextLengthExceeded &&
                    contextOverflowCompactions < 1
                ) {
                    contextOverflowCompactions += 1
                    Log.w(
                        ChatViewModel.TAG,
                        "📦 Context overflow on ${currentProvider.model.displayName} — " +
                            "compacting once and retrying (attempt $contextOverflowCompactions)",
                    )
                    withContext(Dispatchers.Main) {
                        setTransientInlineError(
                            context.getString(
                                com.openminis.app.R.string.vm_context_filling_compacting,
                                contextTokensForPolicy(),
                                effectiveContextWindowTokens() ?: 0,
                            ),
                        )
                    }
                    val compacted = awaitCompaction()
                    withContext(Dispatchers.Main) { clearInlineError() }
                    if (compacted) {
                        // [T-android-inloop-compact-divider-order / GH#235]
                        // Same seal+swap contract as the in-loop COMPACTED
                        // branch: compactAll() tail-appends the "N messages
                        // compacted" divider AFTER the still-streaming
                        // bubble, so continuing to write into that bubble
                        // renders fresh output ABOVE the divider. Seal it,
                        // drop it when empty, and continue in a FRESH
                        // placeholder below the divider.
                        val sealedId = assistantId
                        val freshAssistantId = "assistant_${System.currentTimeMillis()}_${turn}"
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(
                                sealedId,
                                messageTextSnapshot(),
                                false,
                                allToolBlocks,
                                isAwaitingModelResponse = false,
                            )
                            val sealed = _messages.value.firstOrNull { it.id == sealedId }
                            if (sealed != null &&
                                sealed.content.isEmpty() &&
                                sealed.toolBlocks.isEmpty()
                            ) {
                                _messages.value = _messages.value.filterNot { it.id == sealedId }
                                AppLogger.info(
                                    ChatViewModel.TAG,
                                    "[Compact] overflow: dropped empty sealed bubble $sealedId",
                                )
                            }
                            _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
                                id = freshAssistantId,
                                role = "assistant",
                                content = "",
                                isStreaming = true,
                                isAwaitingModelResponse = true,
                                thinkingLevel = turnThinkingLevel,
                            ))
                        }
                        dropStreamingEntry(sealedId)
                        assistantId = freshAssistantId
                        accumulatedText = ""
                        allToolBlocks.clear()
                        allToolInputs.clear()
                        toolInputChunkRings.clear()
                        toolCallRawArgs.clear()
                        // Per-attempt state reset — same list as the
                        // transient-retry path above.
                        turnTextSb.setLength(0)
                        currentTextBlockSb = null
                        turnTextBlockIdx = -1
                        turnThinking.clear()
                        toolCalls.clear()
                        toolCallSignatures.clear()
                        toolReplayGuard.reset()
                        replayRenamedIds.clear()
                        pendingChunkSb.setLength(0)
                        lastUiUpdateMs = 0L
                        lastFlushedLen = 0
                        stallResumeContext = null
                        continue  // retry on same provider with compacted history
                    }
                    // Compaction failed — fall through to fallback / throw.
                }
                // [T-android-model-switch-midrun-adopt] No abandon here: the
                // adoption at the top of the loop already moved onto the
                // user's pick, so this retry goes out on it.
                val maxRetries = effectiveMaxRetries()
                // 429 with another group member: switch endpoints instead of
                // hammering the same key through 1/2/4/8/16s (inside a typical
                // 60s relay window). Same-provider retries remain for network
                // / 5xx, and for 429 when this is the last candidate — at most once.
                val skipSameProviderRetry = (isRateLimit && remainingFallbacks.isNotEmpty()) || isPermanentCapacity
                val sameProviderBudget = if (isRateLimit) minOf(maxRetries, 1) else maxRetries
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
                        // Show the error inline on the streaming assistant message during countdown.
                        // Keeps isStreaming=true so the UI doesn't tear down the streaming state.
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
                    withContext(Dispatchers.Main) {
                        clearInlineError()
                    }
                    // Roll back partial blocks from the failed stream attempt so the retried
                    // stream's deltas don't double-append on top of stale content. Previous
                    // turns (everything before turnStartBlockIndex) are preserved.
                    if (allToolBlocks.size > turnStartBlockIndex) {
                        while (allToolBlocks.size > turnStartBlockIndex) {
                            allToolBlocks.removeAt(allToolBlocks.size - 1)
                        }
                        // [T-android-fallback-text-rewind] Keep this turn's
                        // already-streamed text on screen across the rollback.
                        // `accumulatedText` only folds in `turnTextSb` after the
                        // while loop completes successfully, so passing bare
                        // `accumulatedText` here would visibly rewind everything
                        // the user already read this turn. The next attempt
                        // streams into a fresh `turnTextSb` and re-publishes
                        // `accumulatedText + newTurnText`, so this transient
                        // value is overwritten cleanly (no duplication).
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
                        }
                    }
                    // T307: SB-based per-turn accumulators reset.
                    // [T-stall-resume] Capture the partial text BEFORE the reset:
                    // a mid-stream stall (stalledAfterFirstEvent) carries the
                    // already-produced text into the retried request so the model
                    // continues instead of regenerating from scratch.
                    if ((actual as? com.openminis.app.data.model.LLMError.TransientError)?.stalledAfterFirstEvent == true) {
                        val partial = turnTextSb.toString()
                        stallResumeContext = com.openminis.app.provider.StallResume.note(partial)
                        // [T-stall-echo-strip] Arm the echo suppressor for the
                        // retried attempt. The seed must be the exact bytes the
                        // note embeds — StallResume.tail is the single source.
                        stallEchoStripper = com.openminis.app.provider.StallEchoStripper(
                            com.openminis.app.provider.StallResume.tail(partial),
                        )
                    }
                    turnTextSb.setLength(0)
                    currentTextBlockSb = null
                    // [T-android-tool-splits-reply-fix] The tracked turn
                    // text block was just rolled back with the rest of
                    // this turn's partial blocks.
                    turnTextBlockIdx = -1
                    turnThinking.clear()
                    toolCalls.clear()
                    toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                    // [T-android-duplicate-toolcall-replay] Reset replay
                    // bookkeeping with the dispatch list: the retried attempt
                    // re-streams into an empty toolCalls, so raw-id sightings
                    // from the dead attempt must not refuse the fresh ones.
                    toolReplayGuard.reset()
                    replayRenamedIds.clear()
                    // T94 fix 2 + T256: throttle bookkeeping is per-stream
                    // attempt; reset alongside the partial-block rollback so
                    // the next attempt's first delta fires through immediately
                    // rather than coalescing against stale baselines.
                    pendingChunkSb.setLength(0)
                    lastUiUpdateMs = 0L
                    lastFlushedLen = 0
                    lastFileToolInputMs = 0L
                    lastOtherToolInputMs = 0L
                    continue  // retry on same provider
                }
                // Retries exhausted or non-retryable — proceed to fallback / throw.
                _autoRetryAttempt.value = 0
                _autoRetryCountdown.value = 0
                // [T-android-timeout-while-running] Clear any transient
                // inline error from the prior retry attempts before we
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
                    // [T-android-model-indicator-flash-on-endpoint-retry]
                    // Same-model recovery is a TRANSPARENT retry, not a real
                    val isRealModelChange = next.model.id != currentProvider.model.id
                    fallbackReasons.add("⚠️ ${currentProvider.model.displayName}: $reason")
                    Log.i(ChatViewModel.TAG, "🔀 $reason on ${currentProvider.model.displayName}, switching to ${next.model.displayName} (realModelChange=$isRealModelChange)")
                    currentProvider = next
                    triedEntryIds.add(nextCandidate.entryId)
                    retryAttempt = 0
                    // [T-android-model-switch-midrun-adopt] The run stays on
                    // `next` for the rest of THIS request, but persisting it
                    // when the user picked another model mid-stream would
                    // clobber that pick and leave the adoption at the top of
                    // the loop with nothing to adopt. The old code wrote
                    // unconditionally.
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
                    allToolBlocks.removeAll { it.kind == "info" }
                    allToolBlocks.add(0, AssistantBlock(
                        id = "fallback_info_$turn",
                        kind = "info",
                        content = infoText,
                        toolTitle = "Switched model",
                        toolStatus = ToolBlockStatus.SUCCESS,
                    ))
                    // [T-android-fallback-text-rewind] Same as the retry-
                    // rollback path above: preserve this turn's streamed text
                    // (`turnTextSb`) on screen while we switch providers.
                    // `accumulatedText` hasn't folded it in yet, so bare
                    // `accumulatedText` would rewind the visible reply. The new
                    // provider streams into a fresh `turnTextSb` (reset just
                    // below) and re-publishes `accumulatedText + newTurnText`.
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
                    }
                    // Reset turn state for retry with new provider
                    turnTextSb.setLength(0)
                    currentTextBlockSb = null
                    // [T-android-tool-splits-reply-fix] Fresh stream from a
                    // different provider — and the add(0, info) above
                    // shifted every block index anyway.
                    turnTextBlockIdx = -1
                    turnThinking.clear()
                    toolCalls.clear()
                    toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                    // [T-android-duplicate-toolcall-replay] Reset replay
                    // bookkeeping with the dispatch list: the retried attempt
                    // re-streams into an empty toolCalls, so raw-id sightings
                    // from the dead attempt must not refuse the fresh ones.
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

        // [T-android-queued-message-interrupt-thinking] The thinking-phase
        // cut landed here: skip normal bookkeeping and inject as a standalone
        // turn. Not persisted: a row here would sort AFTER the queued user
        // row and render below the prompt it preceded on every reload.
        if (queuedPromptInterrupted) {
            if (turn == 0) generateSessionTitleIfNeeded()
            val handled = drainQueueIntoStandaloneTurn(assistantId, accumulatedText, allToolBlocks, uiBatcher)
            if (handled != null) {
                swapLoopStateToInjectedBubble(handled)
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
        activeRun?.updateAssistantText(accumulatedText)

        // Build assistant contentParts for history
        val assistantParts = mutableListOf<AgentContentPart>()
        if (turnText.isNotEmpty()) {
            assistantParts.add(AgentContentPart.Text(turnText))
        }
        for ((id, name, args) in toolCalls) {
            // [T-android-gemini3-thoughtsig / #179] Attach the captured Gemini
            // 3.x signature so it round-trips through persistence and replay.
            assistantParts.add(AgentContentPart.ToolUse(id, name, args, thoughtSignature = toolCallSignatures[id]))
        }

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

        appendBoundedHistory(LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = turnText,
            contentParts = assistantParts,
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
                agentHistory.removeAt(agentHistory.size - 1)
                while (allToolBlocks.size > turnStartBlockIndex) allToolBlocks.removeAt(allToolBlocks.size - 1)
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
                        // Resend spent: the empty-turn branch below sees the
                        // cleaned tail (leaked turn already dropped) and
                        // routes normally (reminder / next member / hint).
                    }
                }
            }
        }

        var goalAccountingSucceeded = true
        if (toolCalls.isEmpty() && goalMountedForCurrentRequest) {
            val turnUsageTokens = (lastUsage?.inputTokens ?: 0).toLong() + (lastUsage?.outputTokens ?: 0).toLong()
            goalAccountingSucceeded = runCatching {
                goalManager.recordUsage(
                    goalSessionId,
                    turnUsageTokens,
                    (System.currentTimeMillis() - turnStartedAtMs).coerceAtLeast(0),
                )
            }.onFailure { AppLogger.warning(ChatViewModel.TAG_STREAM, "Goal usage accounting failed: ${it.message}") }.isSuccess
        }

        if (toolCalls.isEmpty() && turnFinishReason != null && turnFinishReason in setOf("stop", "end_turn") &&
            turnText.isNotBlank() && goalAccountingSucceeded && goalRunRequested &&
            goalContinuations < ChatViewModel.MAX_GOAL_CONTINUATIONS &&
            activeRun?.isStopped != true &&
            com.openminis.app.service.SessionActivityTracker.isActive(goalSessionId)
        ) {
            val goal = runCatching { goalManager.get(goalSessionId) }.getOrNull()
            if (goal != null && goalManager.mayContinue(goalSessionId, usefulActivity = true)) {
                val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, allToolInputs)
                val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                val persistedId = persistAssistantTurnForRun(
                    checkNotNull(activeRun) { "Agent loop has no persistence owner" }, turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
                )
                if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
                val steering = goalManager.continuationPrompt(goal)
                appendBoundedHistory(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = steering,
                        contentParts = listOf(AgentContentPart.Text(steering)),
                    ),
                )
                goalContinuations++
                AppLogger.info(ChatViewModel.TAG_STREAM, "Goal auto-continuation $goalContinuations/${ChatViewModel.MAX_GOAL_CONTINUATIONS} (session=$goalSessionId)")
                continue
            }
        }

        // [T-subagent-background] The model is about to end the turn while a
        // detached wave it started still owes a result: lanes in flight, or a
        // finished wave whose reports nobody collected. Ending here is exactly
        // how background work gets abandoned — the user reads a confident final
        // answer while the lanes keep burning tokens and their output dies in
        // the registry. Nudge (bounded by MAX_SUBAGENT_COLLECT_NUDGES) so the
        // model either collects, or tells the user plainly that work is still
        // running, which dispatch id it is, and how to pick it up later.
        if (toolCalls.isEmpty() &&
            subAgentCollectNudges < ChatViewModel.MAX_SUBAGENT_COLLECT_NUDGES &&
            activeRun?.isStopped != true
        ) {
            val batchRegistry = com.openminis.app.tools.SubAgentBatchRegistry
            val pending = runCatching {
                batchRegistry.unfinished(goalSessionId, context)
            }.getOrElse { e ->
                AppLogger.warning(ChatViewModel.TAG_STREAM, "sub-agent registry read failed: ${e.message}")
                emptyList()
            }
            if (pending.isNotEmpty()) {
                val nudge = buildString {
                    append("<system-reminder>\n")
                    append(batchRegistry.renderPending(pending))
                    append('\n')
                    pending.forEach { b ->
                        append("- dispatch ").append(b.id.take(8)).append(": ")
                        append(
                            if (b.complete) {
                                "finished, reports NOT collected yet"
                            } else {
                                "${b.running}/${b.lanes.size} lane(s) still running"
                            },
                        )
                        append(" (").append(b.done).append(" done, ").append(b.failed).append(" failed)\n")
                    }
                    append(
                        "\nDo not end the turn as if nothing had been dispatched. Call check_agent now: " +
                            "op=await with timeout_sec to wait for running lanes, op=collect to read the finished " +
                            "reports, then fold those results into your answer. If a lane genuinely cannot finish " +
                            "in time, say so explicitly — name the dispatch id, state that it is still running, and " +
                            "tell the user it can be collected later with check_agent.",
                    )
                    append("\n</system-reminder>")
                }
                // Same shape as the Goal continuation above: persist this turn's
                // assistant output first, otherwise history would carry two
                // consecutive user messages and providers reject the sequence.
                val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, allToolInputs)
                val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                persistAssistantTurnForRun(
                    checkNotNull(activeRun) { "Agent loop has no persistence owner" }, turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
                )
                if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
                appendBoundedHistory(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = nudge,
                        contentParts = listOf(AgentContentPart.Text(nudge)),
                    ),
                )
                subAgentCollectNudges++
                AppLogger.info(
                    ChatViewModel.TAG_STREAM,
                    "sub-agent collect nudge $subAgentCollectNudges/${ChatViewModel.MAX_SUBAGENT_COLLECT_NUDGES} " +
                        "(session=$goalSessionId, dispatches=${pending.size})",
                )
                continue
            }
        }

        // If no tool calls and no Goal continuation, we're done.
        if (toolCalls.isEmpty()) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop turn=$turn no tool calls → break (finishReason=$turnFinishReason)")
            withContext(Dispatchers.Main) {
                updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
            }
            val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
            val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
            val persistedId = persistAssistantTurnForRun(
                checkNotNull(activeRun) { "Agent loop has no persistence owner" }, turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
            )
            if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
            // [T-error-persist-android] Empty-response hint: the model ended a
            // turn (finish=stop/end_turn) with no visible text anywhere in the
            // reply and no tool blocks — the user just sees a blank bubble.
            // Surface a hint instead. When the context is near full, point at
            // compaction; otherwise suggest retry/switch. setInlineError
            // attaches + persists onto the (empty) assistant row so the hint
            // survives a reload too.
            val hasVisibleContent = accumulatedText.isNotBlank() ||
                allToolBlocks.any { it.kind == "tool_use" || (it.kind == "text" && it.content.isNotBlank()) }

            // [T-android-silent-stream-drop] A turn's stopReason comes ONLY
            // from the SSE terminal event, which always carries a concrete
            // reason. A null therefore means the stream closed WITHOUT one —
            // the connection dropped mid-flight (a mid-flight throw would
            // have gone down the fallback/retry path instead, not here).
            //
            // Previously null was folded into `finishedCleanly`, so a
            // PARTIAL reply — bytes arrived, then the socket died — was
            // persisted silently as if complete. The reply just stopped with
            // no error and no way to retry: the "断流" reports. Mirrors iOS
            // d6604021.
            // Only the PARTIAL case is handled here. An EMPTY turn with a null
            // stopReason keeps falling through to the empty-turn handling
            // below (system-reminder retry, then the empty-response hint),
            // which already covers it well — re-routing it here would lose
            // that recovery.
            if (turnFinishReason == null && hasVisibleContent) {
                // [T-android-stream-drop-autocontinue] Relay-side silent cut: the SSE stream ended with no finish_reason and no [DONE] while content was already on screen. Buffet relays do this
                // routinely (nginx proxy_read_timeout during long thinking silences, upstream caps after big tool-call turns) — the "一瞬间出现4个 Execute Shell 然后中断" pattern is exactly a
                // cut right after the tool-call flush.
                //
                // The partial assistant turn is ALREADY appended to agentHistory (turn boundary above) and ALREADY persisted (persistAssistantTurnForRun just ran), so continuing is
                // safe and lossless: append a continue-reminder and re-enter the loop. The next turn streams into the SAME assistant bubble (accumulatedText/allToolBlocks carry over), so the
                // user sees one continuous reply instead of a banner + a manual retry that regenerates everything. Mirrors the Goal
                // auto-continuation pattern. Budget-limited per run; the
                // banner below only fires once the budget is exhausted.
                if (activeRun?.isStopped != true &&
                    streamDropContinuations < ChatViewModel.MAX_STREAM_DROP_CONTINUATIONS
                ) {
                    streamDropContinuations++
                    AppLogger.warning(
                        ChatViewModel.TAG_STREAM,
                        "stream closed without a finish reason after ${accumulatedText.length} chars — " +
                            "auto-continuation $streamDropContinuations/" +
                            "${ChatViewModel.MAX_STREAM_DROP_CONTINUATIONS} " +
                            "(turn=$turn, model=${provider.model.id}, provider=${provider.name})",
                    )
                    commitStreamDropReminder()
                    continue
                }
                AppLogger.warning(
                    ChatViewModel.TAG_STREAM,
                    "stream closed without a finish reason after ${accumulatedText.length} chars — " +
                        "surfacing as an interrupted reply (turn=$turn)",
                )
                // The budget is exhausted but the turn is NOT over: commit the
                // same reminder the automatic continuations used, so the
                // persisted tail stays resumable. Without it the tail is a
                // plain partial reply, which InterruptedTailDetector reads as a
                // COMPLETED turn — after a reload there is nothing to resume and
                // the banner's Retry can only regenerate and hit the same cut.
                commitStreamDropReminder()
                if (activeRun?.isStopped != true) {
                    withContext(Dispatchers.Main) {
                        setInlineError(
                            context.getString(R.string.chat_error_stream_dropped_partial),
                        )
                    }
                }
                // [T-android-group-pause-badge-restamp] A LIVE interruption
                // just happened: real entry into the paused state.
                markLiveInterruptionAndArmResume(loopResumeEpoch)
                // Deliberate stop, not the runaway ceiling — keep the
                // post-loop tail from adding a fake turn-limit error.
                loopExitedNormally = true
                break
            }

            // [port-upstream-1.16][T-android-model-refusal] The model DECLINED
            // the request (Anthropic stop_reason=refusal: HTTP 200, input
            // billed, empty content; OpenAI content_filter). A refusal is
            // deterministic — no empty-turn nudge and no retry: say so on the
            // reply and let the user rephrase or switch model.
            if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)) {
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
                hasText = EmptyTurnRecovery.turnHasText(turnText, allToolBlocks, turnStartBlockIndex),
                hasToolCall = false,
                stopReason = turnFinishReason,
            )
            if (isEmptyTurn) {
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
                        updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
                    }
                    continue
                }
                // No reminder left and nowhere to go — the empty-response hint.
                val window = effectiveContextWindowTokens()
                val usedCtx = lastUsage?.latestContextTokens ?: 0
                val contextNearFull = window != null && window > 0 && usedCtx > 0 &&
                    usedCtx.toDouble() / window.toDouble() > 0.70
                val hint = when {
                    didInjectEmptyToolReminder ->
                        context.getString(R.string.error_empty_response_after_tool)
                    contextNearFull ->
                        context.getString(R.string.error_empty_response_context_large)
                    else ->
                        context.getString(R.string.error_empty_response_generic)
                }
                if (activeRun?.isStopped != true) {
                    withContext(Dispatchers.Main) { setInlineError(hint) }
                    markLiveInterruptionAndArmResume(loopResumeEpoch)
                }
            }
            // Auto-title after first exchange
            if (turn == 0) generateSessionTitleIfNeeded()
            loopExitedNormally = true
            break
        }
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
            activeSessionId = activeSessionId,
            assistantId = assistantId,
            accumulatedText = accumulatedText,
            turn = turn,
            turnFinishReason = turnFinishReason,
            agentTools = agentTools,
            replayRenamedIds = replayRenamedIds,
            allToolBlocks = allToolBlocks,
            toolInputChunkRings = toolInputChunkRings,
            toolCallRawArgs = toolCallRawArgs,
        )
        val plan = preflightToolTurn(turnCtx, toolCalls)
        val execution = executePlannedToolTurn(turnCtx, plan)

        // ---- Phase 3: assemble resultParts in emission order ----
        val resultParts = mutableListOf<AgentContentPart>()
        for ((id, name, _) in toolCalls) {
            val refused = plan.refusedParts[id]
            val executed = execution.executedParts[id]
            when {
                refused != null -> resultParts.add(refused)
                executed != null -> resultParts.add(executed)
                SubAgentKind.isSpawnTool(name) -> {
                    // A spawn call whose peer result never landed (fan-out
                    // interrupted) still needs a tool_result so the
                    // tool_use/tool_result pairing stays balanced.
                    resultParts.add(AgentContentPart.ToolResult(
                        id = id, name = name,
                        content = "Error: this spawn call's result is missing — the wave was " +
                            "collected without a report for this call. Re-issue the dispatch.",
                        isError = true,
                    ))
                }
            }
        }

        currentCoroutineContext().ensureActive()
        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
        // Update UI with tool statuses. Mark as awaiting the next model
        // response so "Minis is thinking" shows during the network gap
        // between tool results being sent and the next turn's first chunk.
        // Mirrors iOS isAwaitingModelResponse.
        withContext(Dispatchers.Main) {
            updateAssistantMessage(
                assistantId, accumulatedText, true, allToolBlocks,
                isAwaitingModelResponse = true,
            )
        }

        // 持久化本轮 assistant+tools，并回填 agentHistory 的 dbMessageId（compact 边界依赖）。
        val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
        val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
        val assistantDbId = persistAssistantTurnForRunAndBackfill(
            activeRun, turnParts, blockMeta, lastUsage, turnReasoningContent, accumulatedText, agentHistory,
        )
        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }

        // tool_result 作为 user 角色消息持久化（镜像 iOS）。
        val toolResultDbId = persistToolResultMessageForRun(
            checkNotNull(activeRun) { "Agent loop has no persistence owner" },
            resultParts,
            uiMessageId = assistantId,
        )
        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }

        // 工具结果进入历史。
        appendBoundedHistory(LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = resultParts,
            dbMessageId = toolResultDbId,
        ))
        val turnUsageTokens = (lastUsage?.inputTokens ?: 0).toLong() + (lastUsage?.outputTokens ?: 0).toLong()
        val goalUsageResult = if (goalMountedForCurrentRequest) runCatching {
            goalManager.recordUsage(
                goalSessionId,
                turnUsageTokens,
                (System.currentTimeMillis() - turnStartedAtMs).coerceAtLeast(0),
            )
        }.onFailure { AppLogger.warning(ChatViewModel.TAG_STREAM, "Goal usage accounting failed: ${it.message}") }
        else Result.success(null)
        // [T-android-goal-early-exit] Broken usage lookup or terminal /
        // paused state ends the loop. See GoalContextInjection.
        if (com.openminis.app.goal.shouldExitForGoalState(goalUsageResult, goalMountedForCurrentRequest)) {
            withContext(Dispatchers.Main) {
                updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
            }
            loopExitedNormally = true
            break
        }

        // [T-android-loop-stall-guard] Record this dispatched turn after the
        // results are in history and the goal ledger settled, then act on the
        // outcome (see enforceStallGuard): rule 1/3 arms a one-shot nudge for
        // the next request assembly; rule 2 breaks the run with a final
        // report. The break's UI teardown stays loop-local — it is bound to
        // assistantId/accumulatedText/allToolBlocks.
        when (val outcome = enforceStallGuard(stallGuard, turn, toolCalls, turnText, resultParts)) {
            is StallLoopOutcome.Nudge -> stallGuardNudge = outcome.message
            is StallLoopOutcome.BreakWithReport -> {
                accumulatedText = accumulatedText + "\n\n" + outcome.report
                withContext(Dispatchers.Main) {
                    drainLiveAssistantUi(assistantId, accumulatedText, allToolBlocks)
                }
                markLiveInterruptionAndArmResume(loopResumeEpoch)
                loopExitedNormally = true
                break
            }
            StallLoopOutcome.Continue -> Unit
        }

        if (turn == 0) generateSessionTitleIfNeeded()  // auto-title after first exchange

    // [T-android-queued-message-interrupt-on-toolclose] 工具结果边界上的排队消息会打断运行计划。
        // starts a standalone turn. See drainQueueIntoStandaloneTurn.
        if (_promptQueue.value.isNotEmpty()) {
            AppLogger.info(
                ChatViewModel.TAG_STREAM,
                "📨[QueueInterrupt] turn=$turn ${_promptQueue.value.size} queued prompt(s) — interrupting after current tool call to start a standalone turn",
            )
            val handled = drainQueueIntoStandaloneTurn(assistantId, accumulatedText, allToolBlocks, uiBatcher)
            if (handled != null) {
                swapLoopStateToInjectedBubble(handled)
                continue
            }
            // null = empty-after-build / drain rejected; fall through to
            // normal next-turn dispatch so the queue doesn't pin the loop.
        }
    }
    // Two ways to leave the for-loop above:
    //   (a) `break` from the "no tool calls" happy-path → loopExitedNormally=true,
    uiBatcher.flush()
    uiBatcher.stop()
    if (!loopExitedNormally) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "runAgentLoop EXIT — hit turn cap=$maxTurns, finalizing as resumable",
        )
        // [T-generation-run] Not a clean finish — close the ledger so the
        // launch scan reserves ABANDONED for a process that actually died.
        com.openminis.app.agent.GenerationRunLifecycle.failed(context, generationRunId)
        withContext(Dispatchers.Main) {
            finalizeAtTurnLimit(assistantId, accumulatedText, allToolBlocks, loopResumeEpoch)
        }
    } else {
        AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
    }
    // [T-context-ring] Refresh the live token ring after the turn settles.
    refreshContextUsage()
    // [GH#131] Lifecycle hook: one assistant response has settled. Detail is a
    // bounded tail of the reply (hooks get MINIS_HOOK_DETAIL), not the whole
    // transcript; fired after the UI/finalize work so a slow hook cannot delay
    // what the user sees.
    //
    // A cancelled turn (Stop) deliberately does NOT fire: there is no complete
    // reply, and user scripts treat OnResponse as "the answer is final" (notify,
    // sync out, trigger downstream automation). Cancellation normally throws out
    // of the loop before this point; the isActive guard closes the race where it
    // lands between the last suspension and here.
    if (currentCoroutineContext().isActive) {
        runCatching {
            com.openminis.app.agent.lifecycle.LifecycleHooks.fire(
                com.openminis.app.agent.lifecycle.LifecycleHooks.LifecycleEvent.RESPONSE,
                realSessionId.ifEmpty { sessionId },
                accumulatedText.takeLast(500),
            )
        }
    }
    // [T-android-tts-scene-announcements] 收敛后按用户开关播报（默认关，失败全吞在 coordinator 内）。
    runCatching {
        val sink = announcementSink ?: return@runCatching
        com.openminis.app.speech.VoiceAnnouncementCoordinator(sink)
            .onScene(context, com.openminis.app.speech.AnnouncementScene.IDLE_AGENT_DONE)
    }
}
