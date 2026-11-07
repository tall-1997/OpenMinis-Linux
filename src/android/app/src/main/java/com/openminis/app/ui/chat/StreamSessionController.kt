package com.openminis.app.ui.chat

import com.openminis.app.sandbox.kernel.UIBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * [P1-12] Incremental side-channel publish switch.
 *
 * When true (default), a delta whose tool blocks are element-wise UNCHANGED
 * since the previously published delta (the "pure text append, no tool-block
 * interleaving" case) reuses the already-published immutable block list
 * instead of snapshotting the caller's list twice per publish (once here,
 * once more inside [publish] and again in the SnapshotMutableStateFlow
 * boundary). The published [StreamingDelta] is byte-for-byte identical:
 * element-wise-equal data-class instances and equal-content lists produce
 * equal values, and all reused lists are unmodifiable snapshots.
 *
 * Set to false to restore the previous full-snapshot path verbatim (one-line
 * rollback); with the switch off every code path below behaves exactly as
 * before the optimization. A @Volatile var (not a const) so the equivalence
 * test can exercise both paths in one process; production never writes it —
 * the ONLY writer is StreamingIncrementalPublishTest, which flips the switch
 * per test to pin both paths. (This is why the var lives in production code
 * while having no production writer: the guarded paths below are the
 * kill-switch rollback.)
 */
@Volatile
internal var STREAM_INCREMENTAL_PUBLISH = true

/**
 * Owns the streaming side-channel + dual-path flush that used to live inside
 * [ChatViewModel]. Behavior is unchanged: live tokens write [_streamingById],
 * canonical [_messages] is only mutated on structural/error/end paths.
 */
