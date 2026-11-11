package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.agent.Level
import com.openminis.app.data.ToolLimitPrefs
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.logging.AppLogger
import com.openminis.app.plugins.LocalToolPluginStore
import com.openminis.app.security.ToolAliases
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.ToolOutcome
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.ToolCallLane
import com.openminis.app.tools.ToolCallLanes
import com.openminis.app.tools.ToolCallPreflight
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolOutputSpill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-parallel-tool-calls] Same-turn tool-call execution, extracted from
 * ChatViewModelAgentLoopExt so the agent loop reads as orchestration and the
 * executor lives here.
 *
 * The turn executes in three phases:
 *
 *   1. PREFLIGHT ([preflightToolTurn]) — sequential, emission order: JSON
 *      repair, then the replay / truncated-write / loop-detector /
 *      preflight / truncated-turn refusals, then lane assignment. A refused
 *      call gets its error part and FAILED block here — it never executes.
 *   2. EXECUTE ([executePlannedToolTurn]) — concurrent by lane:
 *      side-effect-free calls ([ToolCallLanes] CONCURRENT) overlap each
 *      other and any barrier; barriers (shell / write / stateful surface /
 *      spawn fan-out) chain strictly in emission order; a concurrent call
 *      positioned after a barrier joins that barrier first. The model's
 *      emission order IS its dependency declaration — a call emitted after
 *      a write may assume the write happened, and reads emitted before a
 *      barrier never delay it (reads have no side effects another call
 *      could observe).
 *   3. ASSEMBLE — back in the agent loop: resultParts are appended in the
 *      order the model emitted the calls, so the transcript matches the
 *      model's intent regardless of completion order.
 *
 * Shared mutable state during phase 2: allToolBlocks index writes and the
 * loop detector are synchronized; every Main-thread UI push uses a toList()
 * snapshot so the UI never iterates a list a lane is mutating.
 */

/** Immutable snapshot of the turn state the executor needs. */
internal class ToolTurnContext(
    val activeSessionId: String,
    val assistantId: String,
    val accumulatedText: String,
    val turn: Int,
    val turnFinishReason: String?,
    val agentTools: List<AgentToolDefinition>,
    val replayRenamedIds: Set<String>,
    val allToolBlocks: MutableList<AssistantBlock>,
    val toolInputChunkRings: MutableMap<String, MutableList<String>>,
    /**
     * [GH#61/#83/#146] Per-call raw `arguments` tail the provider could not
     * parse. Consulted by preflight AFTER the ToolInputDelta ring, so the
     * exact final payload wins over a stale stride-gated delta.
     */
    val toolCallRawArgs: MutableMap<String, String>,
)

/**
 * One tool call that survived phase-1 preflight, carrying everything its
 * execution lane needs. Emission order is preserved by list order; phase 2
 * may reorder EXECUTION (by lane) but never the assembled transcript.
 */
internal data class PlannedToolCall(
    val id: String,
    val name: String,
    /** Canonical dispatch name (aliases resolved) — drives lane assignment. */
    val canonicalName: String,
    val argsStr: String,
    val paramsMap: Map<String, Any?>,
    /** Non-null when this call's args were truncated and auto-closed. */
    val truncationRepairTag: String?,
    val lane: ToolCallLane,
)

/** Phase-1 output: survivors to execute plus per-id refused error parts. */
internal class ToolTurnPlan(
    val plannedCalls: List<PlannedToolCall>,
    val refusedParts: Map<String, AgentContentPart.ToolResult>,
)

/** Phase-2 output: per-id executed parts plus the spawn fan-out results. */
internal class ToolTurnExecution(
    val executedParts: ConcurrentHashMap<String, AgentContentPart.ToolResult>,
    val parallelSubResults: MutableMap<String, ToolExecutionResult>,
)

/**
 * Phase 1: sequential preflight in emission order. Every refusal path
 * synthesizes an error tool_result under the call's id so the
 * tool_use/tool_result pairing stays balanced, and records the block in the
 * loop detector where the serial loop did. Streak semantics are preserved
 * exactly because every check still runs sequentially in emission order
 * before ANY call executes.
 */
