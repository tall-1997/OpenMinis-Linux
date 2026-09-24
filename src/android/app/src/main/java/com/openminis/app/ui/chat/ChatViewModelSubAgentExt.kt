package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.PlanDiscussionOrchestrator
import com.openminis.app.tools.SubAgentLane
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.SubAgentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Per-retry backoff (seconds). Index 0 = wait before attempt 2, etc. */
private val SUBAGENT_BACKOFF_S = intArrayOf(2, 5)

internal suspend fun ChatViewModel.runPlanDiscussion(provider: LLMProvider): String {
        val assistantId = java.util.UUID.randomUUID().toString()
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value + ChatMessage(
                id = assistantId,
                role = "assistant",
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
            )
        }
        val userText = _messages.value.lastOrNull { it.role == "user" && !it.isQueued }?.content.orEmpty()
        val excerpt = _messages.value.takeLast(16).joinToString("\n") {
            "${it.role}: ${it.content.take(500)}"
        }
        val config = providerRepository.config.value
        val mainEntry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
        val mainName = mainEntry?.model?.displayName ?: currentModel?.displayName ?: "main"
        val mainMax = (mainEntry?.model?.maxOutputTokens ?: currentModel?.maxOutputTokens ?: 4096).coerceIn(256, 8192)
        val mainMember = PlanDiscussionOrchestrator.Member(mainName, "facilitator", provider, mainMax)
        val stances = listOf("architect", "skeptic", "implementer", "operator")
        val pool = MultiAgentSettings.retainLive(
            multiAgentSettings.selectedModelEntryIds.value,
            config.modelEntries.map { it.id }.toSet(),
            multiAgentSettings.maxConcurrent.value,
        )
        val members = mutableListOf<PlanDiscussionOrchestrator.Member>()
        if (pool.isNotEmpty()) {
            pool.forEachIndexed { i, id ->
                val entry = config.modelEntries.find { it.id == id } ?: return@forEachIndexed
                val p = providerForModelEntry(entry) ?: return@forEachIndexed
                members += PlanDiscussionOrchestrator.Member(
                    displayName = entry.model.displayName,
                    stance = stances[i % stances.size],
                    provider = p,
                    maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192),
                )
            }
        }
        if (members.isEmpty()) {
            members += mainMember.copy(stance = "architect")
            members += mainMember.copy(displayName = "$mainName · critic", stance = "skeptic")
            members += mainMember.copy(displayName = "$mainName · implementer", stance = "implementer")
        }
        val tools = AgentTools.makeAgentTools(
            supportsImageInput = currentModel?.hasImageInput == true,
            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = false,
            subAgentEnabled = false,
        )
        val result = try {
            PlanDiscussionOrchestrator.run(
                userText = userText,
                conversationExcerpt = excerpt,
                main = mainMember,
                members = members,
                tools = tools,
                executeTool = { name, json ->
                    executeTool(name, json, "", mutableListOf(), assistantId, "")
                },
                onProgress = { msg ->
                    withContext(Dispatchers.Main) {
                        val overlay = msg.lineSequence()
                            .firstOrNull { it.startsWith("**状态：**") }
                            ?.removePrefix("**状态：**")
                            ?.trim()
                            ?: "计划讨论"
                        SessionActivityTracker.updateToolStatus(overlay, "plan_discussion", true, overlay)
                        _messages.value = _messages.value.map {
                            if (it.id == assistantId) {
                                it.copy(content = msg, isAwaitingModelResponse = true, isStreaming = true)
                            } else it
                        }
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = "计划讨论失败: ${e.message ?: e.javaClass.simpleName}"
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.map {
                    if (it.id == assistantId) {
                        it.copy(content = err, isStreaming = false, isAwaitingModelResponse = false)
                    } else it
                }
            }
            return ""
        }
        val partsJson = "[{\"type\":\"text\",\"value\":" + escapeJson(result.markdown) + "}]"
        val persisted = chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
        withContext(Dispatchers.Main) {
            SessionActivityTracker.updateToolStatus("", null, false)
            _messages.value = _messages.value.map {
                if (it.id == assistantId) it.copy(
                    id = persisted.id,
                    content = result.markdown,
                    isStreaming = false,
                    isAwaitingModelResponse = false,
                ) else it
            }
        }
        return result.markdown
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
 * One card per sub-agent. A batch shares the parent tool id, so each member
 * gets `$toolId#sub-$index`. A single spawn keeps the parent card and must
 * not publish every live sibling's transcript into it.
 */
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
    ): ToolExecutionResult {
        if (!multiAgentSettings.enabled.value) {
            return ToolExecutionResult("Multi-agent dispatch is disabled in Settings → Multi-agent.", false)
        }
        // [T-android-subagent-depth-leak] The authoritative "am I a sub-agent?"
        // test is the lane marker carried in the coroutine context: it is scoped
        // to the coroutine, so it cannot leak, and it repairs itself by
        // construction. The depth counter is kept only as a stale-state
        // detector - an interrupted dispatch used to leave it above zero for the
        // rest of the session, and gating on it turned one stopped dispatch into
        // a session that could never dispatch again until the app was killed.
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
        val writersHere = spawns.count { SubAgentKind.canWrite(it.kind) }
        val writerTotal = maxOf(parallelWriters, writersHere)
        val sem = limiter ?: Semaphore(
            MultiAgentSettings.clampConcurrent(multiAgentSettings.maxConcurrent.value),
        )
        if (spawns.size == 1) {
            val total = waveSize.coerceAtLeast(1)
            val index = if (total > 1) waveIndex + 1 else 1
            return supervisorScope {
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
        }
        val results = supervisorScope {
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
    ): ToolExecutionResult {
        val prompt = spawn.prompt
        val role = spawn.role
        val skills = spawn.skills
        val requested = spawn.requestedModel
        val kind = spawn.kind
        val writePaths = spawn.writePaths
        val maxTurns = spawn.maxTurns
        val title = spawn.title.ifEmpty { "子代理 $index/$total" }
        if (SubAgentKind.requiresWritePaths(kind, parallelWriters) && writePaths.isEmpty()) {
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
        val config = providerRepository.config.value
        val pool = MultiAgentSettings.retainLive(
            multiAgentSettings.selectedModelEntryIds.value,
            config.modelEntries.map { it.id }.toSet(),
            multiAgentSettings.maxConcurrent.value,
        )
        // Resolve the initial entry once. Retries may swap to another pool entry
        // (see the attempt loop below) so a rate-limited / truncated endpoint is
        // skipped instead of hammered. The base pick honours the coordinator's
        // explicit `model` request and the round-robin slot assignment.
        val pickedId = MultiAgentSettings.pickModelId(pool, requested, subAgentRoundRobin.getAndIncrement())
        val baseEntry = when {
            pickedId != null -> config.modelEntries.find {
                it.id == pickedId ||
                    it.model.id.equals(pickedId, ignoreCase = true) ||
                    it.model.displayName.equals(pickedId, ignoreCase = true)
            }
            else -> _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
        } ?: return ToolExecutionResult(
            "No model available for sub-agent. Select models under Settings → Multi-agent, or keep the main session model selected.",
            false,
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
        val parentSession = realSessionId.ifBlank { sessionId }.ifBlank { activeSessionId }
        val laneId = kotlin.coroutines.coroutineContext[SubAgentLane]?.id
            ?: SubAgentLane.idFor(parentSession, System.nanoTime())
        val trackerId = com.openminis.app.service.SubAgentActivityTracker.start(
            parentSessionId = parentSession,
            title = title,
            role = role,
            model = baseEntry.model.displayName,
            index = index,
            total = total,
            kind = kind,
            turnCap = maxTurns,
        )
        return try {
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
                        val last = lastError
                        val noSameKey = last is LLMError.RateLimited ||
                            (last as? LLMError)?.isFallbackable == true
                        if (noSameKey) {
                            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, last?.message)
                            return ToolExecutionResult(
                                "Sub-agent failed after ${attempt - 1} attempt(s): ${last?.message ?: last?.javaClass?.simpleName}",
                                false,
                            )
                        }
                        baseEntry
                    }
                }
                val provider = providerForModelEntry(entry)
                if (provider == null) {
                    if (attempt >= maxAttempts) {
                        return ToolExecutionResult(
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
            val result = com.openminis.app.tools.WritePathGuard.withPaths(writePaths) {
                SubAgentRunner.run(
                    provider = provider,
                    modelDisplayName = entry.model.displayName,
                    userPrompt = prompt,
                    role = role,
                    roleContext = context,
                    skillsHint = skills,
                    tools = com.openminis.app.tools.DispatchAgentsTool.filterToolsForType(
                        com.openminis.app.tools.SubAgentTypeStore.find(context, role.orEmpty()),
                        SubAgentKind.filterTools(
                            kind,
                            AgentTools.makeAgentTools(
                                supportsImageInput = entry.model.hasImageInput,
                                visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                                    providerRepository, context,
                                ),
                                memoryEnabled = false,
                                subAgentEnabled = false,
                            ) + AgentTools.makeSubAgentExtraTools(),
                            role,
                            context,
                        ),
                    ),
                    maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192),
                    executeTool = { name, json ->
                        if (SubAgentKind.blocks(kind, name)) {
                            ToolExecutionResult("Error: $kind sub-agent cannot use $name.", false)
                        } else if (com.openminis.app.tools.CollabRoles.toolsFor(context, role)?.let { name !in it } == true) {
                            ToolExecutionResult("Error: role $role cannot use $name.", false)
                        } else if (run {
                            val type = com.openminis.app.tools.SubAgentTypeStore.find(context, role.orEmpty())
                            type != null && type.toolNames.isNotEmpty() && name !in type.toolNames
                        }) {
                            ToolExecutionResult("Error: type $role cannot use $name.", false)
                        } else if (name == com.openminis.app.tools.GrepSourceTool.NAME) {
                            com.openminis.app.tools.GrepSourceTool.execute(json, sessionId, context)
                        } else {
                            executeTool(name, json, "", mutableListOf(), "", "")
                        }
                    },
                    onStep = { turn, toolName -> onStep(turn, toolName) },
                    kind = kind,
                    writePaths = writePaths,
                    maxTurns = maxTurns,
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
            result.copy(toolTitle = title.ifEmpty { "Sub-agent · ${entry.model.displayName}" })
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
                    val backoffS = SUBAGENT_BACKOFF_S[(attempt - 1).coerceAtMost(SUBAGENT_BACKOFF_S.size - 1)]
                    val retryAfter = (e as? LLMError.RateLimited)?.retryAfterSeconds ?: 0
                    val waitS = maxOf(backoffS, retryAfter)
                    runCatching {
                        com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                            trackerId, 0, maxTurns, "retry in ${waitS}s (${e.javaClass.simpleName})",
                        )
                    }
                    delay(waitS * 1000L + Random.nextLong(0, 800))
                    null
                } else {
                    // Final failure: RETURN, do not throw — an exception escaping
                    // this lane would cancel its fan-out siblings via awaitAll.
                    com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, e.message)
                    return ToolExecutionResult(
                        "Sub-agent failed after $attempt attempt(s): ${e.message ?: e.javaClass.simpleName}",
                        false,
                    )
                }
            }
            if (attemptResult != null) {
                return if (attempt > 1) attemptResult.copy(
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
        } catch (e: CancellationException) {
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, e.message)
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
            }
        }
        } finally {
            subAgentDepth.decrementAndGet()
        }
    }

private fun isUpstreamTruncation(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return "empty response" in m || "connection dropped" in m || "upstream" in m ||
            "eof" in m || "truncat" in m
    }

/**
 * Re-pick a pool entry on a different rate-limit bucket (host + key + model).
 * Same model name on another key is a different bucket and is eligible.
 * Returns null when the pool has no other bucket (caller may stop on 429).
 */
private fun pickRetryEntry(
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
        var apiKey = providerRepository.usableApiKey(instance) ?: return null
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