internal class StreamSessionController(
    private val scope: CoroutineScope,
    private val messages: MutableStateFlow<List<ChatMessage>>,
    private val streamingById: MutableStateFlow<Map<String, StreamingDelta>>,
    private val newlineFlushMinChars: Int,
    private val newlineFlushMaxLen: Int,
) {
    /**
     * [T-android-stream-flush-dualpath] Per-message streaming-flush state for
     * the dual-path throttle in [updateAssistantMessage]. Keyed by messageId so
     * the throttle accumulator survives the high-frequency token calls (the
     * earlier per-fragment produceState version reset every fragment rebuild and
     * so never actually throttled — diagnostics showed every tick flushing).
     * Mirrors iOS AIChatViewModel+SSEStream's lastTextDeltaFlush/…Length.
     */
    private class StreamFlushState {
        var lastFlushMs: Long = 0L
        var lastFlushedLen: Int = 0
        // The `st.trailingJob === coroutineContext[Job]` ownership guards in
        // updateAssistantMessage are only sound because every read/write of
        // this field happens on one thread: `scope` is viewModelScope
        // (Dispatchers.Main.immediate), so ticks and trailing jobs are
        // serialized and never interleave mid-statement. Move this state
        // off-main and the guard becomes a plain data race needing a CAS or a
        // mutex instead.
        var trailingJob: Job? = null
        // [T-android-stream-flush-review] Freshest suppressed delta. Updated on
        // EVERY throttled tick so the trailing job publishes the latest content
        // (not the stale value captured when the job was first scheduled) — a
        // burst of sub-throttle deltas followed by a pause would otherwise leave
        // the side channel several deltas behind.
        var pendingContent: String? = null
        var pendingBlocks: List<AssistantBlock> = emptyList()
        var pendingAwaiting: Boolean = false
    }

    private val streamFlushStates = HashMap<String, StreamFlushState>()

    /**
     * [T-perf-background-session] Background-session pause gate. When true the
     * controller keeps accumulating deltas (into each message's pendingContent)
     * but does NOT publish them to [_streamingById], so a background chat that is
     * still streaming no longer drives recomposition / message-list copies while
     * the user is elsewhere. The turn's canonical persist still runs at stream
     * end. [resumePublishing] flushes every parked delta and clears the gate.
     */
    @Volatile
    var publishPaused: Boolean = false

    /**
     * [T-android-stream-flush-review] Cancel a message's pending trailing flush
     * and drop its throttle accumulator. Call from EVERY stream-termination
     * path (natural end, cancel, turn-limit, retry-truncate, clearChat) so a
     * trailing coroutine — which runs on viewModelScope, NOT streamJob, and is
     * therefore NOT cancelled by streamJob.cancel() — can't fire after the
     * side channel was drained and re-revive a stale "thinking" overlay row.
     */
    fun clearStreamFlushState(id: String) {
        streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

    fun clearAllStreamFlushStates() {
        streamFlushStates.values.forEach { it.trailingJob?.cancel() }
        streamFlushStates.clear()
    }

    /**
     * [T-perf-background-session] Clear the background gate and drain every
     * parked delta into [_streamingById] in one go. Called when the chat becomes
     * the foreground session again, so the transcript catches up to the live
     * stream without a per-token recomposition storm while it was backgrounded.
     */
    fun resumePublishing() {
        if (!publishPaused) return
        publishPaused = false
        for ((id, st) in streamFlushStates) {
            val pc = st.pendingContent
            if (pc == null) continue
            st.pendingContent = null
            streamingById.value = streamingById.value + (
                id to StreamingDelta(
                    content = pc,
                    toolBlocks = snapshotAssistantBlocks(st.pendingBlocks),
                    isAwaitingModelResponse = st.pendingAwaiting,
                )
            )
        }
    }

    /** Cancel + drop flush states for any message id NOT in [keptIds] (retry/truncate). */
    fun retainStreamFlushStates(keptIds: Set<String>) {
        val drop = streamFlushStates.keys.filter { it !in keptIds }
        for (id in drop) streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

    // Dual-path flush thresholds — ported from iOS. Time tiers scale with total
    // length; the newline fast-path flushes immediately on a line break once
    // enough new chars have accumulated, gated to short docs so dense
    // box-drawing streams don't pin the flush rate to the per-token cadence.
    // [T-android-throttle-ladder-converge] Ladder body moved to
    // [StreamingThrottle.ladderFor]; the flush surface uses the default 200ms
    // first tier. [T-android-tail-sprint] A large unflushed backlog (gateway
    // tail burst / smooth-forwarding relay) clamps the tier to the sprint
    // cadence so the display chases the arrival.
    fun streamFlushThrottleMs(len: Int, unflushedChars: Int): Long =
        StreamingThrottle.throttleFor(len, unflushedChars)

    fun updateAssistantMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    ) {
        // T-streaming-side-channel: during a live turn, write high-frequency
        // fields into [_streamingById] instead of mutating the canonical
        // message list. This keeps the `messages` StateFlow reference stable
        // across the turn so ChatScreen's top-level reads
        // (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't trigger
        // a full recompose of the screen-sized mega-composable on every token.
        //
        // On stream end (isStreaming=false), drain the accumulated delta
        // back into the canonical message in a single `_messages` emit, then
        // clear the side-channel entry so post-turn reads (history rebuild,
        // persist, agent loop) see the canonical truth.
        if (isStreaming) {
            val prev = streamingById.value[id]
            // [P1-12] Incremental path: when the caller's block list is
            // element-wise equal to the previously published one (pure text
            // append, no tool-block change of any field), reuse that immutable
            // published list instead of allocating a fresh snapshot. Equality
            // is exact (AssistantBlock is a data class, so ALL fields
            // participate), which is a strictly stronger check than the
            // size/status/content scans below — when it holds, those scans
            // would all report "unchanged" too, so skipping them is
            // behavior-equivalent. With [STREAM_INCREMENTAL_PUBLISH] off, the
            // unconditional snapshot below restores the original path.
            val blocksUnchanged = STREAM_INCREMENTAL_PUBLISH &&
                prev != null && toolBlocks == prev.toolBlocks
            val toolBlocksImmutable = if (blocksUnchanged) {
                prev!!.toolBlocks
            } else {
                snapshotAssistantBlocks(toolBlocks)
            }

            // [T-android-stream-flush-dualpath] Dual-path flush at the
            // message-accumulation layer (NOT per-fragment, which never
            // throttled). Decide whether to publish this delta now:
            //   • structural change (toolBlocks count / awaiting flag) →
            //     publish immediately — these drive tool-bubble UI and must
            //     never be coalesced away or the bubble state stalls.
            //   • else time-path: enough ms since last publish for this length.
            //   • else newline fast-path: a line break in the newly-streamed
            //     chunk + ≥50 new chars, gated to short docs (iOS parity).
            // When none fire, stash the latest as a trailing publish so the
            // final chunk before a pause still lands; a fresh delta cancels
            // and replaces it.
            val st = streamFlushStates.getOrPut(id) {
                StreamFlushState().also { it.lastFlushedLen = 0 }
            }
            // [T-android-stream-flush-review] Structural change also covers an
            // in-place tool-block STATUS flip (running → success), not just a
            // count change — otherwise a spinner→checkmark could lag up to one
            // throttle tier. Compare a cheap (kind,status) fingerprint.
            // [P1-12] When [blocksUnchanged] held, every field already matches
            // the published list, so both change scans are provably false and
            // are skipped without allocating.
            val toolStatusChanged = !blocksUnchanged && prev != null &&
                prev.toolBlocks.size == toolBlocksImmutable.size &&
                toolBlocksImmutable.indices.any { i ->
                    prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
                }
            val toolContentChanged = !blocksUnchanged && prev != null &&
                prev.toolBlocks.size == toolBlocksImmutable.size &&
                toolBlocksImmutable.indices.any { i ->
                    prev.toolBlocks[i].content != toolBlocksImmutable[i].content
                }
            val structuralChange = prev == null ||
                (!blocksUnchanged && prev.toolBlocks.size != toolBlocksImmutable.size) ||
                prev.isAwaitingModelResponse != isAwaitingModelResponse ||
                toolStatusChanged ||
                toolContentChanged
            val now = System.currentTimeMillis()
            val elapsed = now - st.lastFlushMs
            val newChunk = if (content.length > st.lastFlushedLen) {
                content.substring(st.lastFlushedLen.coerceAtMost(content.length))
            } else ""
            val unflushed = content.length - st.lastFlushedLen
            val throttle = streamFlushThrottleMs(content.length, unflushed)
            val newlineFlush = content.length < newlineFlushMaxLen &&
                newChunk.contains('\n') &&
                unflushed >= newlineFlushMinChars

            /**
             * @return true when the delta reached the side channel now, false
             * when the UI bus was full and it was parked for the trailing job.
             * A caller running INSIDE a trailing job must not clear state on the
             * false path — see [T-android-stream-flush-reentry].
             */
            fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean): Boolean {
                if (publishPaused) {
                    // Background gate: park the freshest delta, don't touch the
                    // side channel. resumePublishing() drains it on foreground.
                    st.pendingContent = text
                    st.pendingBlocks = blocks
                    st.pendingAwaiting = awaiting
                    return false
                }
                val at = System.currentTimeMillis()
                if (!UIBus.admit(at, criticalEvent = false)) {
                    st.pendingContent = text
                    st.pendingBlocks = blocks
                    st.pendingAwaiting = awaiting
                    val wait = UIBus.waitMs(at, criticalEvent = false).coerceAtLeast(16L)
                    // [T-android-stream-flush-reentry] Reaching this branch from
                    // inside a trailing job means st.trailingJob IS the job now
                    // executing. Cancelling it is harmless (nothing suspends
                    // after this point), but the enclosing body then used to run
                    // its own cleanup: nulling the freshly scheduled job handle
                    // and its pendingContent. The replacement job fired, read
                    // null, and returned — so the last throttled delta vanished
                    // from the side channel until the next delta or the stream
                    // end, with nothing in the logs.
                    st.trailingJob?.cancel()
                    st.trailingJob = scope.launch {
                        delay(wait)
                        val pc = st.pendingContent ?: run {
                            // Nothing left to flush. Release the handle only if
                            // a re-entrant park has not already replaced it —
                            // the `trailingJob == null` guard otherwise refuses
                            // to schedule the next delta and parked text would
                            // sit unpublished until the stream ends.
                            if (st.trailingJob === coroutineContext[Job]) st.trailingJob = null
                            return@launch
                        }
                        if (publish(pc, st.pendingBlocks, st.pendingAwaiting)) {
                            st.pendingContent = null
                            if (st.trailingJob === coroutineContext[Job]) st.trailingJob = null
                        }
                    }
                    return false
                }
                streamingById.value = streamingById.value + (
                    id to StreamingDelta(
                        content = text,
                        // [P1-12] Every list reaching [publish] is already an
                        // unmodifiable snapshot: a fresh [snapshotAssistantBlocks]
                        // result, the previously published list reused by the
                        // incremental path, or a stashed [StreamFlushState
                        // .pendingBlocks] (one of the former two). Re-snapshotting
                        // an immutable list is a pure copy — skip it under the
                        // switch; the SnapshotMutableStateFlow boundary still
                        // applies its own snapshot, so the published value is
                        // unchanged. With the switch off the original
                        // re-snapshot runs.
                        toolBlocks = if (STREAM_INCREMENTAL_PUBLISH) {
                            blocks
                        } else {
                            snapshotAssistantBlocks(blocks)
                        },
                        isAwaitingModelResponse = awaiting,
                    )
                )
                st.lastFlushMs = System.currentTimeMillis()
                st.lastFlushedLen = text.length
                return true
            }

            if (structuralChange || elapsed >= throttle || newlineFlush) {
                st.trailingJob?.cancel()
                st.trailingJob = null
                st.pendingContent = null
                publish(content, toolBlocksImmutable, isAwaitingModelResponse)
            } else {
                // Throttled: always record this delta as the freshest pending
                // value, so whenever the trailing job fires it publishes the
                // latest text — not whatever was captured when it was first
                // scheduled (review #2). Schedule the job only once.
                st.pendingContent = content
                st.pendingBlocks = toolBlocksImmutable
                st.pendingAwaiting = isAwaitingModelResponse
                if (st.trailingJob == null) {
                    val wait = (throttle - elapsed).coerceAtLeast(16L)
                    st.trailingJob = scope.launch {
                        delay(wait)
                        val pc = st.pendingContent ?: run {
                            // Nothing left to flush. Release the handle only if
                            // a re-entrant park has not already replaced it —
                            // the `trailingJob == null` guard otherwise refuses
                            // to schedule the next delta and parked text would
                            // sit unpublished until the stream ends.
                            if (st.trailingJob === coroutineContext[Job]) st.trailingJob = null
                            return@launch
                        }
                        // Only clear state this coroutine still owns.
                        if (publish(pc, st.pendingBlocks, st.pendingAwaiting)) {
                            st.pendingContent = null
                            if (st.trailingJob === coroutineContext[Job]) st.trailingJob = null
                        }
                    }
                }
            }
            // [T-android-timeout-while-running] If a transient banner
            // (`message.error`) is still on the canonical assistant message
            // when a fresh streaming event arrives, the banner is stale —
            // the model is producing again, by construction the prior
            // transient timeout / retry / fallback has been resolved.
            // Clear it in the same mutation. setTransientInlineError /
            // setInlineError are the only paths that write `error`; the
            // terminal path (setInlineError) sets isStreaming=false on the
            // same message in the same emit, so it cannot reach this
            // branch and the clear is safe.
            //
            // 𝙓𝙄𝙉 TG36302 (0.10): user saw a red "timeout / retry" banner
            // glued to the bottom of the conversation while the agent
            // continued running (LM Studio tool loop on 30/30, "Minis is
            // thinking" indicator). Caused by (a) the fallback-switch branch in
            // runAgentLoop not calling clearInlineError(), and (b) the
            // streaming-side-channel writing every subsequent delta into
            // _streamingById without ever touching _messages where
            // `error` lives. (a) is fixed at the fallback site; (b) is
            // fixed here defensively so any future write-path that forgets
            // to clear can't strand a stale banner across the rest of
            // the turn.
            val canonical = messages.value
            val canonicalIdx = canonical.indexOfLast { it.id == id }
            if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
                val updated = canonical.toMutableList()
                updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
                messages.value = snapshotChatMessages(updated)
            }
            return
        }
        // [T-android-stream-flush-dualpath] Stream end → cancel any pending
        // trailing flush and drop the throttle accumulator for this message;
        // the canonical drain below publishes the final, complete text.
        clearStreamFlushState(id)
        // Stream end → sync delta into canonical message + clear side-channel.
        val current = messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx < 0) {
            // The message itself is gone (e.g. clearChat raced ahead) —
            // just clear any leftover stream delta and bail.
            if (streamingById.value.containsKey(id)) {
                streamingById.value = streamingById.value - id
            }
            return
        }
        val updated = current.toMutableList()
        updated[idx] = current[idx].copy(
            content = content,
            isStreaming = false,
            toolBlocks = snapshotAssistantBlocks(toolBlocks),
            isAwaitingModelResponse = isAwaitingModelResponse,
        )
        messages.value = snapshotChatMessages(updated)
        if (streamingById.value.containsKey(id)) {
            streamingById.value = streamingById.value - id
        }
    }

    /**
     * The freshest known delta for [id]: the parked [StreamFlushState
     * .pendingContent] when one exists, else the last PUBLISHED side-channel
     * delta. pendingContent is updated on EVERY suppressed tick — a throttled
     * trailing publish AND every tick parked by the [publishPaused] gate,
     * which never touches the side channel at all — so whenever it is set it
     * is at least as fresh as the published delta. The length comparison only
     * guards a pathological mid-turn content shrink, where the published
     * (longer) value stays the safer flush.
     */
    private fun freshestDeltaFor(id: String): StreamingDelta? {
        val st = streamFlushStates[id]
        val pending = st?.pendingContent
        if (st != null && pending != null) {
            val published = streamingById.value[id]
            if (published == null || pending.length >= published.content.length) {
                return StreamingDelta(
                    content = pending,
                    toolBlocks = snapshotAssistantBlocks(st.pendingBlocks),
                    isAwaitingModelResponse = st.pendingAwaiting,
                )
            }
        }
        return streamingById.value[id]
    }

    /**
     * Force-drain any outstanding streaming delta for [id] back into the
     * canonical message and clear the side-channel slot. Called from turn
     * exit paths (cancel / error / retry / resume / clearChat) so the
     * canonical message reflects all accumulated content even if the last
     * [updateAssistantMessage] call had isStreaming=true.
     *
     * [review 2.1 background-pause-gate] The flushed delta prefers the parked
     * pendingContent over the published side-channel entry: while the
     * background gate holds, fresh content ONLY accumulates in pendingContent
     * (publish() parks without touching [_streamingById]), so flushing the
     * published value wrote pre-pause text back into the bubble and
     * clearStreamFlushState then dropped everything streamed since — the
     * stopped bubble visibly regressed. The same preference covers the
     * foreground window where the last delta sits unpublished in a trailing
     * job at stop time.
     */
    fun flushStreamingDelta(id: String) {
        val delta = freshestDeltaFor(id) ?: return
        val current = messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx >= 0) {
            val updated = current.toMutableList()
            updated[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            messages.value = snapshotChatMessages(updated)
        }
        // [T-android-stream-flush-review] Cancel the pending trailing flush
        // BEFORE clearing the side channel — otherwise its viewModelScope
        // coroutine (not cancelled by streamJob.cancel) fires later and
        // re-adds the orphan side-channel entry, reviving a stale "thinking"
        // row after the turn was stopped/drained.
        clearStreamFlushState(id)
        streamingById.value = streamingById.value - id
    }

    /** Drain ALL outstanding streaming deltas (called on global resets). */
    fun flushAllStreamingDeltas() {
        // Snapshot the freshest delta per id BEFORE clearing the flush
        // states: clearAllStreamFlushStates drops pendingContent, and a
        // delta parked by the background gate or a trailing job at this
        // moment is the freshest content the turn produced (same rationale
        // as [flushStreamingDelta]). streamFlushStates keys are included so
        // a stream that started under the pause gate — never published to
        // the side channel at all — still drains into the canonical list.
        val freshest = (streamingById.value.keys + streamFlushStates.keys)
            .associateWith { freshestDeltaFor(it) }
        clearAllStreamFlushStates()
        if (freshest.isEmpty()) return
        val current = messages.value.toMutableList()
        var changed = false
        for ((id, delta) in freshest) {
            if (delta == null) continue
            val idx = current.indexOfLast { it.id == id }
            if (idx < 0) continue
            current[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            changed = true
        }
        if (changed) messages.value = snapshotChatMessages(current)
        streamingById.value = emptyMap()
    }
}
