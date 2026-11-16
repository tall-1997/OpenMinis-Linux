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
 * smoothness channel: no cut, no bridge beyond the role-alternation note
 * below; the steering text rides the next request as plain user speech and
 * the model folds the note into the work in progress. The UI commit is what
 * differs: [drainSteeringIntoRun] seals the running assistant bubble, lands
 * the user bubble right after it and opens a FRESH assistant bubble for the
 * continuation — the pre-fix commit appended the user bubble at the END of
 * the list while the answer kept streaming into the bubble ABOVE it, so the
 * message sat below its own answer forever.
 *
 * Thread model: offers arrive on Main (composer), drains happen on the agent
 * loop dispatcher — headless entry points may call from a non-Main thread, so
 * EVERY operation (queue ops and [SteeringSessionStore.stateFor] alike) is
 * guarded by one lock, matching the `_promptQueue` CAS discipline without the
 * StateFlow. The queue itself has no UI representation, but [steerPrompt]
 * materialises a placeholder bubble per offer (ids threaded through
 * [SteeringEntry]) so the user sees their queued words immediately.
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

/**
 * [steering-commit-ui] One queued steering message. The id is threaded
 * through the queue so the offer can materialise a placeholder bubble
 * immediately and the boundary commit can replace it at the sealed-assistant
 * position (the pre-fix offer had NO bubble at all — only an ephemeral
 * system-info row, and the commit appended the real bubble at the end of the
 * list). The placeholder/sysinfo ids are deterministic off the entry id, so
 * no extra state is stored.
 */
internal data class SteeringEntry(
    val id: String,
    val text: String,
) {
    /** Placeholder-bubble id: [steerPrompt] appends it, the commit finds it. */
    val placeholderMessageId: String get() = "steer_q_$id"

    /** System-info "queued" row id: the offer appends it, the commit drops it. */
    val sysinfoMessageId: String get() = "steer_sys_$id"
}

internal class AgentSteeringQueue(startMode: QueueMode = QueueMode.ONE_AT_A_TIME) {

    private val lock = Any()
    private val items = ArrayDeque<SteeringEntry>()

    /** pi agent.ts steeringMode: switchable while the run is live. */
    @Volatile
    var mode: QueueMode = startMode

    /** Enqueue one steering message. Blank text is rejected (an empty user msg is a 400). */
    fun offer(text: String): SteeringEntry? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val entry = SteeringEntry(
            id = "steer_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
            text = trimmed,
        )
        synchronized(lock) {
            items.addLast(entry)
        }
        return entry
    }

    /** Pop the oldest single message (one-at-a-time drain). Null when empty. */
    fun drainOne(): SteeringEntry? = synchronized(lock) {
        if (items.isEmpty()) null else items.removeFirst()
    }

    /** Pop everything (all-mode drain). Empty list when empty. */
    fun drainAll(): List<SteeringEntry> = synchronized(lock) {
        val out = items.toList()
        items.clear()
        out
    }

    /** Drain respecting the current mode: ALL -> everything, ONE_AT_A_TIME -> oldest only. */
    fun drainPerMode(): List<SteeringEntry> = when (mode) {
        QueueMode.ALL -> drainAll()
        QueueMode.ONE_AT_A_TIME -> listOfNotNull(drainOne())
    }

    /**
     * [T-agent-steering-exit-fallback] Mode-independent fallback flush: hand
     * EVERY currently queued entry to [sink] and clear the queue. True when at
     * least one entry moved. Unlike [drainPerMode] this ignores the mode — a
     * fallback flush must not strand entries behind a ONE_AT_A_TIME boundary
     * the ending run will never reach. The sink indirection keeps the routing
     * policy (VM-side: enqueuePrompt) out of the pure queue component, so the
     * fallback is JVM-testable with a counting fake channel.
     */
    fun drainToFallback(sink: (SteeringEntry) -> Unit): Boolean {
        val entries = drainAll()
        entries.forEach(sink)
        return entries.isNotEmpty()
    }

    /** Withdraw a queued entry (queued-bubble withdraw path). True when removed. */
    fun remove(entryId: String): Boolean = synchronized(lock) {
        val before = items.size
        items.removeAll { it.id == entryId }
        items.size != before
    }

    /** Rewrite a queued entry's text (queued-bubble edit path). Blank rejected. */
    fun updateText(entryId: String, newText: String): Boolean = synchronized(lock) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return false
        val idx = items.indexOfFirst { it.id == entryId }
        if (idx < 0) return false
        items[idx] = items[idx].copy(text = trimmed)
        true
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
 *
 * Thread model: [stateFor] is reachable from Main (composer offers, UI
 * drains) AND from headless entry points on background dispatchers, so the
 * get-or-create is one critical section under `synchronized(states)` — a
 * hand-rolled get + put-if-null, NOT [MutableMap.getOrPut], whose compound
 * get-then-put is only atomic when every reader provably holds the same
 * monitor as the wrapper's own methods.
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
        var state = states[owner]
        if (state == null) {
            state = SteerState(AgentSteeringQueue())
            states[owner] = state
        }
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
internal fun ChatViewModel.steeringState(): SteerState =
    vmSteerState.stateFor(this, activeSessionId)

