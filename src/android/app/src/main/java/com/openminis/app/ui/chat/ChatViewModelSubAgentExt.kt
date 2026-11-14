package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.data.repository.requestCredential
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.SubAgentLane
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.tools.SubAgentTokenBudget
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.SubAgentKind
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Per-retry backoff (seconds). Index 0 = wait before attempt 2, etc. */
// [T-subagent-429-selfheal] The ladder used to be {2, 5} — sized for a
// transient blip, not a provider rate-limit window (~60s): three attempts
// spent the retry budget in ten seconds, surfacing 429s in waves as dead lanes.
private val SUBAGENT_BACKOFF_S = intArrayOf(2, 5, 15, 30)

/** Ceiling on one backoff wait, even when Retry-After asks for longer. */
private const val MAX_BACKOFF_S = 60

/**
 * Stagger between lanes of the same wave. Fanning out N requests at the same
 * millisecond against one provider is the cheapest way to manufacture the 429
 * that then kills a lane; a few hundred ms of spread costs nothing.
 */
internal const val SPAWN_STAGGER_MS = 400L

/**
 * Backoff for [attempt] (1-based, the attempt that just failed), never below
 * the provider's own Retry-After and never above [MAX_BACKOFF_S].
 */
internal fun backoffSeconds(attempt: Int, retryAfterSeconds: Int): Int {
    val ladder = SUBAGENT_BACKOFF_S[(attempt - 1).coerceIn(0, SUBAGENT_BACKOFF_S.size - 1)]
    return maxOf(ladder, retryAfterSeconds).coerceAtMost(MAX_BACKOFF_S)
}

/**
 * [T-thinking-ceiling-sync] Session level must never exceed the model
 * ceiling — pure core of the ChatViewModel ceiling-sync flow.
 */
internal fun clampThinkingToCeiling(level: ThinkingLevel, ceiling: ThinkingLevel): ThinkingLevel =
    if (level.rank > ceiling.rank) ceiling else level

internal fun parseThinkingLevel(raw: String?, max: ThinkingLevel): ThinkingLevel {
    val requested = raw?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { ThinkingLevel.valueOf(it.uppercase()) }.getOrNull() }
        ?: max
    return if (requested.rank <= max.rank) requested else max
}

internal suspend fun publishSubAgentUi(trackerId: String, event: com.openminis.app.tools.SubAgentRunner.UiEvent) {
    val tracker = com.openminis.app.service.SubAgentActivityTracker
    when (event) {
        is com.openminis.app.tools.SubAgentRunner.UiEvent.Phase ->
            tracker.setPhase(trackerId, event.label, event.tool)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.Thinking ->
            tracker.appendStream(trackerId, event.stepId, "thinking", event.delta)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.Text ->
            tracker.appendStream(trackerId, event.stepId, "text", event.delta)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.ToolStart ->
            tracker.beginTool(trackerId, event.id, event.name)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.ToolArgs ->
            tracker.updateToolArgs(trackerId, event.id, event.name, event.args)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.ToolRunning ->
            tracker.markToolRunning(trackerId, event.id, event.name, event.args)
        is com.openminis.app.tools.SubAgentRunner.UiEvent.ToolDone ->
            tracker.finishTool(trackerId, event.id, event.name, event.success, event.output)
    }
}

internal suspend fun ChatViewModel.publishRunSubagentLog(
        toolId: String,
        assistantId: String,
        currentText: String,
        toolBlocks: MutableList<AssistantBlock>?,
        log: String,
    ) {
        if (toolId.isEmpty() || assistantId.isEmpty() || toolBlocks == null) return
        synchronized(toolBlocks) {
            val i = toolBlocks.indexOfFirst { it.id == toolId }
            if (i >= 0) {
                toolBlocks[i] = toolBlocks[i].copy(content = log)
            }
        }
        withContext(Dispatchers.Main) {
            updateAssistantMessage(assistantId, currentText, true, toolBlocks.toList())
        }
    }

internal fun subAgentCardId(parentToolId: String, index: Int): String = "$parentToolId#sub-$index"

internal fun currentSubAgentLine(spawn: ChatSubAgentSpawn, index: Int, total: Int, step: String): String =
    buildString {
        val role = spawn.role?.takeIf { it.isNotBlank() }
        if (role != null) append("角色 ").append(role).append(" · ")
        append("类型 ").append(spawn.kind)
        append(" · ").append(index).append('/').append(total)
        if (step.isNotBlank()) append('\n').append(step)
    }

