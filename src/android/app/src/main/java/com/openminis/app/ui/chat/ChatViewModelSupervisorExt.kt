package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.tools.SupervisorChat
import com.openminis.app.tools.SupervisorPrompts
import com.openminis.app.tools.SupervisorRunStore
import com.openminis.app.tools.SupervisorSeats
import com.openminis.app.tools.SupervisorSessionRunner
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.VisionGroupResolver
import com.openminis.app.tools.WritePathGuard
import androidx.lifecycle.viewModelScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live supervisor sessions keyed by session id. The host is the single
 * source of truth for "is a loop running here": the UI asks it before
 * routing user input to the inbox, and [haltSupervisorSession] finds the
 * job to cancel through it. Registration is process-local by design —
 * cross-process recovery is [SupervisorRunStore]'s job, not this one.
 */
internal object SupervisorSessionHost {

    internal class Live(
        val sessionId: String,
        val objective: String,
        val inbox: SupervisorChat.SupervisorInbox,
        val job: Job,
    )

    private val sessions = ConcurrentHashMap<String, Live>()

    fun register(live: Live) {
        sessions[live.sessionId] = live
    }

    fun remove(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun live(sessionId: String): Live? = sessions[sessionId]

    fun isLive(sessionId: String): Boolean = sessions.containsKey(sessionId)
}

/**
 * UI projection of a supervisor session, keyed by session id. The runner
 * coroutine is the only writer while a loop is live; [reset] clears the
 * slot when the chat switches sessions so a stale bar never follows the
 * user into another conversation.
 */
internal object SupervisorUiHost {

    /** Coarse phase for the status bar. Terminal phases linger, then clear. */
    internal enum class Phase { INSTRUCTING, EXECUTING, REVIEWING, DONE, HALTED, FAILED, INTERRUPTED }

    internal data class UiState(
        val sessionId: String,
        val phase: Phase,
        val objective: String,
        val workerTurns: Int = 0,
    ) {
        val isLive: Boolean
            get() = phase == Phase.INSTRUCTING || phase == Phase.EXECUTING || phase == Phase.REVIEWING
    }

    private val states = ConcurrentHashMap<String, UiState>()
    private val flow = MutableStateFlow<UiState?>(null)

    /** The bar renders this; null means no supervisor activity to show. */
    val uiState: StateFlow<UiState?> = flow.asStateFlow()

    internal fun update(sessionId: String, phase: Phase, objective: String, workerTurns: Int) {
        if (sessionId.isBlank()) return
        val state = UiState(sessionId, phase, objective, workerTurns)
        states[sessionId] = state
        flow.value = state
    }

    /** Clears the slot for one session (discard / switch away). */
    internal fun reset(sessionId: String) {
        states.remove(sessionId)
        if (flow.value?.sessionId == sessionId) flow.value = null
    }

    /** Re-publishes the stored state for a session after a switch back. */
    internal fun republish(sessionId: String) {
        val state = states[sessionId] ?: return
        flow.value = state
    }
}

/**
 * ChatViewModel wiring for Supervisor-Worker sessions
 * (spec: docs/superpowers/specs/2026-10-24-supervisor-worker-loop-design.md §3).
 *
 * The protocol state machine lives in [SupervisorSessionRunner]; this file
 * builds the two seats out of the group-chat pipeline (supervisor speaks via
 * [speakModel] under a NO_WRITE guard, worker executes via [SubAgentRunner]
 * inside the instruction's WritePathGuard scope) and owns the lifecycle:
 * start, halt, user interjections, run-store persistence, and recovery.
 *
 * Speaker labels are plain English by design: the supervisor's bubble
 * text is model-generated protocol output (instruction envelopes, DONE
 * lines), not UI chrome — only the status bar and slash panel carry
 * localized strings (this PR).
 */
private const val SUPERVISOR_SPEAKER = "Supervisor"

/** Bubble id prefix so PR#3 can restyle supervisor rows by id shape. */
private const val SUPERVISOR_BUBBLE_PREFIX = "supervisor-"

private fun ChatViewModel.supervisorBaseTools(entry: ModelEntry): List<AgentToolDefinition> =
    AgentTools.makeAgentTools(
        supportsImageInput = entry.model.hasImageInput,
        visionGroupConfigured = VisionGroupResolver.isConfigured(providerRepository, context),
        memoryEnabled = false,
        subAgentEnabled = false,
    )

/** Supervisor seat: the session model, or null when it has no usable provider. */
internal fun ChatViewModel.resolveSupervisorEntry(): ModelEntry? =
    _activeEntryId.value?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }

