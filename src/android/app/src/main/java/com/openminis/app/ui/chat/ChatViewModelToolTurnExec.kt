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
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolOutputSpill
import com.openminis.app.provider.ToolJsonRepair
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
    val truncatedToolTurn: Boolean,
    val agentTools: List<AgentToolDefinition>,
    val replayRenamedIds: Set<String>,
    val allToolBlocks: MutableList<AssistantBlock>,
    val toolInputChunkRings: MutableMap<String, MutableList<String>>,
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
        // JSON repair (T-tool-json-repair b2c4f8a6): salvage truncated /
        // type-mismatched / typo'd args BEFORE preflight rejects them.
        // Mutates `args` in place; downstream argsStr and preflight see
        // the repaired payload. Mirrors iOS repairToolArgs in
        // AIChatViewModel.swift.
        val repairs = ToolJsonRepair.repair(
            name, args, ctx.toolInputChunkRings[id]?.lastOrNull(), ctx.agentTools,
        )
        if (repairs.isNotEmpty()) {
            AppLogger.warning(
                "ToolPreflight",
                "[ToolRepair] REPAIRED tool=$name id=$id strategies=[${repairs.joinToString(", ")}] " +
                    "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                    "rawTail=<<<${ctx.toolInputChunkRings[id]?.lastOrNull()?.take(500) ?: ""}>>>"
            )
        }
        // [T-truncated-args-visibility #119] Non-null when THIS call's
        // arguments arrived truncated and were auto-closed. Only the
        // truncation strategy means the VALUE was cut short; coercion
        // and fuzzy-name repairs fix the shape of a complete argument.
        // Mirrors iOS truncationRepairTag.
        val truncationRepairTag: String? = repairs.firstOrNull { it.startsWith("truncation+") }

        // [T-truncated-args-visibility #119] Refuse truncated WRITES.
        // Auto-closing an unterminated JSON string is indistinguishable
        // from the model ending `content` there, so a half file lands on
        // disk while UI and tool result both report success. For writes a
        // partial artifact is silent corruption of user data and is worse
        // than no write at all; read-only and shell tools keep the
        // repair-and-run behaviour. Mirrors iOS ConcurrentTools.
        if (truncationRepairTag != null && (name == "file_write" || name == "file_edit")) {
            val path = args.optString("path", "").ifBlank { args.optString("file_path", "") }
            AppLogger.warning(
                "ToolPreflight",
                "[ToolRepair] REFUSED truncated write tool=$name id=$id strategy=$truncationRepairTag path=$path"
            )
            val modelMessage = buildString {
                append("Error: This call was NOT executed. Its argument stream was truncated ")
                append("in transit (repair strategy: $truncationRepairTag), so the `content` ")
                append("your client sent was cut short and would have written an incomplete file")
                if (path.isNotBlank()) append(" to $path")
                append(". Nothing was written to disk — the target file is unchanged.\n\n")
                append("The most likely cause is the response hitting its output-token limit ")
                append("mid-argument. Re-issue this write in smaller pieces: write the first ")
                append("part, then append the rest with follow-up calls, rather than repeating ")
                append("the same oversized call.")
            }
            val refusedIdx = ctx.allToolBlocks.indexOfFirst { it.id == id }
            if (refusedIdx >= 0) {
                val elapsed = System.currentTimeMillis() - ctx.allToolBlocks[refusedIdx].startTimeMs
                ctx.allToolBlocks[refusedIdx] = ctx.allToolBlocks[refusedIdx].copy(
                    toolStatus = ToolBlockStatus.FAILED,
                    content = "Blocked: arguments were truncated in transit",
                    durationMs = elapsed,
                )
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(ctx.assistantId, ctx.accumulatedText, true, ctx.allToolBlocks)
                }
            }
            toolLoopDetector.record(name, parseToolParams(args.toString()),
                result = null, errorMessage = modelMessage, toolCallId = id)
            refusedParts[id] = AgentContentPart.ToolResult(
                id = id, name = name,
                content = modelMessage,
                isError = true,
            )
            ctx.toolInputChunkRings.remove(id)
            continue
        }
        val argsStr = args.toString()
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
        // [T-truncated-tool-call-reject] 流因长度限制被切断（finish_reason=
        // length/max_tokens），此工具调用的参数极可能是半截 JSON。拒执行，
        // 让模型看到失败结果后下一轮重新完整调用。镜像 Eta AGENT_RUNTIME.md。
        if (ctx.truncatedToolTurn) {
            val truncatedMsg = "Error: This tool call was rejected because the " +
                "model output was truncated (finish_reason=${ctx.turnFinishReason}). " +
                "The arguments are likely incomplete. Re-read any relevant " +
                "context and re-issue the call with complete arguments."
            val blockIdxTr = ctx.allToolBlocks.indexOfFirst { it.id == id }
            if (blockIdxTr >= 0) {
                val elapsedTr = System.currentTimeMillis() - ctx.allToolBlocks[blockIdxTr].startTimeMs
                ctx.allToolBlocks[blockIdxTr] = ctx.allToolBlocks[blockIdxTr].copy(
                    toolStatus = ToolBlockStatus.FAILED,
                    content = truncatedMsg,
                    durationMs = elapsedTr,
                )
            }
            toolLoopDetector.record(name, paramsMap,
                result = null, errorMessage = truncatedMsg, toolCallId = id)
            refusedParts[id] = AgentContentPart.ToolResult(
                id = id, name = name,
                content = truncatedMsg,
                isError = true,
            )
            continue
        }

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
            val mergedContent = if (existingContent.length > resultContent.length) existingContent else resultContent
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
            Log.d("ToolChain[VM]", "[turn=${ctx.turn}] block[$blockIdx] status→$finalStatus title=${result.toolTitle} contentLen=${finalContent.length}")
            ctx.allToolBlocks[blockIdx] = ctx.allToolBlocks[blockIdx].copy(
                toolStatus = finalStatus,
                content = finalContent,
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
        outputForLLM + "\n\n<system-reminder>The argument stream for this call was " +
            "truncated in transit and auto-closed by the client (repair strategy: " +
            "$truncationRepairTag) before execution. The arguments actually used may be " +
            "incomplete — verify the result and re-issue the call with complete " +
            "arguments if anything is missing.</system-reminder>"
    } else {
        outputForLLM
    }
    val outputSpilled = ToolOutputSpill.maybeSpill(
        context = context,
        sessionId = ctx.activeSessionId,
        toolName = name,
        toolId = id,
        output = outputForLLMWithNote,
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
    val dispatchToolTitle = try {
        JSONObject(call.argsStr).optString("tool_title", "").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
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
