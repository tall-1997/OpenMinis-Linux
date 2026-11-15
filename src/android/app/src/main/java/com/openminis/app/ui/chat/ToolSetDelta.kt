package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition

/**
 * [T-toolset-delta] Tool-set delta declaration. Ported from pi
 * `packages/agent/src/agent-loop.ts:342-384` (declareToolChanges) and
 * `packages/ai/src/utils/transcript.ts:58-96` (getCurrentTools /
 * getToolStateChanges): pi compares the executable tool set against what the
 * transcript declares and turns the difference into `toolsAdded` /
 * `toolsRemoved` on a system message, so the model's view of "what can I call"
 * survives tool-set changes without re-sending the whole tool surface.
 *
 * This port reuses the house "context-only injection" channel instead of a
 * provider system-message field: [commitHiddenUserNote] (goal / recall /
 * state-snapshot notices) writes a `<system-reminder>` user note into the
 * transcript that the display pipeline strips from user bubbles. The delta is
 * computed per turn boundary in [ChatViewModelAgentLoopExt] against the PREVIOUS
 * turn's set, so a mid-run find_tools enablement (or a memory / vision /
 * plugin toggle) is announced exactly once, at the next request.
 *
 * pi-faithful property kept: an UNCHANGED set never injects anything across
 * turns (the no-change contract), and the FIRST observation initializes the
 * baseline silently — a fresh run must not announce the whole always-on surface
 * as "added" (pi avoids this by replaying transcript deltas; our baseline is
 * the first turn's set per session).
 *
 * Pure string/set logic, no Android imports — must stay testable on the pure
 * JVM (same constraint as TaskAnchor / StandingContract).
 */
internal data class ToolSetDelta(
    /** Tool names present now but not in the previous turn's set, first-seen order. */
    val added: List<String>,
    /** Tool names in the previous turn's set but absent now, first-seen order. */
    val removed: List<String>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

/**
 * Pure set diff with order preservation: added keeps [nextNames] order,
 * removed keeps [prevNames] order — stable output for identical inputs, so the
 * declaration text is deterministic (and golden-testable).
 */
internal fun diffToolSets(prevNames: Collection<String>, nextNames: Collection<String>): ToolSetDelta {
    val prev = LinkedHashSet(prevNames)
    val next = LinkedHashSet(nextNames)
    return ToolSetDelta(
        added = next.filterNot { it in prev },
        removed = prev.filterNot { it in next },
    )
}

/** The `<system-reminder>` inner text for a tool-set delta (see [ToolSetDelta]). */
internal object ToolSetDeltaNotice {

    /**
     * Substring that identifies this notice in history. Deliberately short and
     * free of punctuation a translation or rewrite might drop (same rule as
     * StreamDropReminder.MARKER).
     */
    const val MARKER = "Tools changed"

    /**
     * Render the declaration: `Tools changed: +a, +b, -c` followed by the
     * action instruction. The framing `<system-reminder>…</system-reminder>`
     * is added by [commitHiddenUserNote], not here — one framing authority.
     */
    fun text(delta: ToolSetDelta): String {
        val changes = buildList {
            if (delta.added.isNotEmpty()) add(delta.added.joinToString(prefix = "+", separator = ", +"))
            if (delta.removed.isNotEmpty()) add(delta.removed.joinToString(prefix = "-", separator = ", -"))
        }
        return "$MARKER: ${changes.joinToString(" ")}." +
            " Newly added tools are callable immediately; removed tools now fail — stop calling them." +
            " Reason over this change before your next tool call; the instructions' tool list may lag this note."
    }
}

/**
 * Stateful half of the declaration: keeps the last declared set for one
 * session and answers with the delta to declare, or null when nothing changed
 * (including the silent first observation). Synchronized because the agent
 * loop runs off-main while other paths may touch tool state.
 */
internal class ToolSetTracker {
    private var declared: LinkedHashSet<String>? = null

    /** Observe the next turn's tool names; null = inject nothing this turn. */
    @Synchronized
    fun next(names: Collection<String>): ToolSetDelta? {
        val next = LinkedHashSet(names)
        val prev = declared
        declared = next
        if (prev == null) return null
        return diffToolSets(prev, next).takeUnless { it.isEmpty }
    }
}

/**
 * Per-VM tracker holder, keyed by session id so a session switch restarts the
 * baseline. TokenScanMemo pattern: ChatViewModelAgentLoopExt sits at its
 * architecture line ceiling, so the state lives next to the logic that uses it
 * instead of as a new ChatViewModel property.
 */
private val vmToolTrackers =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<ChatViewModel, Pair<String, ToolSetTracker>>())

/**
 * Turn-boundary declaration entry point (pi declareToolChanges parity). Call
 * it AFTER [commitSessionNoticesOnce] and BEFORE the request history is
 * built — the committed note then rides THIS request, exactly the ordering pi
 * achieves by pushing declared messages into the context before streaming.
 */
internal suspend fun ChatViewModel.declareToolSetDeltaIfChanged(tools: List<AgentToolDefinition>) {
    val sid = activeSessionId
    if (sid.isBlank()) return
    val tracker = synchronized(vmToolTrackers) {
        val current = vmToolTrackers[this]
        if (current != null && current.first == sid) current.second
        else ToolSetTracker().also { vmToolTrackers[this] = sid to it }
    }
    val delta = tracker.next(tools.mapTo(LinkedHashSet()) { it.name }) ?: return
    commitHiddenUserNote(ToolSetDeltaNotice.text(delta))
}