/**
 * Worker seat: the dedicated worker model slot when set, else the session
 * model. An empty slot is "follow the session model", not "no worker" —
 * a supervisor chat must always be able to dispatch work.
 */
internal fun ChatViewModel.resolveWorkerEntry(): ModelEntry? {
    val slotId = multiAgentSettings.workerModelEntryId.value
    if (slotId.isNotBlank()) {
        providerRepository.config.value.modelEntries.find { it.id == slotId }?.let { return it }
    }
    return resolveSupervisorEntry()
}

/** True while a supervisor loop is running in this session. */
internal fun ChatViewModel.supervisorSessionActive(): Boolean =
    SupervisorSessionHost.isLive(activeSessionId)

/**
 * The interrupted run for this session, or null. Reads reconcile: a RUNNING
 * record from a dead process flips to INTERRUPTED and is offered back for
 * recovery. Never called while a loop is live — a live loop owns the record.
 */
internal suspend fun ChatViewModel.pendingSupervisorRecovery(): SupervisorRunStore.RunRecord? {
    if (supervisorSessionActive()) return null
    return SupervisorRunStore.interrupted(activeSessionId, context)
}

/**
 * Starts a supervisor-worker loop for [objective]. Returns false without
 * side effects when a loop is already live, the session id is blank, or
 * either seat has no usable provider (the caller surfaces the reason).
 *
 * [recovery] resumes an interrupted run: its digest becomes the first
 * turn's recovery section so the supervisor re-verifies completed work
 * before issuing new instructions.
 */
internal suspend fun ChatViewModel.startSupervisorSession(
    objective: String,
    recovery: SupervisorRunStore.RunRecord? = null,
): Boolean {
    val trimmed = objective.trim()
    if (trimmed.isEmpty()) return false
    val sessionId = activeSessionId
    if (sessionId.isBlank()) return false
    if (SupervisorSessionHost.isLive(sessionId)) return false

    val supervisorEntry = resolveSupervisorEntry() ?: return false
    val workerEntry = resolveWorkerEntry() ?: return false
    val supervisorProvider = providerForModelEntry(supervisorEntry) ?: return false
    val workerProvider = providerForModelEntry(workerEntry) ?: return false

    val maxWorkerTurns = multiAgentSettings.supervisorMaxWorkerTurns.value
    val inbox = SupervisorChat.SupervisorInbox()
    val contextDigest = SupervisorPrompts.contextDigest(agentHistorySnapshot(), MAX_CONTEXT_DIGEST_CHARS)
    val recoveryIntro = recovery?.let { SupervisorPrompts.recoveryIntro(it) }

    // Written before the job launches so a halt that lands before the first
    // suspension point still has a record to mark USER_HALTED — markTerminal
    // no-ops on a missing head, and a lost record means a phantom "running"
    // run the next time the session is opened. Same ordering for the UI
    // state: the bar must appear the instant the send is consumed.
    SupervisorRunStore.start(sessionId, context, trimmed, maxWorkerTurns)
    SupervisorUiHost.reset(sessionId)
    SupervisorUiHost.update(sessionId, SupervisorUiHost.Phase.INSTRUCTING, trimmed, 0)

    // [T-supervisor-register-race] The Live must be registered before the
    // loop body can run: a terminal that overtakes register would leave a
    // dead session in the host, permanently "live". LAZY start keeps the
    // body from starting until the register above has landed.
    val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        runSupervisorSession(
            sessionId = sessionId,
            objective = trimmed,
            recoveryIntro = recoveryIntro,
            contextDigest = contextDigest,
            maxWorkerTurns = maxWorkerTurns,
            supervisorEntry = supervisorEntry,
            supervisorProvider = supervisorProvider,
            workerEntry = workerEntry,
            workerProvider = workerProvider,
            inbox = inbox,
        )
    }
    SupervisorSessionHost.register(
        SupervisorSessionHost.Live(sessionId, trimmed, inbox, job),
    )
    if (!job.start()) {
        // Halt landed between register and start: the loop body never ran,
        // so the terminal observer cannot fire — settle the record and the
        // bar here, same terminal shape as the halt path.
        SupervisorSessionHost.remove(sessionId)
        SupervisorRunStore.markTerminal(sessionId, context, SupervisorRunStore.STATE_USER_HALTED, null)
        SupervisorUiHost.update(sessionId, SupervisorUiHost.Phase.HALTED, trimmed, 0)
    }
    return true
}