internal suspend fun ChatViewModel.preflightToolTurn(
    ctx: ToolTurnContext,
    toolCalls: List<Triple<String, String, JSONObject>>,
): ToolTurnPlan {
    val activeRun = com.openminis.app.service.ActiveRunContext.current()
    val plannedCalls = mutableListOf<PlannedToolCall>()
    val refusedParts = LinkedHashMap<String, AgentContentPart.ToolResult>()
    for ((id, name, args) in toolCalls) {
        currentCoroutineContext().ensureActive()
        activeRun?.associateSession(ctx.activeSessionId)
        activeRun?.setCurrentTool(id, name, args.toString())
        // [GH#61/#83/#146][GH#119] ONE shared preflight layer for every
        // model tool-call execution entry point: JSON repair, invalid
        // argument-stream refusal, truncated-write refusal and truncated-turn
        // refusal are implemented once in [ToolCallPreflight] and called here,
        // in SubAgentRunner and in the group-chat loop. Keeping any of that
        // logic at a call site is what let sub-agents/group chat execute the
        // `{}` payload the provider substituted for an unparseable stream.
        // 中文：参数流修复/拒绝只在共享层实现，三个执行入口不再各写一份。
        // Prefer the provider's exact unparsed tail over the ring's last
        // stride-gated delta: a final fragment may never have been emitted as
        // ToolInputDelta, and repairing a stale prefix can produce a
        // syntactically valid but semantically older argument set.
        val rawArgumentTail = ctx.toolCallRawArgs[id]?.takeIf { it.isNotBlank() }
            ?: ctx.toolInputChunkRings[id]?.lastOrNull()
        val preflight = ToolCallPreflight.finalize(
            toolName = name,
            args = args,
            rawArgumentTail = rawArgumentTail,
            advertisedTools = ctx.agentTools,
            turnFinishReason = ctx.turnFinishReason,
        )
        if (preflight is ToolCallPreflight.Outcome.Rejected) {
            val refusedIdx = ctx.allToolBlocks.indexOfFirst { it.id == id }
            if (refusedIdx >= 0) {
                val elapsed = System.currentTimeMillis() - ctx.allToolBlocks[refusedIdx].startTimeMs
                ctx.allToolBlocks[refusedIdx] = ctx.allToolBlocks[refusedIdx].copy(
                    toolStatus = ToolBlockStatus.FAILED,
                    content = preflight.uiMessage,
                    durationMs = elapsed,
                )
            }
            toolLoopDetector.record(
                name, parseToolParams(args.toString()),
                result = null, errorMessage = preflight.modelMessage, toolCallId = id,
            )
            refusedParts[id] = AgentContentPart.ToolResult(
                id = id, name = name,
                content = preflight.modelMessage,
                isError = true,
            )
            ctx.toolInputChunkRings.remove(id)
            ctx.toolCallRawArgs.remove(id)
            withContext(Dispatchers.Main) {
                updateAssistantMessage(ctx.assistantId, ctx.accumulatedText, true, ctx.allToolBlocks)
            }
            continue
        }
        val truncationRepairTag = (preflight as ToolCallPreflight.Outcome.Ready).truncationRepairTag
        val argsStr = preflight.argsStr
        val paramsMap = parseToolParams(argsStr)
        // [T-android-duplicate-toolcall-replay] Refuse a replayed
        // ToolCallComplete (same raw id + name + serialized args as a call
        // already dispatched this stream attempt). Executing it again would
        // run the tool twice and put two results into the transcript; the
        // refusal synthesizes an error result under the renamed id so
        // tool_use/tool_result pairing stays balanced. Not recorded in the
        // loop detector — a transport replay is not model behavior.
        if (id in ctx.replayRenamedIds) {
            val duplicateMsg = "Error: duplicate tool call ignored. This exact call " +
                "(same id, name, and arguments) was already dispatched earlier in this " +
                "turn and its result is already available above. The duplicate was not " +
                "executed. Continue with the recorded result."
            AppLogger.warning(ChatViewModel.TAG_STREAM, "[ToolReplay] refused duplicate dispatch id=$id name=$name args=${argsStr.take(200)}")
            val dupIdx = ctx.allToolBlocks.indexOfFirst { it.id == id }
            if (dupIdx >= 0) {
                val elapsedDup = System.currentTimeMillis() - ctx.allToolBlocks[dupIdx].startTimeMs
                ctx.allToolBlocks[dupIdx] = ctx.allToolBlocks[dupIdx].copy(
                    toolStatus = ToolBlockStatus.FAILED,
                    content = "Duplicate call ignored (already executed)",
                    durationMs = elapsedDup,
                )
            }
            refusedParts[id] = AgentContentPart.ToolResult(
                id = id, name = name,
                content = duplicateMsg,
                isError = true,
            )
            ctx.toolInputChunkRings.remove(id)
            withContext(Dispatchers.Main) {
                updateAssistantMessage(ctx.assistantId, ctx.accumulatedText, true, ctx.allToolBlocks)
            }
            continue
        }

        // Loop-detector check BEFORE execution. CRITICAL outcomes short-circuit
        // the call: synthesize an error result so the tool_use/tool_result pair
        // stays balanced and the LLM sees the block reason.
        val precheck = toolLoopDetector.check(name, paramsMap)
        if (precheck.level == Level.CRITICAL) {
            val blockedMsg = precheck.message ?: "[LOOP BLOCKED] tool execution blocked"
            Log.w("ToolChain[VM]",
                "[turn=${ctx.turn}] tool BLOCKED by loop detector name=$name msg=$blockedMsg")
            AppLogger.warning("ChatViewModel",
                "tool blocked by loop detector name=$name reason=$blockedMsg")
            val blockIdx = ctx.allToolBlocks.indexOfFirst { it.id == id }
            if (blockIdx >= 0) {
                val elapsed = System.currentTimeMillis() - ctx.allToolBlocks[blockIdx].startTimeMs
                ctx.allToolBlocks[blockIdx] = ctx.allToolBlocks[blockIdx].copy(
                    toolStatus = ToolBlockStatus.FAILED,
                    content = blockedMsg,
                    durationMs = elapsed,
                )
            }
            // Record the blocked attempt so consecutive blocks still
            // count toward the unknown-tool / circuit-breaker windows.
            toolLoopDetector.record(name, paramsMap,
                result = null, errorMessage = blockedMsg, toolCallId = id)
            refusedParts[id] = AgentContentPart.ToolResult(
                id = id, name = name,
                content = blockedMsg,
                isError = true,
            )
            continue
        }

        // Preflight: reject empty / missing-required-field tool calls
        // BEFORE the UI flips to RUNNING and BEFORE executeTool() does
        // any actual work. Mirrors iOS preflightValidateToolCall in
        // AIChatViewModel.swift. Synthesizes a tool_result error so the
        // model can self-correct on the next turn without us spawning
        // shells or touching the filesystem on `{}` args.
        val preflightError = preflightValidateToolCall(name, args, ctx.agentTools)
        if (preflightError != null) {
            val chunkRing: List<String> = ctx.toolInputChunkRings.remove(id) ?: emptyList()
            AppLogger.warning(
                "ToolPreflight",
                "BLOCKED tool=$name id=$id reason=\"$preflightError\" " +
                    "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                    "chunkCount=${chunkRing.size} " +
                    "lastChunk=<<<${chunkRing.lastOrNull()?.take(500) ?: ""}>>>"
            )
            chunkRing.forEachIndexed { i, snap ->
                AppLogger.warning(
                    "ToolPreflight",
                    "  chunk[$i] bytes=${snap.toByteArray(Charsets.UTF_8).size} raw=<<<${snap.take(500)}>>>"
                )
            }
            // English literal — string resource lookup intentionally
            // avoided to keep this commit independent of any in-flight
            // strings.xml refactor in other sessions. Promote to a
            // localized R.string entry in a follow-up if needed.
            val uiMessage = "Blocked invalid tool call"
            val modelMessage = "Error: Tool call rejected before execution. $preflightError The arguments your client sent were empty or missing required fields — re-issue the call with all required parameters filled in. Do not retry with the same empty arguments."
            val blockIdxPre = ctx.allToolBlocks.indexOfFirst { it.id == id }
            if (blockIdxPre >= 0) {
                val elapsedPre = System.currentTimeMillis() - ctx.allToolBlocks[blockIdxPre].startTimeMs
                ctx.allToolBlocks[blockIdxPre] = ctx.allToolBlocks[blockIdxPre].copy(
                    toolStatus = ToolBlockStatus.FAILED,
                    content = uiMessage,
                    durationMs = elapsedPre,
                )
            }
            toolLoopDetector.record(
                toolName = name, params = paramsMap,
                result = null, errorMessage = modelMessage, toolCallId = id
            )
            refusedParts[id] = AgentContentPart.ToolResult(
                id = id, name = name,
                content = modelMessage,
                isError = true,
            )
            withContext(Dispatchers.Main) {
                updateAssistantMessage(ctx.assistantId, ctx.accumulatedText, true, ctx.allToolBlocks)
            }
            continue
        }

        currentCoroutineContext().ensureActive()
        // [T-truncated-tool-call-reject] finish_reason=length/max_tokens 的整轮
        // 拒绝已在共享 preflight（ToolCallPreflight.finalize）里完成，三个执行
        // 入口共用同一判定；此处不再重复检查，避免只有主循环受保护。

        // [T-parallel-tool-calls] Lane assignment. Local command-template
        // plugins materialize into shell commands, so they inherit the
        // barrier lane. Unknown names default to BARRIER (conservative).
        val canonicalName = ToolAliases.canonical(name)
        val isLocalTool = LocalToolPluginStore.find(context, canonicalName) != null
        plannedCalls.add(PlannedToolCall(
            id = id,
            name = name,
            canonicalName = canonicalName,
            argsStr = argsStr,
            paramsMap = paramsMap,
            truncationRepairTag = truncationRepairTag,
            lane = ToolCallLanes.laneFor(canonicalName, isLocalTool),
        ))
    }
    return ToolTurnPlan(plannedCalls, refusedParts)
}

