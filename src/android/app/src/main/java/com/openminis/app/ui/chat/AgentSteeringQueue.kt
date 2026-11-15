package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-agent-steering] Turn-boundary steering queue. Ported from pi
 * `packages/agent/src/types.ts:47-55` (QueueMode) and
 * `packages/agent/src/agent.ts:149-305` (PendingMessageQueue + steer()):
 * a message offered while a run is active is injected as a user message at the
 * NEXT TURN BOUNDARY — the current turn's tool execution is never interrupted.
 *
 * Relationship to the existing queued-prompt machinery (deliberate, do not
 * merge): the `_promptQueue` channel is the iOS-parity INTERRUPT behaviour —
 * `QueuedPromptInterrupt` cuts the current stream (thinking-phase cut) and
 * [injectQueuedPromptsAsNewTurn] replaces the turn. Steering is the pi
 * smoothness channel: no cut, no new assistant bubble, no bridge beyond the
 * role-alternation note below; the run keeps streaming into the SAME assistant
 * bubble and the steering text rides the next request as plain user speech.
 *
 * Thread model: offers arrive on Main (composer), drains happen on the agent
 * loop dispatcher — every operation is guarded by one lock, matching the
 * `_promptQueue` CAS discipline without the StateFlow (the queue has no UI
 * representation of its own; a drained message surfaces as a normal user row).
 *
 * Lifecycle: a message offered with no run active stays queued and is picked
 * up by the next run's first turn boundary (pi line 184-185: "Check for
 * steering messages at start"). [steerPrompt] rejects offers while idle so
 * callers fall back to a normal send instead of queueing into a void. The
 * session stamp is re-written on every access but NEVER clears the queue —
 * the store keys one VM per session (ChatViewModelStore, draft aliasing
 * included), so a stamp change inside a VM's life is only the draft→persisted
 * promotion, and cross-conversation isolation stays structural (a new
 * conversation gets a new VM; this WeakHashMap entry dies with it).
 */
internal enum class QueueMode {
    /** Drain and inject every queued message at the next boundary. */
    ALL,

    /** Inject only the oldest message per boundary, leaving the rest queued. */
    ONE_AT_A_TIME,
}

internal class AgentSteeringQueue(startMode: QueueMode = QueueMode.ONE_AT_A_TIME) {

    private val lock = Any()
    private val items = ArrayDeque<String>()

    /** pi agent.ts steeringMode: switchable while the run is live. */
    @Volatile
    var mode: QueueMode = startMode

    /** Enqueue one steering message. Blank text is rejected (an empty user msg is a 400). */
    fun offer(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        synchronized(lock) {
            items.addLast(trimmed)
        }
        return true
    }

    /** Pop the oldest single message (one-at-a-time drain). Null when empty. */
    fun drainOne(): String? = synchronized(lock) {
        if (items.isEmpty()) null else items.removeFirst()
    }

    /** Pop everything (all-mode drain). Empty list when empty. */
    fun drainAll(): List<String> = synchronized(lock) {
        val out = items.toList()
        items.clear()
        out
    }

    /** Drain respecting the current mode: ALL -> everything, ONE_AT_A_TIME -> oldest only. */
    fun drainPerMode(): List<String> = when (mode) {
        QueueMode.ALL -> drainAll()
        QueueMode.ONE_AT_A_TIME -> listOfNotNull(drainOne())
    }

    val isEmpty: Boolean get() = synchronized(lock) { items.isEmpty() }

    val size: Int get() = synchronized(lock) { items.size }

    fun clear() = synchronized(lock) { items.clear() }
}

/**
 * Per-VM steering state, stamped with the session it was offered under
 * (TokenScanMemo WeakHashMap pattern — ChatViewModel sits at its architecture
 * line ceiling, so per-VM state lives beside the logic that uses it).
 * [sessionId] is informational since the P1-7 re-stamp rule (the stamp no
 * longer gates a clear); it stays so debugging can see which session a live
 * queue belongs to.
 */
internal class SteerState(val queue: AgentSteeringQueue) {
    var sessionId: String = ""
}

/**
 * The per-VM store behind [steeringState]. Keyed by Any (the VM in
 * production, a plain token in tests) so the P1-7 re-stamp rule is
 * JVM-testable without a ChatViewModel instance. [vmSteerState] is the
 * process-wide singleton; a NEW conversation never shares an owner, so
 * cross-session isolation is structural.
 */
internal class SteeringSessionStore {
    private val states =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, SteerState>())

    /**
     * The owner's queue, RE-STAMPED (never cleared) with [sessionId] — the
     * stamp change that used to wipe the queue destroyed offers accepted
     * during the draft→persisted promotion window (P1-7, see the KDoc of
     * [steeringState] below).
     */
    internal fun stateFor(owner: Any, sessionId: String): SteerState = synchronized(states) {
        val state = states.getOrPut(owner) { SteerState(AgentSteeringQueue()) }
        state.sessionId = sessionId
        state
    }
}

private val vmSteerState = SteeringSessionStore()