/**
 * Public steering entry point (pi agent.ts steer()). Accepted ONLY while a run
 * is streaming: false means "nothing is running" and the caller should fall
 * back to a normal send — steering that would sit forever in an idle queue is
 * worse UX than a plain send.
 */
internal fun ChatViewModel.steerPrompt(text: String): Boolean {
    if (!_isStreaming.value) return false
    val entry = steeringState().queue.offer(text) ?: return false
    // [T-agent-steering-queued-visible] (P1-6a) The offer materialises TWO
    // UI forms immediately: the ephemeral system-info "queued" row (the
    // hint) and a placeholder USER bubble carrying the actual words. The
    // pre-fix offer had NO bubble at all — the user watched their text vanish
    // into a system notice. The placeholder id is deterministic
    // ([SteeringEntry.placeholderMessageId]) so the boundary commit can find
    // and replace it at the sealed-assistant position; withdraw/edit route
    // through the same isQueued/queuedPromptId conventions the _promptQueue
    // placeholders use. appendSystemInfo and the StateFlow write are Main-only
    // safe (the offer path is Main-confined).
    appendSystemInfo(
        text = context.getString(com.openminis.app.R.string.vm_steering_queued),
        iconKind = "info",
        messageId = entry.sysinfoMessageId,
    )
    _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
        id = entry.placeholderMessageId,
        role = "user",
        content = entry.text,
        isQueued = true,
        queuedPromptId = entry.id,
    ))
    return true
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
 * [steering-commit-ui] Turn-boundary drain (pi agent-loop.ts runLoop steering
 * pickup). Drains per the current mode and commits each message as a REAL
 * user message — persisted row, transcript append — then REPOSITIONS the UI:
 * the running assistant bubble is sealed (its accumulated text stays above),
 * the real user bubbles land right after it, the queued hint rows and
 * placeholder bubbles are dropped, and a FRESH assistant bubble opens below
 * for the continuation. The loop switches its streaming target through
 * [onCommitted] (swapLoopStateToInjectedBubble in the agent loop) — without
 * the swap the continuation would keep writing into the sealed bubble.
 *
 * Empty-bubble edge (an offer accepted before the first turn streamed): the
 * assistant placeholder holds no content, so it is dropped rather than
 * sealed — sealing would leave an empty bubble in the transcript. The model
 * request is unaffected either way: it is built from agentHistory, which the
 * commit below already carries the entries into.
 *
 * Called once per turn at the top of runAgentLoop, before the request is
 * built.
 */
internal suspend fun ChatViewModel.drainSteeringIntoRun(
    assistantId: String,
    accumulatedText: String,
    allToolBlocks: List<AssistantBlock>,
    uiBatcher: UiUpdateBatcher,
    onCommitted: (String) -> Unit,
) {
    val entries = steeringState().queue.drainPerMode()
    if (entries.isEmpty()) return
    val bubbles = entries.map { commitSteeringUserMessage(it) }
    val newAssistantId = "assistant_${System.currentTimeMillis()}"
    withContext(Dispatchers.Main.immediate) {
        uiBatcher.seal(assistantId)
        _messages.value = repositionAfterSteeringCommit(
            messages = _messages.value,
            placeholderMessageIds = entries.map { it.placeholderMessageId }.toSet(),
            sysinfoMessageIds = entries.map { it.sysinfoMessageId }.toSet(),
            assistantId = assistantId,
            accumulatedText = accumulatedText,
            allToolBlocks = allToolBlocks,
            bubbles = bubbles,
            newAssistantMsg = ChatMessage(
                id = newAssistantId,
                role = "assistant",
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = _thinkingLevel.value,
            ),
        )
        bubbles.forEach { bubble ->
            bubble.sourceDbIds.firstOrNull()?.let { notePersistedUiRow(bubble.id, it) }
        }
        updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
    }
    onCommitted(newAssistantId)
}

/**
 * Pure list surgery behind [drainSteeringIntoRun] — extracted so the
 * reposition contract (placeholders gone, hint rows gone, sealed assistant
 * above the user bubbles, fresh assistant below; empty assistant bubble
 * dropped, not sealed) is JVM-testable without a ChatViewModel.
 */