/**
 * Post-execution bookkeeping shared by direct execution and the spawn
 * fan-out: loop-detector record, block finalize, spill, part build. Runs on
 * the executing lane's coroutine, so every allToolBlocks mutation is
 * synchronized and every UI push uses a snapshot.
 */
internal suspend fun ChatViewModel.finalizePlannedCall(
    ctx: ToolTurnContext,
    call: PlannedToolCall,
    result: ToolExecutionResult,
): AgentContentPart.ToolResult {
    val id = call.id
    val name = call.name
    val truncationRepairTag = call.truncationRepairTag
    // Record post-execution. WARNING text is appended to the tool
    // result so the model sees it on its next turn. No block here —
    // CRITICAL only fires from check() and phase 1 already refused.
    val errMsgForDetector = if (!result.success) result.output else null
    val postRecord = synchronized(toolLoopDetector) {
        toolLoopDetector.record(
            toolName = name,
            params = call.paramsMap,
            result = if (result.success) result.output else null,
            errorMessage = errMsgForDetector,
            toolCallId = id,
        )
    }
    val outputForLLM = if (postRecord.level == Level.WARNING && postRecord.message != null) {
        AppLogger.debug("ChatViewModel",
            "appending loop-warning to tool result name=$name key=${postRecord.warningKey}")
        "${result.output}\n\n${postRecord.message}"
    } else {
        result.output
    }

    val activeRun = com.openminis.app.service.ActiveRunContext.current()
    synchronized(ctx.allToolBlocks) {
        val blockIdx = ctx.allToolBlocks.indexOfFirst { it.id == id }
        if (blockIdx >= 0) {
            val elapsed = System.currentTimeMillis() - ctx.allToolBlocks[blockIdx].startTimeMs
            // Keep live-streamed content if it has more data than the truncated result.
            // T263: takeLast(80) was applied uniformly, but it was sized for
            // shell_execute (long stdout streams where the tail is what
            // matters). For tools whose first line carries metadata —
            // file_read's `[path | N bytes | M lines | showing A-B of M]`
            // banner, file_write/file_edit confirmations, memory_* /
            // browser_use structured headers — clipping the head dropped
            // the banner entirely. iOS routes file_read through a
            // dedicated branch (AIChatViewModel.swift:5229) and avoids
            // this; mirror that intent by gating the trim to shell_execute.
            val existingContent = ctx.allToolBlocks[blockIdx].content
            val resultContent = if (name == "shell_execute") {
                result.output.lines().takeLast(80).joinToString("\n")
            } else {
                result.output
            }
            // [T-shell-detail-stale-output GH#339] The streamed preview stops
            // updating at process exit and is capped to its last 50 lines; the
            // old "keep whichever is longer" rule could therefore keep the
            // STALE preview over the final result whenever the preview happened
            // to be longer than the head-trimmed result, leaving the completed
            // card showing an out-of-order tail. For shell_execute the result
            // is authoritative — use it whenever it says anything.
            val mergedContent = when {
                name == "shell_execute" && resultContent.isNotBlank() -> resultContent
                existingContent.length > resultContent.length -> existingContent
                else -> resultContent
            }
            val childPrefix = "$id#sub-"
            val childCount = ctx.allToolBlocks.count { it.id.startsWith(childPrefix) }
            val finalContent = if (childCount > 0) {
                "已分发 $childCount 个子代理，点开各自卡片查看当前运行。"
            } else {
                mergedContent
            }
            // [T-truncated-args-visibility #119] A call built from
            // truncated args must not render as a clean success — that
            // silence is the reported bug. Show it with the same weight
            // as the blocked path. Mirrors iOS ConcurrentTools.
            val finalStatus = when {
                activeRun?.isStopped == true -> ToolBlockStatus.CANCELLED
                result.success && truncationRepairTag != null -> ToolBlockStatus.FAILED
                result.success -> ToolBlockStatus.SUCCESS
                result.timedOut -> ToolBlockStatus.TIMEOUT
                else -> ToolBlockStatus.FAILED
            }
            // [GH#119] Name the truncation and the bytes the tool reported;
            // "success" with no marker is exactly what let a half-written
            // file become the basis for the next steps.
            val truncatedWrittenBytes = if (truncationRepairTag != null) {
                TruncatedArgsNotice.writtenBytes(result.output)
            } else {
                null
            }
            val finalContentWithNotice = if (truncationRepairTag != null) {
                TruncatedArgsNotice.uiLine(
                    truncationRepairTag,
                    truncatedWrittenBytes,
                    TruncatedArgsNotice.argumentBytes(call.argsStr),
                ) + "\n\n" + finalContent
            } else {
                finalContent
            }
            // T-bg-overlay phase 1: tool finished — drop the
            // notification's indeterminate progress bar so the
            // user can tell streaming has paused (LLM step) vs
            // a tool is in flight.
            // [T-overlay-glyph-typed-outcome] Pass the typed
            // outcome so the bg overlay glyph reflects the real
            // SUCCESS / TIMEOUT / FAILED result instead of
            // text-sniffing the stale "Running: foo" status.
            val toolOutcome = when (finalStatus) {
                ToolBlockStatus.SUCCESS -> ToolOutcome.Success
                ToolBlockStatus.TIMEOUT -> ToolOutcome.Timeout
                ToolBlockStatus.FAILED -> ToolOutcome.Error
                else -> ToolOutcome.Unknown
            }
            SessionActivityTracker.clearToolRunning(toolOutcome)
            Log.d("ToolChain[VM]", "[turn=${ctx.turn}] block[$blockIdx] status→$finalStatus title=${result.toolTitle} contentLen=${finalContentWithNotice.length}")
            ctx.allToolBlocks[blockIdx] = ctx.allToolBlocks[blockIdx].copy(
                toolStatus = finalStatus,
                content = finalContentWithNotice,
                toolTitle = result.toolTitle.ifEmpty { ctx.allToolBlocks[blockIdx].toolTitle },
                durationMs = elapsed,
                browserURL = result.pageURL ?: ctx.allToolBlocks[blockIdx].browserURL,
                imageFilePath = result.imageFilePath ?: ctx.allToolBlocks[blockIdx].imageFilePath,
            )
        }
    }
    withContext(Dispatchers.Main) {
        updateAssistantMessage(ctx.assistantId, ctx.accumulatedText, true, ctx.allToolBlocks.toList())
    }

    // [T-truncated-args-visibility #119] Tell the MODEL its own
    // arguments were altered. Writes never reach here (refused in
    // phase 1); this covers the tools we still run repaired, where the
    // model would otherwise assume the args it emitted were the args
    // that ran. Mirrors iOS ConcurrentTools.
    currentCoroutineContext().ensureActive()
    val outputForLLMWithNote = if (truncationRepairTag != null) {
        outputForLLM + "\n\n" + TruncatedArgsNotice.modelNote(
            truncationRepairTag,
            TruncatedArgsNotice.writtenBytes(result.output),
            TruncatedArgsNotice.argumentBytes(call.argsStr),
        )
    } else {
        outputForLLM
    }
    // [T-tool-output-offload-loop GH#343] A read of an existing spill file —
    // `file_read` OR a shell `cat/head/tail … tool-spill/…` — must not be
    // re-spilled (the full text is still over the limit, so the naive call
    // would hand the model another placeholder pointing at a copy). The shared
    // resolver covers every read-back spelling; #343 was reported because this
    // branch only knew `file_read`.
    val spillSourcePath = ToolOutputSpill.sourcePathForTool(call.canonicalName, call.argsStr)
    val outputSpilled = ToolOutputSpill.maybeSpill(
        context = context,
        sessionId = ctx.activeSessionId,
        toolName = name,
        toolId = id,
        output = outputForLLMWithNote,
        sourcePath = spillSourcePath,
    )
    return AgentContentPart.ToolResult(
        id = id,
        name = name,
        content = outputSpilled,
        isError = !result.success,
        imageData = result.imageData,
        imageMimeType = result.imageMimeType,
        imageLinuxPath = result.imageLinuxPath,
    )
}