/**
 * Resumes the interrupted run for this session. The stored digest becomes
 * the recovery section of the first supervisor turn, so completed work is
 * re-verified before new instructions are issued (spec §3.8). Returns
 * false when there is nothing to resume or the start was refused.
 */
internal suspend fun ChatViewModel.resumeSupervisorSession(): Boolean {
    val recovery = pendingSupervisorRecovery() ?: return false
    return startSupervisorSession(recovery.objective, recovery)
}

/**
 * Withdraws the recovery offer for this session. The transcript keeps
 * whatever was streamed before the interruption; only the run record and
 * the bar's INTERRUPTED phase are cleared.
 */
internal suspend fun ChatViewModel.discardSupervisorRecovery() {
    val sessionId = activeSessionId
    SupervisorRunStore.clear(sessionId, context)
    SupervisorUiHost.reset(sessionId)
}

/**
 * Halts the live loop for this session. The cancelled coroutine unwinds
 * through [SupervisorSessionRunner]'s CancellationException path, which
 * marks the run USER_HALTED and fires the terminal observer. Returns
 * false when no loop is live.
 */
internal fun ChatViewModel.haltSupervisorSession(): Boolean {
    val live = SupervisorSessionHost.live(activeSessionId) ?: return false
    live.job.cancel()
    return true
}

/**
 * Routes a user interjection into the live loop's inbox. The supervisor —
 * never the user directly — folds it into the next instruction. Returns
 * false when no loop is live (the caller then handles the text normally).
 */
internal fun ChatViewModel.pushSupervisorUserMessage(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val live = SupervisorSessionHost.live(activeSessionId) ?: return false
    live.inbox.push(trimmed)
    return true
}

/**
 * Toggles the armed state from the slash panel. The hint text is emitted by
 * the caller (SlashExec) so this stays a pure state flip.
 */
internal fun ChatViewModel.toggleSupervisorArmed() {
    _supervisorArmed.value = !_supervisorArmed.value
}

/**
 * Disarms and returns the previous value — the send path calls this once it
 * has consumed the objective, so a failed start cannot leave the composer
 * silently armed for the next send.
 */
internal fun ChatViewModel.disarmSupervisor(): Boolean {
    val was = _supervisorArmed.value
    _supervisorArmed.value = false
    return was
}

/**
 * Persists a user turn that belongs to a supervisor session (the objective
 * on start, or an interjection while live). The row is the same shape a
 * normal send produces, but it never enters agentHistory: the supervisor
 * loop owns its own context pipeline, and user interjections reach the
 * supervisor through the inbox, not through the chat transcript.
 */
internal suspend fun ChatViewModel.persistSupervisorUserTurn(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    val sessionId = activeSessionId
    if (sessionId.isBlank()) return
    val partsJson = buildUserPartsJson(trimmed, emptyList(), null)
    val persisted = chatRepository.appendMessage(sessionId, "user", partsJson) ?: return
    val userMsg = ChatMessage(
        id = persisted.id,
        role = "user",
        content = trimmed,
        sourceDbIds = listOf(persisted.id),
    )
    _messages.value = trimLoadedWindow(_messages.value + userMsg)
    notePersistedUiRow(persisted.id, persisted.id)
}