/**
 * GrepSource reads the session workspace directly and needs the live session
 * id, so every seat (sub-agent lanes and the supervisor's worker alike)
 * routes it here ahead of the generic dispatcher; null means "not this tool".
 */
internal fun ChatViewModel.grepSourceToolResult(name: String, argsJson: String): ToolExecutionResult? =
    if (name == com.openminis.app.tools.GrepSourceTool.NAME) {
        com.openminis.app.tools.GrepSourceTool.execute(argsJson, activeSessionId, context)
    } else {
        null
    }

/**
 * One card per sub-agent. A batch shares the parent tool id, so each member
 * gets `$toolId#sub-$index`. A single spawn keeps the parent card and must
 * not publish every live sibling's transcript into it.
 */
internal fun ChatViewModel.subAgentChipsEnabled(): Boolean =
    context.getSharedPreferences(
        com.openminis.app.config.PREF_APPEARANCE,
        android.content.Context.MODE_PRIVATE,
    ).getBoolean(com.openminis.app.ui.settings.KEY_SHOW_SUBAGENT_BAR, true)

internal suspend fun ChatViewModel.publishSubAgentCard(
    parentToolId: String,
    cardIndex: Int?,
    title: String,
    assistantId: String,
    currentText: String,
    toolBlocks: MutableList<AssistantBlock>?,
    log: String,
    status: ToolBlockStatus = ToolBlockStatus.RUNNING,
) {
    // Chip mode owns the sub-agent UI. Do not also inject transcript cards.
    if (subAgentChipsEnabled()) return
    if (parentToolId.isEmpty() || assistantId.isEmpty() || toolBlocks == null) return
    val cardId = if (cardIndex != null) subAgentCardId(parentToolId, cardIndex) else parentToolId
    synchronized(toolBlocks) {
        val i = toolBlocks.indexOfFirst { it.id == cardId }
        if (i >= 0) {
            val existing = toolBlocks[i]
            toolBlocks[i] = existing.copy(
                content = log,
                toolTitle = title.ifBlank { existing.toolTitle },
                toolStatus = if (cardIndex == null) existing.toolStatus else status,
            )
        } else if (cardIndex != null) {
            val parent = toolBlocks.indexOfFirst { it.id == parentToolId }
            var at = if (parent >= 0) parent + 1 else toolBlocks.size
            val prefix = "$parentToolId#sub-"
            while (at < toolBlocks.size && toolBlocks[at].id.startsWith(prefix)) at++
            toolBlocks.add(
                at,
                AssistantBlock(
                    id = cardId,
                    kind = "tool_use",
                    content = log,
                    toolStatus = status,
                    toolTitle = title,
                    toolName = "spawn_agent",
                    startTimeMs = System.currentTimeMillis(),
                ),
            )
        }
    }
    withContext(Dispatchers.Main) {
        updateAssistantMessage(assistantId, currentText, true, toolBlocks.toList())
    }
}