/** Execute one planned call on its lane coroutine. */
private suspend fun ChatViewModel.runPlannedCall(
    ctx: ToolTurnContext,
    call: PlannedToolCall,
    executedParts: ConcurrentHashMap<String, AgentContentPart.ToolResult>,
) {
    currentCoroutineContext().ensureActive()
    // [T-android-overlay-tool-title] Pull tool_title uniformly from
    // args for ALL tools, at the moment execution actually starts.
    // [P1-17] Routed through the shared extractToolTitle (complete-JSON path
    // first, streaming partial fallback); blank/absent → null, as before.
    val dispatchToolTitle = extractToolTitle(call.argsStr, fallback = null)
    SessionActivityTracker.updateToolStatus(
        status = "Running: ${call.name}",
        toolName = call.name,
        isRunning = true,
        toolTitle = dispatchToolTitle,
    )
    // Flip PENDING → RUNNING right before the execute dispatch so the UI
    // (tool pill spinner) shows the exact moment execution begins.
    synchronized(ctx.allToolBlocks) {
        val preIdx = ctx.allToolBlocks.indexOfFirst { it.id == call.id }
        if (preIdx >= 0 && ctx.allToolBlocks[preIdx].toolStatus == ToolBlockStatus.PENDING) {
            ctx.allToolBlocks[preIdx] = ctx.allToolBlocks[preIdx].copy(toolStatus = ToolBlockStatus.RUNNING)
        }
    }
    withContext(Dispatchers.Main) {
        updateAssistantMessage(ctx.assistantId, ctx.accumulatedText, true, ctx.allToolBlocks.toList())
    }
    Log.d("ToolChain[VM]", "[turn=${ctx.turn}] executeTool START name=${call.name} args=${call.argsStr.take(200)}")
    val result = executeTool(call.name, call.argsStr, call.id, ctx.allToolBlocks, ctx.assistantId, ctx.accumulatedText)
    // [T-known-failure] Feed the outcome back into the failure ledger the dispatch
    // gate reads: a success clears the record, a failure arms it for the next
    // identical call. Fire-and-forget; never affects the result the model sees.
    com.openminis.app.tools.ToolFailureMemory.recordOutcome(
        context, activeSessionId, call.name, call.argsStr, result.success, result.output,
    )
    currentCoroutineContext().ensureActive()
    Log.d("ToolChain[VM]", "[turn=${ctx.turn}] executeTool END name=${call.name} success=${result.success} title=${result.toolTitle} outputLen=${result.output.length} output=${result.output.take(200)}")
    executedParts[call.id] = finalizePlannedCall(ctx, call, result)
}

