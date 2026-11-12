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



/** 单个子代理 lane 的执行主循环（拆分自 ChatViewModelSubAgentExt，架构行数上限）。 */

internal suspend fun ChatViewModel.runOneSubAgentInner(
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
        /** [T-subagent-plan-autolog] Wave this lane belongs to, for the registry. */
        batchId: String? = null,
        batchSession: String = "",
    ): ToolExecutionResult {
        val prompt = spawn.prompt
        val role = spawn.role
        val skills = spawn.skills
        val requested = spawn.requestedModel
        val kind = spawn.kind
        // [GH#530] Resolve the preset ONCE: its tool scope can downgrade a
        // requested worker/general-purpose kind to a read-only kind, and the
        // whitelist is enforced on the schema, at dispatch and at execute.
        // Resolved before the write_paths check so a read-only preset is not
        // rejected for "missing write paths" it will never need.
        val agentType = com.openminis.app.tools.SubAgentTypeStore.find(context, role.orEmpty())
        val effectiveKind = com.openminis.app.tools.DispatchAgentsTool.effectiveKind(agentType, kind)
        val writePaths = spawn.writePaths
        val maxTurns = spawn.maxTurns
        val title = spawn.title.ifBlank { context.getString(com.openminis.app.R.string.subagent_fallback_label) } // i/N lives in the chip badge, never here
        // A present-but-all-invalid `write_paths` parses to the poison marker,
        // which WritePathGuard resolves to "deny every write" — correct, but
        // useless to the model, which would see a worker that cannot write
        // anything and no explanation. Treat it as "not supplied" so the
        // actionable error below fires instead of a silent denial at runtime.
        if (SubAgentKind.requiresWritePaths(effectiveKind, parallelWriters) &&
            (writePaths.isEmpty() || com.openminis.app.tools.WritePathGuard.isPoisoned(writePaths))
        ) {
            val msg = "Error: parallel workers must set write_paths to non-overlapping directories, or write_paths=none if this task must not write."
            publishSubAgentCard(
                parentToolId = toolId,
                cardIndex = cardIndex,
                title = title,
                assistantId = assistantId,
                currentText = currentText,
                toolBlocks = toolBlocks,
                log = currentSubAgentLine(spawn, index, total, msg),
                status = ToolBlockStatus.FAILED,
            )
            return ToolExecutionResult(msg, false)
        }
        // [T-subagent-config-race] `config.value` is whatever the StateFlow
        // holds RIGHT NOW. A spawn that lands before the one-shot async config
        // load publishes (cold app start, process recreate after memory
        // pressure) sees empty modelEntries, resolves baseEntry to null and
        // dies with "No model available for sub-agent" — on a device that has
        // models configured. Intermittent by construction: it is a start-up
        // window, not a state, and the retry loop further down never runs
        // because the failure happens before it. Wait for the load, bounded,
        // and only then fall through to the actionable error.
        if (providerRepository.config.value.modelEntries.isEmpty()) {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                providerRepository.configLoaded.first { it }
            }
        }
        val config = providerRepository.config.value
        val pool = MultiAgentSettings.retainLive(
            multiAgentSettings.selectedModelEntryIds.value,
            config.modelEntries.map { it.id }.toSet(),
            multiAgentSettings.maxConcurrent.value,
        )
        // Resolve exact IDs first, then fuzzy-match dirty provider/model labels
        // only within the user's configured pool. Retries may rotate afterwards.
        val selectedEntries = if (pool.isEmpty()) config.modelEntries else
            pool.mapNotNull { id -> config.modelEntries.find { it.id == id } }
        val requestedEntry = requested?.let { modelId ->
            selectedEntries.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
                ?: com.openminis.app.provider.ModelAliasMatcher.resolveBest(
                    modelId,
                    selectedEntries,
                    idOf = { it.model.id },
                    nameOf = { it.model.displayName },
                )
        }
        val pickedId = requestedEntry?.id
            ?: MultiAgentSettings.pickModelId(pool, requested, subAgentRoundRobin.getAndIncrement())
        val baseEntry = requestedEntry ?: pickedId?.let { id ->
            config.modelEntries.find { it.id == id }
        } ?: _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
            ?: return ToolExecutionResult(
            "No model available for sub-agent. Select models under Settings → Multi-agent, or keep the main session model selected.",
            false,
        )
        val requestedThinking = spawn.thinkingLevel ?: parseThinkingLevel(
            multiAgentSettings.slotThinkingLevel((index - 1).coerceAtLeast(0)),
            baseEntry.effectiveMaxThinkingLevel,
        )
        // [T-android-subagent-depth-leak] Claim the depth slot inside a
        // try/finally that covers EVERYTHING after the claim. The lane body has
        // its own try/finally, but between this claim and entering that block
        // sit suspension points (activity-tracker start, lane-id resolution).
        // Stopping a dispatch while it was parked in one of them threw
        // CancellationException before the lane's finally existed, so the
        // decrement never ran: depth stayed above zero for the rest of the
        // ViewModel's life and every later dispatch was refused with
        // "sub-agents cannot spawn further sub-agents" until the app was killed.
        subAgentDepth.incrementAndGet()
        return try {
        val parentSession = subAgentOwnerSid(batchSession, realSessionId, sessionId, activeSessionId)
        val laneId = kotlin.coroutines.coroutineContext[SubAgentLane]?.id
            ?: SubAgentLane.idFor(parentSession, System.nanoTime())
        val trackerId = com.openminis.app.service.SubAgentActivityTracker.start(
            parentSessionId = parentSession,
            parentToolId = toolId,
            title = title,
            role = role,
            model = baseEntry.model.displayName,
            index = index,
            total = total,
            kind = kind,
            turnCap = maxTurns,
        )
        val subAgentJob = kotlinx.coroutines.SupervisorJob(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job])
        com.openminis.app.service.SubAgentActivityTracker.attachJob(trackerId, subAgentJob)
        val steerChannel = com.openminis.app.service.SubAgentActivityTracker.openSteer(trackerId)
        return try {
            withContext(subAgentJob) {
            // [T-subagent-429-selfheal] Fan-out used to fire every lane in the
            // same millisecond, which is the cheapest way to provoke the very
            // rate limit that then killed one of them. Spread the wave.
            if (total > 1 && index > 1) {
                delay(SPAWN_STAGGER_MS * (index - 1) + Random.nextLong(0, 150))
            }
            // Bounded retry with pool-endpoint rotation. Sub-agent failures are
            // mostly transient upstream errors (429 / truncated stream); the main
            // session already retries those, sub-agents used to die on first blip.
            // Attempts budget is user-tunable (Settings → Multi-agent, 1 = no retry).
            val maxAttempts = multiAgentSettings.subagentMaxAttempts.value
            var attempt = 0
            var lastError: Exception? = null
            var lastFailedGateKey: String? = null
            var lastFailedEntryId: String? = null
            while (attempt < maxAttempts) {
                attempt++
                val entry = if (attempt == 1) {
                    baseEntry
                } else {
                    val rotated = pickRetryEntry(
                        entries = config.modelEntries,
                        pool = pool,
                        excludeEntryId = lastFailedEntryId,
                        excludeGateKey = lastFailedGateKey,
                        gateKeyOf = { e -> providerForModelEntry(e)?.callGateKey },
                        seed = subAgentRoundRobin.getAndIncrement(),
                    )
                    if (rotated != null) {
                        rotated
                    } else {
                        // [T-subagent-429-selfheal] "Nothing to rotate to" used
                        // to be treated as "nothing left to try": a 429 on a
                        // single-provider pool returned a final failure at once,
                        // discarding the remaining attempt budget. That is why a
                        // concurrent wave lost exactly one member at random —
                        // whichever lane hit the rate limit first — while its
                        // siblings finished normally. Rotation and retry are
                        // separate questions: with no alternative bucket, wait
                        // out the cooldown on the same entry and keep going.
                        val last = lastError
                        val rateLimited = last is LLMError.RateLimited ||
                            (last as? LLMError)?.isFallbackable == true
                        if (rateLimited && attempt < maxAttempts) {
                            val waitS = backoffSeconds(
                                attempt,
                                (last as? LLMError.RateLimited)?.retryAfterSeconds ?: 0,
                            )
                            Log.i(
                                ChatViewModel.TAG,
                                "Sub-agent 429 lane=$laneId no rotation target; cooling down ${waitS}s on ${baseEntry.model.displayName} (attempt $attempt/$maxAttempts)",
                            )
                            runCatching {
                                com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                                    trackerId, 0, maxTurns, "rate-limited; retry in ${waitS}s",
                                )
                            }
                            com.openminis.app.service.SubAgentEventBus.publish(
                                com.openminis.app.service.SubAgentEvent.RetryScheduled(
                                    runId = trackerId,
                                    parentSessionId = parentSession,
                                    attempt = attempt,
                                    maxAttempts = maxAttempts,
                                    waitMs = waitS * 1000L,
                                    reason = "rate-limit-cooldown",
                                    atMs = System.currentTimeMillis(),
                                ),
                            )
                            delay(waitS * 1000L + Random.nextLong(0, 800))
                        }
                        baseEntry
                    }
                }
                // [T-subagent-plan-autolog] Publish the attempt before it runs:
                // a lane that dies mid-call still shows which model and which
                // attempt it was on, instead of a stale `pending`.
                if (batchId != null) {
                    runCatching {
                        com.openminis.app.tools.SubAgentBatchRegistry.markRunning(
                            parentSession, context, batchId, index, trackerId,
                            entry.model.displayName, attempt,
                        )
                    }
                }
                val provider = providerForModelEntry(entry)
                if (provider == null) {
                    if (attempt >= maxAttempts) {
                        return@withContext ToolExecutionResult(
                            "Failed to create provider for ${entry.model.displayName} (attempt $attempt/$maxAttempts)",
                            false,
                        )
                    }
                    lastFailedEntryId = entry.id
                    continue
                }
                if (attempt > 1) {
                    Log.i(ChatViewModel.TAG, "Sub-agent retry lane=$laneId attempt=$attempt/$maxAttempts model=${entry.model.displayName} prev=${lastError?.message}")
                    runCatching {
                        com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                            trackerId, 0, maxTurns, "retry ${attempt - 1} → ${entry.model.displayName}",
                        )
                    }
                    com.openminis.app.service.SubAgentEventBus.publish(
                        com.openminis.app.service.SubAgentEvent.RetryScheduled(
                            runId = trackerId,
                            parentSessionId = parentSession,
                            attempt = attempt,
                            maxAttempts = maxAttempts,
                            waitMs = 0L,
                            reason = "model-rotation:${entry.model.displayName}",
                            atMs = System.currentTimeMillis(),
                        ),
                    )
                }
                val attemptResult: ToolExecutionResult? = try {
            withContext(SubAgentLane(laneId)) {
            var lastUiMs = 0L
            suspend fun onStep(turn: Int, toolName: String) {
                com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                    trackerId, turn, maxTurns, toolName,
                )
                if (toolName.isNotBlank()) {
                    com.openminis.app.service.SubAgentActivityTracker.appendLog(
                        trackerId,
                        "turn $turn/$maxTurns · $toolName",
                    )
                }
                val now = System.currentTimeMillis()
                val important = toolName.isNotBlank()
                if (!important && now - lastUiMs < 250L) return
                lastUiMs = now
                val step = if (toolName.isNotBlank()) "turn $turn/$maxTurns · $toolName" else "turn $turn/$maxTurns"
                publishSubAgentCard(
                    parentToolId = toolId,
                    cardIndex = cardIndex,
                    title = title,
                    assistantId = assistantId,
                    currentText = currentText,
                    toolBlocks = toolBlocks,
                    log = currentSubAgentLine(spawn, index, total, step),
                )
            }
            // [GH#530] agentType / effectiveKind were resolved at the top of this
            // function (before the write_paths check); reuse them here.
            // [GH#521/#188/#199] Same first-token / idle-stream budget the main
            // loop resolves, so a stalled sub-agent stream is bounded by the
            // watchdog instead of waiting out the 600s transport read timeout.
            val subAgentFirstEventMs = com.openminis.app.data.GroupRoutingState.resolveFirstEventTimeoutMs(
                providerRepository, _selectedGroupId.value, requestedThinking.isEnabled,
            )
            val result = com.openminis.app.tools.WritePathGuard.withPaths(writePaths) {
                SubAgentRunner.run(
                    provider = provider,
                    temperature = entry.overrides.temperature,
                    modelDisplayName = entry.model.displayName,
                    userPrompt = prompt,
                    role = role,
                    roleContext = context,
                    skillsHint = skills,
                    tools = com.openminis.app.tools.DispatchAgentsTool.filterToolsForType(
                        agentType,
                        SubAgentKind.filterTools(
                            effectiveKind,
                            AgentTools.makeAgentTools(
                                supportsImageInput = entry.model.hasImageInput,
                                visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                                    providerRepository, context,
                                ),
                                memoryEnabled = false,
                                subAgentEnabled = false,
                                hasSpeechModel = providerRepository.resolveVoiceInputEntry() != null,
                                // [GH#298] Same web_search master switch.
                                webSearchEnabled = com.openminis.app.tools.WebSearchSettings.toolEnabled(context),
                            ) + AgentTools.makeSubAgentExtraTools(),
                            role,
                            context,
                        ),
                    ),
                    maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192),
                    executeTool = { name, json, callId ->
                        if (SubAgentKind.blocks(effectiveKind, name)) {
                            ToolExecutionResult("Error: $effectiveKind sub-agent cannot use $name.", false)
                        } else if (com.openminis.app.tools.CollabRoles.toolsFor(context, role)?.let { name !in it } == true) {
                            ToolExecutionResult("Error: role $role cannot use $name.", false)
                        } else if (agentType != null &&
                            agentType.toolScope != com.openminis.app.tools.SCOPE_WRITE &&
                            name in com.openminis.app.tools.DispatchAgentsTool.writeToolNames()
                        ) {
                            // [GH#530] Scope is a hard gate, not just a schema hint.
                            ToolExecutionResult("Error: read-only preset ${agentType.name} cannot use $name.", false)
                        } else if (agentType != null && agentType.toolNames.isNotEmpty() && name !in agentType.toolNames) {
                            ToolExecutionResult("Error: type $role cannot use $name.", false)
                        } else {
                            grepSourceToolResult(name, json)
                                // [F1 GH#343] The third parameter is the TOOL CALL
                                // ID, not a session id. This used to receive
                                // `parentSession`, so every parallel lane wrote the
                                // same `<parentSession>.txt` spill file and the last
                                // writer silently won; readers could get another
                                // call's output. A session id is NOT this parameter:
                                // executeTool's gate/workspace identity comes from
                                // ChatViewModel.activeSessionId, so passing
                                // parentSession here only poisoned the spill key.
                                ?: executeTool(name, json, callId)
                        }
                    },
                    onStep = { turn, toolName -> onStep(turn, toolName) },
                    onUi = { event -> publishSubAgentUi(trackerId, event) },
                    kind = effectiveKind,
                    writePaths = writePaths,
                    maxTurns = maxTurns,
                    tokenBudget = tokenBudget,
                    thinkingLevel = requestedThinking,
                    steers = steerChannel,
                    // [GH#221] 子代理 provider 来自 entry，不是当前主模型，因此
                    // 按 entry 的实例解析 OpenCode 会话头 / 用户 KV。
                    extraHeaders = providerExtraHeaders(entry.providerInstanceId),
                    firstEventTimeoutMs = subAgentFirstEventMs,
                    streamIdleTimeoutMs = subAgentFirstEventMs * 2,
                )
            }
            com.openminis.app.service.SubAgentActivityTracker.appendLog(
                trackerId,
                "---\n" + result.output,
            )
            publishSubAgentCard(
                parentToolId = toolId,
                cardIndex = cardIndex,
                title = title,
                assistantId = assistantId,
                currentText = currentText,
                toolBlocks = toolBlocks,
                log = currentSubAgentLine(spawn, index, total, SubAgentRunner.cardStep(result.output)),
                status = if (result.success) ToolBlockStatus.SUCCESS else ToolBlockStatus.FAILED,
            )
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, result.success)
            // [T-event-bus] Parent-session awareness: the coordinator used to
            // receive only the lane's final text — append what the bus
            // observed for this run so turn counts, tool volume and retry
            // storms are visible in the dispatch result itself.
            val lifecycle = com.openminis.app.service.SubAgentEventBus.summaryFor(trackerId)
                ?.let { s -> "\n\n[lifecycle] turns=${s.turns} toolCalls=${s.toolCalls} retries=${s.retries}" }
                .orEmpty()
            result.copy(
                output = result.output + lifecycle,
                toolTitle = title.ifEmpty { "Sub-agent · ${entry.model.displayName}" },
            )
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                lastFailedEntryId = entry.id
                lastFailedGateKey = provider.callGateKey
                val retryable = (e as? LLMError)?.isRetryable ?: isUpstreamTruncation(e)
                Log.w(ChatViewModel.TAG, "Sub-agent lane=$laneId attempt=$attempt/$maxAttempts retryable=$retryable err=${e.message}")
                if (retryable && attempt < maxAttempts) {
                    // Same ladder as the rotation path, and capped: an uncapped
                    // Retry-After could park a lane (holding its concurrency
                    // permit) for minutes while siblings waited on it.
                    val waitS = backoffSeconds(
                        attempt,
                        (e as? LLMError.RateLimited)?.retryAfterSeconds ?: 0,
                    )
                    runCatching {
                        com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                            trackerId, 0, maxTurns, "retry in ${waitS}s (${e.javaClass.simpleName})",
                        )
                    }
                    com.openminis.app.service.SubAgentEventBus.publish(
                        com.openminis.app.service.SubAgentEvent.RetryScheduled(
                            runId = trackerId,
                            parentSessionId = parentSession,
                            attempt = attempt,
                            maxAttempts = maxAttempts,
                            waitMs = waitS * 1000L,
                            reason = e.javaClass.simpleName,
                            atMs = System.currentTimeMillis(),
                        ),
                    )
                    delay(waitS * 1000L + Random.nextLong(0, 800))
                    null
                } else {
                    // Final failure: RETURN, do not throw — an exception escaping
                    // this lane would cancel its fan-out siblings via awaitAll.
                    com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, e.message)
                    return@withContext ToolExecutionResult(
                        "Sub-agent failed after $attempt attempt(s): ${e.message ?: e.javaClass.simpleName}",
                        false,
                    )
                }
            }
            if (attemptResult != null) {
                return@withContext if (attempt > 1) attemptResult.copy(
                    output = "(recovered on attempt $attempt/$maxAttempts via ${entry.model.displayName})\n" + attemptResult.output,
                ) else attemptResult
            }
            }
            val failed = ToolExecutionResult(
                "Sub-agent failed after $maxAttempts attempt(s): ${lastError?.message ?: "unknown error"}",
                false,
            )
            publishSubAgentCard(
                parentToolId = toolId,
                cardIndex = cardIndex,
                title = title,
                assistantId = assistantId,
                currentText = currentText,
                toolBlocks = toolBlocks,
                log = currentSubAgentLine(spawn, index, total, failed.output),
                status = ToolBlockStatus.FAILED,
            )
            failed
            }
        } catch (e: CancellationException) {
            val stoppedByUser = com.openminis.app.service.SubAgentActivityTracker.wasUserStopped(trackerId)
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, e.message)
            if (stoppedByUser) return ToolExecutionResult("Sub-agent stopped by user", false)
            throw e
        } catch (t: Throwable) {
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, t.message)
            val failed = ToolExecutionResult(
                "Sub-agent failed: ${t.javaClass.simpleName}: ${t.message}",
                false,
            )
            publishSubAgentCard(
                parentToolId = toolId,
                cardIndex = cardIndex,
                title = title,
                assistantId = assistantId,
                currentText = currentText,
                toolBlocks = toolBlocks,
                log = currentSubAgentLine(spawn, index, total, failed.output),
                status = ToolBlockStatus.FAILED,
            )
            return failed
        } finally {
            withContext(NonCancellable) {
                runCatching { ExecutionCoordinator.sessionDidTerminate(laneId) }
                subAgentJob.cancel()
            }
            com.openminis.app.service.SubAgentActivityTracker.closeSteer(trackerId, steerChannel)
        }
        } finally {
            subAgentDepth.decrementAndGet()
        }
    }