internal suspend fun ChatViewModel.executeRunSubAgent(
        argsJson: String,
        toolId: String = "",
        toolBlocks: MutableList<AssistantBlock>? = null,
        assistantId: String = "",
        currentText: String = "",
        limiter: Semaphore? = null,
        parallelWriters: Int = 1,
        waveIndex: Int = 0,
        waveSize: Int = 1,
        /**
         * [T-subagent-background] Detach the wave: hand the model a dispatch id
         * immediately and let the lanes run on [viewModelScope] while the parent
         * loop keeps thinking. Results are collected later with `check_agent`.
         */
        background: Boolean = false,
    ): ToolExecutionResult {
        if (!multiAgentSettings.enabled.value) {
            return ToolExecutionResult("Multi-agent dispatch is disabled in Settings → Multi-agent.", false)
        }
        // [T-android-subagent-depth-leak] The authoritative test is the coroutine-scoped
        // lane marker (cannot leak, self-repairs). The depth counter is only a stale-state
        // detector — gating on it once bricked the session until app kill after a stopped dispatch.
        if (kotlin.coroutines.coroutineContext[SubAgentLane] != null) {
            return ToolExecutionResult("Error: sub-agents cannot spawn further sub-agents.", false)
        }
        if (subAgentDepth.get() > 0) {
            Log.w(
                ChatViewModel.TAG,
                "sub-agent depth=${subAgentDepth.get()} with no lane marker - resetting stale counter",
            )
            subAgentDepth.set(0)
        }
        val spawns = parseSubAgentBatch(argsJson, com.openminis.app.data.ToolLimitPrefs.subagentMaxTurns())
        if (spawns.isEmpty()) {
            return ToolExecutionResult(
                if (argsJson.isBlank() || !argsJson.trim().startsWith("{"))
                    "Error: invalid spawn_agent arguments"
                else
                    "Error: spawn_agent requires a non-empty tasks array or prompt",
                false,
            )
        }
        // [T-subagent-background] Detach when the caller says so, or when the
        // model asked for it in the arguments. Providers disagree on how a
        // boolean arrives — a real JSON bool from OpenAI-shaped ones, the string
        // "true" from others — so accept both spellings rather than silently
        // ignoring half of them.
        val detached = background || runCatching {
            org.json.JSONObject(argsJson).let { o ->
                o.optBoolean("background", false) ||
                    o.optString("background", "").equals("true", ignoreCase = true)
            }
        }.getOrDefault(false)
        val writersHere = spawns.count { SubAgentKind.canWrite(it.kind) }
        val writerTotal = maxOf(parallelWriters, writersHere)
        val sem = limiter ?: Semaphore(
            MultiAgentSettings.clampConcurrent(multiAgentSettings.maxConcurrent.value),
        )
        // Shared token pool per dispatch (Codex rollout_budget), OPT-IN only:
        // when the model passes token_budget the strictest requested cap
        // governs the whole wave; when it omits the field there is NO budget —
        // lanes run to their turn budgets. A silent default cap would cut
        // long-running workers off mid-task with a "partial report", which the
        // user experiences as lost work.
        val requestedCap = spawns.mapNotNull { it.tokenBudget }.minOrNull()
        val sharedBudget = requestedCap?.let { SubAgentTokenBudget(SubAgentTokenBudget.clamp(it)) }
        // [T-subagent-plan-autolog] A wave used to leave no durable trace unless
        // the model happened to call agent_plan first — so an interrupted
        // dispatch lost both the task list and how far each lane got. File the
        // board here instead of relying on the model to remember. A failed
        // add() must not abort the dispatch: runCatching, board stays
        // best-effort, the wave is the deliverable.
        val ownerSession = subAgentOwnerSid("", realSessionId, sessionId, activeSessionId)
        val autoPlan = spawns.size > 1 || detached
        val planIds: List<String?> = if (autoPlan) {
            spawns.mapIndexed { i, s ->
                runCatching {
                    com.openminis.app.tools.AgentPlanStore.add(
                        ownerSession,
                        context,
                        title = "子代理 ${i + 1}/${spawns.size} · ${s.title.ifBlank { s.kind }}",
                        description = s.prompt.trim().take(200),
                        status = "active",
                    ).id
                }.getOrNull()
            }
        } else {
            spawns.map { null }
        }
        val batch = com.openminis.app.tools.SubAgentBatchRegistry.begin(
            sessionId = ownerSession,
            context = context,
            toolId = toolId,
            assistantId = assistantId,
            background = detached,
            seeds = spawns.mapIndexed { i, s ->
                com.openminis.app.tools.SubAgentBatchRegistry.LaneSeed(
                    index = i + 1,
                    kind = s.kind,
                    title = s.title.ifBlank { s.kind },
                    prompt = s.prompt,
                    planId = planIds[i],
                )
            },
        )
        if (detached) {
            return launchDetachedSubAgentBatch(
                batch = batch,
                ownerSession = ownerSession,
                spawns = spawns,
                toolId = toolId,
                writerTotal = writerTotal,
                sem = sem,
                sharedBudget = sharedBudget,
            )
        }
        if (spawns.size == 1) {
            val total = waveSize.coerceAtLeast(1)
            val index = if (total > 1) waveIndex + 1 else 1
            return try {
                supervisorScope {
                    try {
                        sem.withPermit {
                            runOneSubAgent(
                                spawn = spawns[0],
                                toolId = toolId,
                                toolBlocks = toolBlocks,
                                assistantId = assistantId,
                                currentText = currentText,
                                parallelWriters = writerTotal,
                                index = index,
                                total = total,
                                cardIndex = null,
                                tokenBudget = sharedBudget,
                                batchId = batch.id,
                                batchSession = ownerSession,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        ToolExecutionResult(
                            "Sub-agent failed: ${t.javaClass.simpleName}: ${t.message}",
                            false,
                        )
                    }
                }
            } finally {
                multiAgentSettings.clearSlotThinkingLevels()
            }
        }
        val results = try {
            supervisorScope {
                spawns.mapIndexed { i, spawn ->
                    async {
                        try {
                            sem.withPermit {
                                runOneSubAgent(
                                    spawn = spawn,
                                    toolId = toolId,
                                    toolBlocks = toolBlocks,
                                    assistantId = assistantId,
                                    currentText = currentText,
                                    parallelWriters = writerTotal,
                                    index = i + 1,
                                    total = spawns.size,
                                    cardIndex = i + 1,
                                    tokenBudget = sharedBudget,
                                    batchId = batch.id,
                                    batchSession = ownerSession,
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            ToolExecutionResult(
                                "Sub-agent ${i + 1} failed: ${t.javaClass.simpleName}: ${t.message}",
                                false,
                            )
                        }
                    }
                }.awaitAll()
            }
        } finally {
            multiAgentSettings.clearSlotThinkingLevels()
        }
        val ok = results.count { it.success }
        val body = buildString {
            append("Dispatched ${results.size} sub-agents: $ok ok, ${results.size - ok} failed.\n")
            results.forEachIndexed { i, r ->
                append("\n## 子代理 ${i + 1}/${results.size} (${spawns[i].kind}, ")
                append(if (r.success) "ok" else "fail")
                append(")\n")
                append(r.output.trim())
                append('\n')
            }
        }
        publishRunSubagentLog(
            toolId,
            assistantId,
            currentText,
            toolBlocks,
            "Dispatched ${results.size} sub-agents: $ok ok, ${results.size - ok} failed.",
        )
        return ToolExecutionResult(
            output = body,
            success = results.any { it.success },
            toolTitle = "子代理 ${spawns.size}",
        )
    }

/** [T-subagent-background] Detached wave: parent loop keeps thinking while lanes run on viewModelScope; registry persists lane state so process death surfaces `stopped`, not a silent gap. */
private fun ChatViewModel.launchDetachedSubAgentBatch(
        batch: com.openminis.app.tools.SubAgentBatchRegistry.Batch,
        ownerSession: String,
        spawns: List<ChatSubAgentSpawn>,
        toolId: String,
        writerTotal: Int,
        sem: Semaphore,
        sharedBudget: SubAgentTokenBudget?,
    ): ToolExecutionResult {
        viewModelScope.launch(SupervisorJob() + Dispatchers.Default) {
            try {
                supervisorScope {
                    spawns.mapIndexed { i, spawn ->
                        async {
                            try {
                                sem.withPermit {
                                    runOneSubAgent(
                                        spawn = spawn,
                                        toolId = toolId,
                                        toolBlocks = null,
                                        assistantId = "",
                                        currentText = "",
                                        parallelWriters = writerTotal,
                                        index = i + 1,
                                        total = spawns.size,
                                        cardIndex = null,
                                        tokenBudget = sharedBudget,
                                        batchId = batch.id,
                                        batchSession = ownerSession,
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                // One dead lane must not cancel its siblings.
                                Log.w(
                                    ChatViewModel.TAG,
                                    "background lane ${i + 1}/${spawns.size} failed: ${t.message}",
                                )
                                ToolExecutionResult(
                                    "Sub-agent ${i + 1} failed: ${t.javaClass.simpleName}: ${t.message}",
                                    false,
                                )
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) {
                Log.i(ChatViewModel.TAG, "background dispatch ${batch.id.take(8)} cancelled")
            } finally {
                multiAgentSettings.clearSlotThinkingLevels()
            }
        }
        val summary = spawns.mapIndexed { i, s ->
            "  ${i + 1}. [${s.kind}] ${s.title.ifBlank { s.prompt.trim().take(60) }}"
        }.joinToString("\n")
        return ToolExecutionResult(
            output = "Dispatched ${spawns.size} sub-agent(s) in the BACKGROUND — dispatch_id=${batch.id.take(8)}.\n" +
                "$summary\n\n" +
                "They are running now; this call did NOT wait for them. Continue with other work, then " +
                "call check_agent with dispatch_id=${batch.id.take(8)} — op=status for a snapshot, " +
                "op=await to block until they finish, op=collect for their reports. Each lane is also " +
                "filed on the agent_plan board, so progress survives an interruption.",
            success = true,
            toolTitle = "后台子代理 ${spawns.size}",
        )
    }

/**
 * [T-subagent-plan-autolog] Bookkeeping shell around the lane body. Whatever the
 * inner loop hands back — a report, a budget-exhaustion partial, a final
 * failure — the registry and the plan board learn about it here, so a lane
 * cannot finish without its progress being recorded. Cancellation is recorded
 * as `stopped` and then rethrown.
 */
private suspend fun ChatViewModel.runOneSubAgent(
        spawn: ChatSubAgentSpawn,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>?,
        assistantId: String,
        currentText: String,
        parallelWriters: Int,
        cardIndex: Int? = null,
        index: Int,
        total: Int,
        tokenBudget: SubAgentTokenBudget? = null,
        batchId: String? = null,
        batchSession: String = "",
    ): ToolExecutionResult {
        val ownerSession = subAgentOwnerSid(batchSession, realSessionId, sessionId, activeSessionId)
        val outcome = try {
            runOneSubAgentInner(
                spawn = spawn,
                toolId = toolId,
                toolBlocks = toolBlocks,
                assistantId = assistantId,
                currentText = currentText,
                parallelWriters = parallelWriters,
                cardIndex = cardIndex,
                index = index,
                total = total,
                tokenBudget = tokenBudget,
                batchId = batchId,
                batchSession = ownerSession,
            )
        } catch (e: CancellationException) {
            recordLaneOutcome(ownerSession, batchId, index, null, stopped = true, error = e.message)
            throw e
        }
        recordLaneOutcome(ownerSession, batchId, index, outcome, stopped = false, error = null)
        return outcome
    }

/**
 * Mirror a finished lane onto the registry and its plan item. Best-effort by
 * design: bookkeeping must never turn a completed lane into a failed one.
 */
private fun ChatViewModel.recordLaneOutcome(
        sessionId: String,
        batchId: String?,
        index: Int,
        outcome: ToolExecutionResult?,
        stopped: Boolean,
        error: String?,
    ) {
        if (batchId == null) return
        val registry = com.openminis.app.tools.SubAgentBatchRegistry
        val lane = runCatching { registry.get(sessionId, context, batchId) }
            .getOrNull()?.lanes?.find { it.index == index } ?: return
        val success = outcome?.success == true
        runCatching {
            registry.markFinished(
                sessionId = sessionId, context = context, batchId = batchId, index = index,
                success = success, output = outcome?.output.orEmpty(),
                error = error ?: if (!success && !stopped) outcome?.output?.trim()?.take(300) else null,
                attempts = lane.attempts, stopped = stopped, filesDir = context.filesDir,
            )
        }
        val planId = lane.planId ?: return
        val status = if (success) "done" else "failed"
        runCatching {
            com.openminis.app.tools.AgentPlanStore.update(
                sessionId,
                context,
                planId,
                null,
                outcome?.output?.trim()?.take(200),
                status,
            )
        }
    }

internal fun isUpstreamTruncation(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return "empty response" in m || "connection dropped" in m || "upstream" in m ||
            "eof" in m || "truncat" in m
    }

/**
 * Re-pick a pool entry on a different rate-limit bucket (host + key + model).
 * Same model name on another key is a different bucket and is eligible.
 * Returns null when the pool has no other bucket (caller may stop on 429).
 */
internal fun pickRetryEntry(
        entries: List<ModelEntry>,
        pool: List<String>,
        excludeEntryId: String?,
        excludeGateKey: String?,
        gateKeyOf: (ModelEntry) -> String?,
        seed: Int,
    ): ModelEntry? {
        val pooled = pool.filter { it.isNotBlank() }.mapNotNull { id -> entries.find { it.id == id } }
        val rotated = pooled.filter { candidate ->
            if (excludeEntryId != null && candidate.id == excludeEntryId) return@filter false
            val gk = gateKeyOf(candidate)
            gk.isNullOrBlank() || excludeGateKey.isNullOrBlank() ||
                !com.openminis.app.provider.ProviderKeyGate.sameBucket(gk, excludeGateKey)
        }
        return if (rotated.isNotEmpty()) rotated[Math.floorMod(seed, rotated.size)] else null
    }

internal fun ChatViewModel.providerForModelEntry(entry: ModelEntry): LLMProvider? {
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
        var apiKey = providerRepository.requestCredential(instance)?.first ?: return null // rotation pick; every lane advances the pool
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = kotlinx.coroutines.runBlocking { manager?.validAccessToken() }
                if (freshToken != null && freshToken != apiKey) {
                    providerRepository.saveApiKey(instance.id, freshToken)
                    apiKey = freshToken
                }
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "Sub-agent OAuth refresh failed: ${e.message}")
            }
        }
        return ProviderFactory.create(instance, apiKey, entry.model, context)
    }