/**
 * Spawn fan-out as ONE barrier job covering every spawn call of the turn —
 * same semantics as the old inline fan-out (peers run concurrently under the
 * multi-agent semaphore, the barrier awaits them all), then each peer's
 * result goes through the shared finalize path so spawn cards are
 * indistinguishable from serial ones.
 */
private suspend fun ChatViewModel.fanOutSpawnCalls(
    ctx: ToolTurnContext,
    plannedCalls: List<PlannedToolCall>,
    execution: ToolTurnExecution,
) {
    val cap = MultiAgentSettings.clampConcurrent(multiAgentSettings.maxConcurrent.value)
    val sem = Semaphore(cap)
    val peers = plannedCalls.filter { SubAgentKind.isSpawnTool(it.name) }
    val writerCount = peers.sumOf { peer ->
        parseSubAgentBatch(peer.argsStr, ToolLimitPrefs.subagentMaxTurns())
            .count { spawn -> SubAgentKind.canWrite(spawn.kind) }
    }
    coroutineScope {
        peers.mapIndexed { peerIdx, peer ->
            async {
                val peerResult = executeRunSubAgent(
                    peer.argsStr,
                    peer.id,
                    ctx.allToolBlocks,
                    ctx.assistantId,
                    ctx.accumulatedText,
                    limiter = sem,
                    parallelWriters = writerCount,
                    waveIndex = peerIdx,
                    waveSize = peers.size,
                )
                synchronized(execution.parallelSubResults) {
                    execution.parallelSubResults[peer.id] = peerResult
                }
            }
        }.awaitAll()
    }
    for (peer in peers) {
        val peerResult = execution.parallelSubResults[peer.id] ?: continue
        execution.executedParts[peer.id] = finalizePlannedCall(ctx, peer, peerResult)
    }
}