private suspend fun ChatViewModel.runSupervisorSession(
    sessionId: String,
    objective: String,
    recoveryIntro: String?,
    contextDigest: String,
    maxWorkerTurns: Int,
    supervisorEntry: ModelEntry,
    supervisorProvider: com.openminis.app.provider.LLMProvider,
    workerEntry: ModelEntry,
    workerProvider: com.openminis.app.provider.LLMProvider,
    inbox: SupervisorChat.SupervisorInbox,
) {
    val supervisorBubbleId = "$SUPERVISOR_BUBBLE_PREFIX$sessionId"

    val workerSpeaker = workerEntry.model.displayName

    suspend fun supervisorNotice(text: String) {
        upsertGroupBubble(UUID.randomUUID().toString(), SUPERVISOR_SPEAKER, text, analyzing = false)
        persistGroupUtterance(SUPERVISOR_SPEAKER, text, null)
    }

    supervisorNotice("Supervisor session started — objective: $objective")

    var workerRound = 0
    SupervisorUiHost.update(sessionId, SupervisorUiHost.Phase.INSTRUCTING, objective, 0)

    val supervisorSeat = SupervisorSessionRunner.Seat { user, system, _ ->
        upsertGroupBubble(supervisorBubbleId, SUPERVISOR_SPEAKER, "", analyzing = true)
        val text = WritePathGuard.withPaths(listOf(WritePathGuard.NO_WRITE)) {
            speakModel(
                provider = supervisorProvider,
                system = system,
                user = user,
                tools = SupervisorSeats.supervisorTools(supervisorBaseTools(supervisorEntry)),
                toolGate = null,
                thinkingLevel = supervisorEntry.effectiveMaxThinkingLevel,
                stopWhenClosing = false,
                onStatus = {},
            )
        }
        upsertGroupBubble(supervisorBubbleId, SUPERVISOR_SPEAKER, text, analyzing = false)
        text
    }

    val workerSeat = SupervisorSessionRunner.Seat { user, _, writePaths ->
        workerRound += 1
        SupervisorUiHost.update(sessionId, SupervisorUiHost.Phase.EXECUTING, objective, workerRound)
        val bubbleId = "supervisor-worker-$sessionId-$workerRound"
        upsertGroupBubble(bubbleId, workerSpeaker, "", analyzing = true)
        val result = WritePathGuard.withPaths(writePaths) {
            SubAgentRunner.run(
                provider = workerProvider,
                modelDisplayName = workerEntry.model.displayName,
                userPrompt = user,
                role = null,
                skillsHint = null,
                tools = SupervisorSeats.workerTools(supervisorBaseTools(workerEntry)),
                maxTokens = (workerEntry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192),
                executeTool = { name, json ->
                    if (SubAgentKind.blocks(SubAgentKind.WORKER, name)) {
                        ToolExecutionResult("Error: WORKER sub-agent cannot use $name.", false)
                    } else {
                        grepSourceToolResult(name, json)
                            ?: executeTool(name, json, "")
                    }
                },
                kind = SubAgentKind.WORKER,
                writePaths = writePaths,
                maxTurns = multiAgentSettings.subagentMaxTurns.value,
                thinkingLevel = workerEntry.effectiveMaxThinkingLevel,
            )
        }
        upsertGroupBubble(bubbleId, workerSpeaker, result.output, analyzing = false)
        SupervisorUiHost.update(sessionId, SupervisorUiHost.Phase.REVIEWING, objective, workerRound)
        result.output
    }

    SupervisorSessionRunner.run(
        objective = objective,
        contextDigest = contextDigest,
        maxWorkerTurns = maxWorkerTurns,
        supervisorSystem = SupervisorPrompts.supervisorSystem(supervisorEntry.model.displayName),
        supervisor = supervisorSeat,
        worker = workerSeat,
        inbox = inbox,
        recovery = recoveryIntro,
        onRound = { n, instructionDigest, reportDigest ->
            SupervisorRunStore.appendRound(sessionId, context, n, instructionDigest, reportDigest)
        },
        onTerminal = { state, summary ->
            SupervisorRunStore.markTerminal(sessionId, context, state, summary)
            SupervisorSessionHost.remove(sessionId)
            val phase = when (state) {
                SupervisorRunStore.STATE_DONE -> SupervisorUiHost.Phase.DONE
                SupervisorRunStore.STATE_USER_HALTED -> SupervisorUiHost.Phase.HALTED
                else -> SupervisorUiHost.Phase.FAILED
            }
            SupervisorUiHost.update(sessionId, phase, objective, workerRound)
            val text = when (state) {
                SupervisorRunStore.STATE_DONE ->
                    "Supervisor session complete. ${summary.orEmpty().trim()}".trim()
                SupervisorRunStore.STATE_USER_HALTED ->
                    "Supervisor session halted by user."
                else ->
                    "Supervisor session failed: ${summary ?: state}"
            }
            supervisorNotice(text)
        },
    )
}


/** Cap on the chat digest fed to the supervisor's first turn. */
private const val MAX_CONTEXT_DIGEST_CHARS = 4000