internal fun repositionAfterSteeringCommit(
    messages: List<ChatMessage>,
    placeholderMessageIds: Set<String>,
    sysinfoMessageIds: Set<String>,
    assistantId: String,
    accumulatedText: String,
    allToolBlocks: List<AssistantBlock>,
    bubbles: List<ChatMessage>,
    newAssistantMsg: ChatMessage,
): List<ChatMessage> {
    val cleaned = messages.filterNot { m ->
        m.id in placeholderMessageIds || (m.role == "system" && m.id in sysinfoMessageIds)
    }
    return if (accumulatedText.isNotEmpty() || allToolBlocks.isNotEmpty()) {
        cleaned.map { m ->
            if (m.id == assistantId) m.copy(
                content = accumulatedText,
                isStreaming = false,
                isAwaitingModelResponse = false,
                toolBlocks = allToolBlocks,
            ) else m
        } + bubbles + listOf(newAssistantMsg)
    } else {
        cleaned.filterNot { it.id == assistantId } + bubbles + listOf(newAssistantMsg)
    }
}

/**
 * [steering-commit-ui] Withdraw a queued steering placeholder (the
 * queued-bubble withdraw affordance routes here for `steer_`-prefixed
 * queuedPromptIds; _promptQueue-owned bubbles stay on
 * [ChatViewModel.withdrawQueuedMessage]). True when this was a steering
 * placeholder and it is gone.
 */
internal fun ChatViewModel.withdrawSteeringPlaceholder(messageId: String): Boolean {
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return false
    if (!msg.isQueued) return false
    val pid = msg.queuedPromptId ?: return false
    if (!pid.startsWith("steer_")) return false
    if (!steeringState().queue.remove(pid)) return false
    _messages.value = _messages.value.filterNot { it.id == messageId }
    return true
}

/**
 * [T-agent-steering-exit-fallback] (P2-3) Post-run safety net, called ONCE at
 * runAgentLoop's natural exit (keep-alive declined, before the break):
 * steering accepted during the final turn we are NOT continuing would strand
 * in the queue until some future run happened to touch steeringState() again.
 * Re-route every entry onto the existing post-run prompt dispatch —
 * enqueuePrompt → _promptQueue → the launching wrapper's drainQueuedPrompts —
 * and clear the queue.
 *
 * skipDedupe=true is load-bearing: the offer path already recorded the
 * fingerprint at acceptance (see the sendMessage steering branch), so the
 * atomic check-and-record gate would reject the entry as a duplicate of
 * itself. The queued system-info hint is NOT re-emitted either — the offer
 * path already surfaced it, and the queued bubble enqueuePrompt appends is
 * the post-offer representation. Error/cancel paths keep the "next run's
 * turn-0 drain" semantics (drainSteeringIntoRun at the top of the loop);
 * only the natural exit calls this.
 *
 * Main-confined: enqueuePrompt writes `_messages`/`_attachments` outside the
 * CAS discipline, while the agent loop runs on Dispatchers.IO — the hop keeps
 * the funnel's thread contract. The gate check happens before the hop so a
 * stream that already wound down keeps its entries queued for the next run's
 * turn-0 drain instead of dropping them into a closed door. Returns true when
 * at least one entry was re-routed.
 */
internal suspend fun ChatViewModel.drainToPromptQueueFallback(): Boolean {
    if (!_isStreaming.value) return false
    return withContext(Dispatchers.Main) {
        var moved = false
        steeringState().queue.drainToFallback { entry ->
            moved = true
            // [steering-commit-ui] Drop the steering forms of this entry (the
            // placeholder bubble + queued hint row) before enqueuePrompt
            // appends the post-offer representation — otherwise the message
            // would appear twice.
            _messages.value = _messages.value.filterNot { m ->
                m.id == entry.placeholderMessageId ||
                    (m.role == "system" && m.id == entry.sysinfoMessageId)
            }
            enqueuePrompt(entry.text, skipDedupe = true)
        }
        moved
    }
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
 * Commit one steering entry the way [injectQueuedPromptsAsNewTurn] commits a
 * queued prompt: conditional role-alternation bridge, persisted DB row,
 * bounded-history append. The UI bubble is NOT appended here —
 * [drainSteeringIntoRun] inserts the returned bubbles at the sealed-assistant
 * position in one batch reposition.
 */
private suspend fun ChatViewModel.commitSteeringUserMessage(entry: SteeringEntry): ChatMessage {
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
    val partsJson = buildUserPartsJson(entry.text, emptyList())
    val persisted = withContext(Dispatchers.IO) {
        runCatching { chatRepository.appendMessage(sid, "user", partsJson) }.getOrNull()
    }
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = entry.text,
            contentParts = listOf(AgentContentPart.Text(entry.text)),
            dbMessageId = persisted?.id,
        ),
    )
    return ChatMessage(
        id = persisted?.id ?: "steer_${System.currentTimeMillis()}",
        role = "user",
        content = entry.text,
        sourceDbIds = listOfNotNull(persisted?.id),
    )
}