/**
 * This VM's queue, re-stamped with the session id on every access.
 *
 * [T-agent-steering-stamp-keep] (P1-7) Re-stamp, NEVER clear. The old rule
 * (`sessionId != sid -> queue.clear()`) assumed a stamp change meant a
 * different conversation, but a VM is per-session (ChatViewModelStore keys by
 * session id and ALIASES the draft→persisted promotion instead of re-keying),
 * so the one stamp transition in its life happens while a run is streaming:
 * `_isStreaming` is claimed synchronously in sendMessage BEFORE ensureSession
 * promotes the draft, so an offer accepted in that window was stamped with
 * the draft id and the very next access (drain or second offer) wiped it —
 * the user's words destroyed between the composer and the request build.
 * Keeping the content is safe: cross-session isolation is structural (one VM
 * per conversation; this WeakHashMap entry dies with the VM).
 */
private fun ChatViewModel.steeringState(): SteerState =
    vmSteerState.stateFor(this, activeSessionId)

/**
 * Public steering entry point (pi agent.ts steer()). Accepted ONLY while a run
 * is streaming: false means "nothing is running" and the caller should fall
 * back to a normal send — steering that would sit forever in an idle queue is
 * worse UX than a plain send.
 */
internal fun ChatViewModel.steerPrompt(text: String): Boolean {
    if (!_isStreaming.value) return false
    val accepted = steeringState().queue.offer(text)
    if (accepted) {
        // [T-agent-steering-queued-visible] (P1-6a) Until the boundary commits
        // it, the offer has NO UI form and the composer is already cleared —
        // the user watches their words vanish. Materializing a placeholder
        // bubble at offer time would need an id threaded through the queue, a
        // drain-time replace instead of append, and withdrawal on stop/session
        // end — a whole placeholder lifecycle for one row; instead this
        // ephemeral system-info row says "queued" and
        // [commitSteeringUserMessage] still lands the real bubble at the
        // boundary (nothing duplicated). appendSystemInfo only mutates the
        // messages StateFlow, so it is safe from the Main-only offer path.
        appendSystemInfo(
            text = context.getString(com.openminis.app.R.string.vm_steering_queued),
            iconKind = "info",
        )
    }
    return accepted
}

/**
 * pi agent-loop.ts:187-193 keep-alive: true while steering text is waiting for
 * a boundary inside the live run. runAgentLoop's natural-exit site (the turn
 * that produced no tool calls) continues the loop on true, so an offer
 * accepted during that FINAL turn — which has no later boundary — still gets
 * its in-run turn (next iteration's [drainSteeringIntoRun] commits and carries
 * it) instead of stranding in the queue until some future run.
 */
internal fun ChatViewModel.steeringKeepAlive(): Boolean =
    _isStreaming.value && !steeringState().queue.isEmpty

/**
 * Turn-boundary drain (pi agent-loop.ts runLoop steering pickup). Drains per
 * the current mode and commits each message as a REAL user message — visible
 * bubble, persisted row, transcript append — so the next request (built right
 * after this call in the loop) carries the user's words.
 *
 * Returns true when at least one message was injected.
 */
internal suspend fun ChatViewModel.drainSteeringIntoRun(): Boolean {
    val texts = steeringState().queue.drainPerMode()
    for (text in texts) {
        commitSteeringUserMessage(text)
    }
    return texts.isNotEmpty()
}

/**
 * Role-alternation bridge for a steering message that follows tool results.
 * Same root problem as the #579 note in [injectQueuedPromptsAsNewTurn]: a
 * user message appended directly after a user(tool_result) turn merges into
 * one turn on several providers and loses the boundary. Steering-specific
 * wording — the run is NOT interrupted, the note only declares the boundary.
 */
private const val STEERING_BRIDGE_TEXT =
    "(A steering note from the user follows, added mid-run. The current task keeps " +
        "running — fold the note into the work in progress; treat it as a task change only if it says so.)"

/**
 * Append one steering message the way [injectQueuedPromptsAsNewTurn] appends a
 * queued prompt: conditional role-alternation bridge, persisted DB row,
 * bounded-history append, visible UI bubble. No new assistant placeholder —
 * the run keeps streaming into its existing bubble.
 */
private suspend fun ChatViewModel.commitSteeringUserMessage(text: String) {
    val sid = ensureSession()
    if (agentHistory.lastOrNull()?.role == LLMMessage.Role.USER) {
        appendBoundedHistory(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = STEERING_BRIDGE_TEXT,
                contentParts = listOf(AgentContentPart.Text(STEERING_BRIDGE_TEXT)),
            ),
        )
    }
    val partsJson = buildUserPartsJson(text, emptyList())
    val persisted = withContext(Dispatchers.IO) {
        runCatching { chatRepository.appendMessage(sid, "user", partsJson) }.getOrNull()
    }
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = text,
            contentParts = listOf(AgentContentPart.Text(text)),
            dbMessageId = persisted?.id,
        ),
    )
    withContext(Dispatchers.Main) {
        val bubbleId = persisted?.id ?: "steer_${System.currentTimeMillis()}"
        _messages.value = trimLoadedWindow(
            _messages.value + ChatMessage(
                id = bubbleId,
                role = "user",
                content = text,
                sourceDbIds = listOfNotNull(persisted?.id),
            ),
        )
        persisted?.let { notePersistedUiRow(it.id, it.id) }
    }
}