/**
 * Phase 2: execute the plan by lane. Returns per-id executed parts; the
 * agent loop assembles the transcript in emission order (phase 3).
 */
internal suspend fun ChatViewModel.executePlannedToolTurn(
    ctx: ToolTurnContext,
    plan: ToolTurnPlan,
): ToolTurnExecution {
    val execution = ToolTurnExecution(
        executedParts = ConcurrentHashMap(),
        parallelSubResults = mutableMapOf(),
    )
    if (plan.plannedCalls.isEmpty()) return execution
    val laneSemaphore = Semaphore(ToolLimitPrefs.toolCallConcurrency())
    coroutineScope {
        var barrierChain: Job? = null
        var spawnFanOutLaunched = false
        for (call in plan.plannedCalls) {
            if (SubAgentKind.isSpawnTool(call.name)) {
                if (!spawnFanOutLaunched) {
                    spawnFanOutLaunched = true
                    val prev = barrierChain
                    barrierChain = launch {
                        prev?.join()
                        fanOutSpawnCalls(ctx, plan.plannedCalls, execution)
                    }
                }
                continue
            }
            if (call.lane == ToolCallLane.BARRIER) {
                val prev = barrierChain
                barrierChain = launch { prev?.join(); runPlannedCall(ctx, call, execution.executedParts) }
            } else {
                // Concurrent read: wait for earlier barriers, then take a
                // semaphore permit. Joining BEFORE acquiring prevents a permit
                // from being parked while waiting on a barrier.
                val prev = barrierChain
                launch {
                    prev?.join()
                    laneSemaphore.withPermit { runPlannedCall(ctx, call, execution.executedParts) }
                }
            }
        }
    }
    return execution
}
