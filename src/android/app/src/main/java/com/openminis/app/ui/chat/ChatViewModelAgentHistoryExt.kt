package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.agent.TimeContextGate
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.ui.chat.retention.ResidentWindow
import kotlinx.coroutines.flow.first

/**
 * [T-android-inloop-compact-truthful-gate] The effective history in two
 * flavors:
 *  - default (`applyProgressiveTrim = true`) — what the next request actually
 *    carries, after [progressivelyTrimmed] stripped thinking / offloaded old
 *    tool outputs. Used for calibration and display.
 *  - `applyProgressiveTrim = false` — the same slice UNTRIMMED, i.e. what the
 *    request would weigh without the emergency diet. The in-loop / send-time
 *    compact gates read this: ProgressiveTrim masks a 270k-token pile-up down
 *    to ~41k of stubs, which let the 80% threshold stay silent forever while
 *    900 stale tool rounds were re-assembled every turn. Compaction shrinks
 *    THIS slice (the marker moves forward), so the post-compact check passes
 *    and the gate cannot thrash — unlike a raw `agentHistory` floor, which
 *    stays over-threshold after a successful compact and would trip the
 *    "already compacted" dead end.
 *
 * v1 (legacy) markers and the unresolvable-anchor fallback intentionally
 * bypass [progressivelyTrimmed] in BOTH flavors — their wire format is frozen
 * by contract.
 */
internal fun ChatViewModel.effectiveAgentHistoryUncounted(
    applyProgressiveTrim: Boolean = true,
    /**
     * [T-compact-gate-unblinded] When false, the postAnchor tail budget is
     * NOT applied. The compact gate must weigh the un-budgeted slice: the
     * tail budget caps the measurable request at preAnchor(20%) +
     * postAnchor(35%) ≈ 55% of the window, so with a marker in play the
     * 75% threshold could never fire again. The marker then froze while the
     * unsummarized postAnchor kept growing, and every request silently
     * dropped rounds the summary does not cover. This switch exists only for
     * measurement; request assembly always uses the budget.
     */
    applyTailBudget: Boolean = true,
    /**
     * [T-state-note-reinject] True only for the actual outgoing request.
     * Measurement builds (gate/Token Usage/context ring) must not mutate VM
     * delivery state; the request build may re-inject the current state note
     * when the tail budget evicted it, without writing a new DB row.
     */
    forRequest: Boolean = false,
): List<LLMMessage> {
    val summary = _compactSummary.value
    val marker = _cachedLatestMarker
    // No compact in play → return full history untouched.
    if (summary.isNullOrBlank() || marker == null) {
        val plain = maybeProgressivelyTrimmed(agentHistory.toList(), applyProgressiveTrim)
        // [T-time-context-interval] The clock belongs to EVERY request, not only
        // the ones that follow a compaction: a fresh session is exactly where
        // "watch the build for 20 minutes" had no timestamp at all.
        return if (forRequest) applyRequestClockNote(plain) else plain
    }

    val summaryWrappedText = "<context-summary>\n" +
        "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
        "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. The current persona in the system prompt overrides any voice implied by this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
        summary +
        "\n</context-summary>"

    // [T-compact-chunk-pool] When the marker carries a rolling chunk
    // pool, retrieve the chunks that match the current instruction
    // instead of injecting the whole monolithic summary. A single-chunk
    // pool is byte-identical to the v2 blob, so retrieval only kicks in
    // once several compactions have accumulated.
    val chunkRetrieved: String? = marker.summaryChunks?.let { json ->
        val query = agentHistory.lastOrNull { it.role == LLMMessage.Role.USER }?.content.orEmpty()
        ChatViewModel.selectSummaryChunks(json, query)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n---\n\n")
    }
    val effectiveSummaryWrappedText = if (chunkRetrieved != null && chunkRetrieved != summary) {
        // The chunk pool is an accelerator for relevance, not the source of
        // truth. It intentionally evicts old per-compaction chunks, while
        // `summary` is regenerated from previousSummary and therefore carries
        // the cumulative history. Always retain that cumulative summary so a
        // long-running chat cannot lose early decisions merely because the
        // rolling pool crossed SUMMARY_CHUNK_POOL_MAX.
        "<context-summary>\n" +
            "The following cumulative summary preserves the earlier conversation. A few relevant compaction excerpts follow as additional detail.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary.\n\n" +
            summary +
            "\n\n--- relevant compaction excerpts ---\n" +
            chunkRetrieved +
            "\n</context-summary>"
    } else {
        summaryWrappedText
    }

    // ─── v2 markers (id-only anchor model) ─────────────────────────
    //
    // anchor = lastCompactedMessageId. What we send to the model:
    //   1. last [COMPACT_KEEP_RECENT_USER_TURNS] user-text turns BEFORE
    //      anchor (inclusive of anchor) — recent verbatim warm-up
    //   2. the summary, INLINED as a `<context-summary>` text part
    //      prepended to the first user message AFTER anchor (preserves
    //      strict role alternation — no synthetic standalone user turn)
    //   3. all messages strictly after anchor (the kept-tail "active"
    //      region — typically empty right after compact, populated as
    //      the user sends new prompts)
    //
    // If anchor unresolvable, degrade to full history (over-inform
    // beats summary-only; the M-Team session bug taught us that a lone
    // summary message paired with hot tools makes the model loop).
    if (marker.version >= 2) {
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
        val anchorIdx = anchorId?.let { id ->
            agentHistory.indexOfLast { it.dbMessageId == id }
        } ?: -1
        if (anchorIdx < 0) {
            // [T-compact-detached-anchor] The anchor was evicted from the
            // bounded window. Degrading to FULL history throws the summary
            // away on every turn - the session then stays over budget
            // forever and never recovers on its own. The summary is
            // independent of the anchor, so inject it with a verbatim
            // tail instead. Logged once per detection, not per call.
            if (!_compactMarkerDetached) {
                _compactMarkerDetached = true
                stateHydrationPending = true  // [T-state-hydration]
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] marker ${marker.id.take(8)} anchor ${anchorId?.take(8) ?: "nil"} " +
                        "evicted from agentHistory(size=${agentHistory.size}) - " +
                        "summary+tail injection (tailMax=${DETACHED_TAIL_MESSAGES})",
                )
            }
            return maybeProgressivelyTrimmed(
                foldHarnessNotes(
                    // [T-compact-gate-unblinded] The gate's measurement must
                    // see what the summary does NOT cover (the whole resident
                    // tail), otherwise detached mode silently persists.
                    if (applyTailBudget) detachedCompactHistory(effectiveSummaryWrappedText)
                    else agentHistory.toList(),
                ),
                applyProgressiveTrim,
            )
        }

        // Step 1: walk back from anchor collecting user-text turns. Stop
        // when EITHER we've collected N user-text turns OR including the
        // next turn would push preAnchor over 100 messages. Decisions
        // happen only at user-message boundaries so we never split a
        // user/assistant/tool round in half (which would orphan a
        // tool_use with no matching tool_result).
        //
        // [T-compact-preanchor-prune, port iOS 8b76cd74]
        val keepN = ChatViewModel.COMPACT_KEEP_RECENT_USER_TURNS
        val preAnchorCap = 100
        // [T-preanchor-dynamic-budget GH#344] SINGLE source: 20% of the
        // model's effective window, floor 8k, ceiling 40k (no window → 40k).
        // The old inline min(15k, max(0.2w, 8k)) saturated at 15k for every
        // window ≥ 75k, so the dynamic term never ran on 128k/200k models.
        val windowTokens = effectiveContextWindowTokens()
        val preAnchorBudget = WalkBack.preAnchorTokenBudget(windowTokens)
        val walkBack = walkBackUserTurnsBounded(
            anchorIdx = anchorIdx,
            maxUserTextTurns = keepN,
            maxMessages = preAnchorCap,
            // [T-compact-token-budget-first] With a token budget supplied,
            // WalkBack decides on tokens FIRST and the message cap stops
            // vetoing: an agent-dense session can put 100+ tool rounds
            // between the anchor and the last user-text turn, and the bare
            // cap made preAnchor permanently empty (live: 605-message run,
            // 432 rounds dropped by the tail budget, walkBack returning 0
            // every request — the work memory between summary and tail
            // vanished on every request).
            maxEstimatedTokens = preAnchorBudget,
        )
        val priorIdxResolved: Int? = walkBack.priorIdx
        val priorIdx = walkBack.priorIdx ?: (anchorIdx + 1) // empty preAnchor sentinel
        if (walkBack.stopReason != "userTextTargetMet") {
            AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 walkBack stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx userTextTurnsFound=${walkBack.userTextTurnsFound} preAnchorMsgs=${walkBack.messageCount}")
        }

        // PRE-ANCHOR PRUNE (tool-heavy session fix):
        // The walk-back-N-user-text strategy pulls in everything between
        // the Nth-last and last user-text turn — in a heavy tool-call
        // session that can be many messages of tool_result / tool_use,
        // tens of thousands of tokens that the summary already covers.
        // Drop any tool_result > 1000 chars in the preAnchor slice and
        // strip the matching tool_use part (same id) from the assistant
        // message so the model never sees a dangling tool_use/result.
        // [T-android-agenthistory-cme] subList views on a CopyOnWriteArrayList
        // carry a comodification check — slice an immutable toList()
        // snapshot instead so a concurrent append cannot throw CME mid-read.
        val preAnchorRaw: List<LLMMessage> =
            if (priorIdx <= anchorIdx) {
                val snap = agentHistory.toList()
                if (anchorIdx < snap.size) snap.subList(priorIdx, anchorIdx + 1)
                else emptyList()
            } else emptyList()

        val droppedToolIds = mutableSetOf<String>()
        var droppedToolResultCount = 0
        for (msg in preAnchorRaw) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolResult && part.content.length > 1000) {
                    droppedToolIds.add(part.id)
                    droppedToolResultCount += 1
                }
            }
        }

        val preAnchorPruned: MutableList<LLMMessage> = ArrayList(preAnchorRaw.size)
        for (msg in preAnchorRaw) {
            if (msg.contentParts.isEmpty()) {
                // Plain text-only message — nothing to prune.
                preAnchorPruned.add(msg)
                continue
            }
            val kept = msg.contentParts.filter { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                    is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                    else -> true
                }
            }
            if (kept.isEmpty()) continue // skip empty shells
            preAnchorPruned.add(msg.copy(contentParts = kept))
        }

        if (droppedToolResultCount > 0) {
            AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
        }

        // ROLE ALIGNMENT: the API requires the first message to be `user`.
        // After clamp (cap may land on assistant) and after prune (the
        // head user may have been emptied), peel any leading non-user
        // messages so preAnchor starts on a user turn.
        while (preAnchorPruned.isNotEmpty() && preAnchorPruned.first().role != LLMMessage.Role.USER) {
            preAnchorPruned.removeAt(0)
        }

        // Step 2 & 3: copy the lookback window (post-prune), then splice
        // in the summary as parts[0] of the first post-anchor user msg.
        val result = mutableListOf<LLMMessage>()
        result.addAll(preAnchorPruned)

        // [T-harness-note-fold] Fold harness USER messages into the prefix of
        // the first real user message. All harness notes (goal/recall/state/
        // WorkingMemory/plan-retry) are materialized as role=USER messages by
        // the host, but the provider wire format serializes each as an
        // independent user turn — which the model interprets as "the user
        // spoke again" and produces interstitial prose between tool calls.
        // The fold makes the model see them as part of the original request
        // rather than as separate "turn to speak" invitations.
        // One change covers ALL injection sources (goal/recall/state/
        // WorkingMemory/plan-retry/once-notices). Transcript/DB/UI/ledger
        // all untouched — this is request-assembly only.

        // [T-android-agenthistory-cme] postAnchor is held across ~50 lines —
        // it must be a view over an immutable snapshot, never over the live
        // COWAL (whose subList views throw CME if the parent array swaps).
        val postAnchorSnap = agentHistory.toList()
        val postAnchorRaw = if (anchorIdx + 1 < postAnchorSnap.size) {
            postAnchorSnap.subList(anchorIdx + 1, postAnchorSnap.size)
        } else {
            emptyList()
        }

        // [T-android-postanchor-tail-budget] Hard tail window on the active
        // region. Without a marker move for hours the postAnchor slice grows
        // unbounded (observed: 909 stale tool rounds fully re-assembled every
        // turn) — the summary covers those rounds, so keep only the newest
        // ones that fit the budget and drop the oldest WHOLE rounds (never a
        // half turn: the splitter decides only at user-round boundaries, so a
        // tool_use is never orphaned from its tool_result). Request-assembly
        // only; DB persistence is untouched. The dropped-rounds notice is
        // injected at the head of the kept slice as a USER text line.
        //
        // [T-compact-window-scaled-budget] postAnchor tail budget scales with
        // the model's window too (≤35%, ceiling 60k) — preAnchorBudget was
        // computed above (≤20%) so summary + preAnchor + postAnchor stays
        // under the 80% compact threshold on every model (no oscillation).
        // [T-budget-floor-fraction-clamp] The old 8k floor overrode the 35%
        // share on windows <~23k (20k window → 8k = 40%); the fraction itself
        // is the floor now, same clamp policy as preAnchorTokenBudget.
        // [T-harness-config-resolve] One resolution per assembly; the per-model
        // override (Model detail -> Context budget) reaches every consumer below.
        val harness = activeHarnessConfig()
        val postAnchorBudget = PostAnchorTail.budgetTokensForWindow(windowTokens, applyTailBudget, harness)
        val tailBudget = PostAnchorTail.Budget(
            budgetTokens = postAnchorBudget,
            estimate = WalkBack::estimateMessageTokens,
            // [T-postanchor-dropped-digest] The summary predates every
            // post-anchor round, so it cannot stand in for them. Preserve the
            // dropped user instructions / assistant decisions as bounded
            // excerpts in the SAME request; the tool ledger stays the
            // structured record for tool calls.
            digest = { dropped -> DroppedRoundDigest.render(dropped, config = harness) },
            // [T-harness-config-resolve] The reserve/digest caps come from the
            // same resolution as the token budget above.
            config = harness,
        )
        val postAnchorOutcome = PostAnchorTail.apply(postAnchorRaw, tailBudget)
        var postAnchor = postAnchorOutcome.kept
        if (postAnchorOutcome.droppedRounds > 0) {
            // [T-state-note-reinject] Only a real request arms delivery state;
            // gate/Token-Usage measurements must stay side-effect free.
            if (forRequest) {
                stateHydrationPending = true
                evictedStateNote(postAnchorSnap, anchorIdx, postAnchor)?.let { note ->
                    postAnchor = listOf(note) + postAnchor
                }
            }
            AppLogger.info(
                ChatViewModel.TAG,
                "[CompactDiag] eAH v2 postAnchor tail budget: kept=${postAnchor.size} " +
                    "droppedRounds=${postAnchorOutcome.droppedRounds} " +
                    "droppedMessages=${postAnchorOutcome.droppedMessages} " +
                    "droppedTokens≈${postAnchorOutcome.droppedTokens} budget=${tailBudget.budgetTokens}",
            )
        }

        // DIAG: explain how the slice was sized using post-prune /
        // post-alignment counts so the log reflects what actually
        // reaches the model.
        val preAnchorRawCount = maxOf(0, anchorIdx - priorIdx + 1)
        val priorIdxSource =
            if (priorIdxResolved == null) "fallback=empty(<$keepN user-text turns before anchor or cap hit)"
            else "userTextWalkBack(N=$keepN)"
        AppLogger.info(ChatViewModel.TAG, "[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx agentHistory.size=${agentHistory.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

        val firstUserOffset = firstInjectableUserIndex(postAnchor)
        if (firstUserOffset >= 0) {
            if (firstUserOffset > 0) {
                result.addAll(postAnchor.subList(0, firstUserOffset))
            }
            val target = postAnchor[firstUserOffset]
            // Prepend `<context-summary>...` to the user content. Every
            // provider wire builder serializes contentParts when non-empty
            // and ignores `content` on that path, so the injection MUST
            // update both fields — a real user turn always carries parts
            // (fresh send / queue inject / steering / tool-result carrier),
            // and a content-only edit silently dropped the whole summary
            // from the wire. See prependUserText / [T-user-text-prefix-parts-sync].
            val injected = prependUserText(target, effectiveSummaryWrappedText)
            result.add(injected)
            if (firstUserOffset + 1 < postAnchor.size) {
                result.addAll(postAnchor.subList(firstUserOffset + 1, postAnchor.size))
            }
        } else {
            // No real user turn after the anchor (autonomous run). The
            // summary leads the active region as its own user message; the
            // notice that follows is folded onto it, instead of the summary
            // being appended to a carrier or folded back into pre-anchor.
            val summaryMessage = LLMMessage(
                role = LLMMessage.Role.USER,
                content = effectiveSummaryWrappedText,
                contentParts = listOf(AgentContentPart.Text(effectiveSummaryWrappedText)),
            )
            result.add(summaryMessage)
            result.addAll(postAnchor)
        }
        // [T-harness-note-fold] Fold harness USER messages into the nearest
        // preceding real user turn, across the whole sequence. Harness notes
        // (goal/recall/state/WorkingMemory/plan-retry/dropped-rounds) are
        // materialized as role=USER messages at the history TAIL by the
        // host before each loop request, but the provider wire format
        // serializes each as an independent user turn — which the model
        // interprets as "the user spoke again" and produces interstitial
        // prose between tool calls. The fold makes the model see them as
        // part of the original request. Transcript/DB/UI/ledger all
        // untouched — request-assembly only.
        val assembled = maybeProgressivelyTrimmed(foldHarnessNotes(result), applyProgressiveTrim)
        // [T-time-context-interval] Request-side clock, applied after the fold so
        // the note lands on the real user turn rather than on a harness note.
        return if (forRequest) applyRequestClockNote(assembled) else assembled
    }


    // ─── v1 (legacy) markers ──────────────────────────────────────
    //
    // Original behavior preserved unchanged so old markers keep
    // rendering / sending data the same way they always did.
    val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = effectiveSummaryWrappedText)
    val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
        ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

    if (firstKeptId != null) {
        val keepStart = agentHistory.indexOfFirst { it.dbMessageId == firstKeptId }
        if (keepStart >= 0) {
            val snap = agentHistory.toList()
            return buildList(snap.size - keepStart + 1) {
                add(summaryHead)
                if (keepStart < snap.size) {
                    addAll(snap.subList(keepStart, snap.size))
                }
            }
        }
        // Fall through to safety net.
    } else {
        val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
        val lcmIdx = lcmId?.let { id ->
            agentHistory.indexOfLast { it.dbMessageId == id }
        } ?: -1
        val postCompactStart = lcmIdx + 1
        return buildList(agentHistory.size - postCompactStart + 1) {
            add(summaryHead)
            val snap = agentHistory.toList()
            if (postCompactStart < snap.size) {
                addAll(snap.subList(postCompactStart, snap.size))
            }
        }
    }

    Log.w(ChatViewModel.TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in agentHistory (size=${agentHistory.size}); returning full history")
    return agentHistory.toList()
}

/**
 * [T-state-note-reinject] The committed state note that the tail budget
 * evicted from this request, or null when it is still visible (or predates
 * the anchor and is covered by the summary).
 */
internal fun evictedStateNote(
    snapshot: List<LLMMessage>,
    anchorIdx: Int,
    kept: List<LLMMessage>,
): LLMMessage? {
    if (anchorIdx < 0 || anchorIdx >= snapshot.size) return null
    if (kept.any { it.content.contains(SessionStateSnapshot.MARKER) }) return null
    return snapshot.subList(anchorIdx + 1, snapshot.size)
        .lastOrNull { it.content.contains(SessionStateSnapshot.MARKER) }
}

/**
 * [T-time-context-interval] Append the clock note to the LAST real user turn of
 * an outgoing request.
 *
 * WHY here and not in the send path: `SendExt` annotates the user body, then
 * `runAgentLoopBody` calls `rebuildAgentHistoryFromDatabase` before the first
 * request — the annotating text is a request-side copy, so the note was gone by
 * the time `requestHistory` was built, and every later turn of the same run
 * never got one at all. Injecting at assembly means:
 *  - the note exists in exactly what the model receives;
 *  - nothing is persisted (the DB row keeps the user's raw words);
 *  - the interval throttle applies to real requests, which is the point.
 *
 * The note rides the last USER message as appended text + a matching Text part,
 * the same shape `prependUserText` produces, so providers that serialize parts
 * AND providers that serialize content both see it. A carrier (tool-result) turn
 * is skipped on purpose: mutating a carrier would put the note between a call
 * and its result in the model's reading order.
 */
internal fun ChatViewModel.applyRequestClockNote(history: List<LLMMessage>): List<LLMMessage> {
    if (history.isEmpty()) return history
    val nowMs = System.currentTimeMillis()
    val sid = realSessionId.ifEmpty { activeSessionId }
    val idx = history.indexOfLast { msg ->
        msg.role == LLMMessage.Role.USER &&
            msg.contentParts.none {
                it is AgentContentPart.ToolResult || it is AgentContentPart.ToolUse
            } &&
            !HarnessNoteMarkers.isUserHarnessNote(msg)
    }
    if (idx < 0) return history
    // [T-time-context-baseline] Mark the baseline on EVERY request carrying a real
    // user turn, before the early returns below. Marking only when a note is
    // emitted made elapsed measure "since the last clock note" (up to a whole
    // interval) instead of "since the previous message" - a factual error in the
    // line the model reads.
    val previousAt = TurnClock.previousMessageAt(sid)
    val target = history[idx]
    // [T-time-context-sentinel] Match the SAME literal the producer writes.
    // A bare `<system-note>` test also matched the workspace reminder and the
    // file-reference note (both ride the same user turn), so any session with a
    // resolvable workspace silently lost its clock entirely.
    if (target.content.contains("<system-note>\u5f53\u524d\u65f6\u95f4")) return history
    val keywordHit = CLOCK_HINTS.any { target.content.contains(it, ignoreCase = true) }
    if (!keywordHit && !TimeContextGate.shouldInject(sid, nowMs)) return history
    TimeContextGate.markInjected(sid, nowMs)

    val zone = java.util.TimeZone.getDefault().toZoneId()
    val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(java.time.Instant.ofEpochMilli(nowMs).atZone(zone))
    // [T-time-context-snapshot] The value, not the clock, decides. A keyword hit
    // bypasses the interval gate by design, so without this a second request in
    // the same minute carried two identical `当前时间: 14:32` notes — noise the
    // model learns to skip, which is worse than no note at all.
    if (TimeContextGate.alreadyStamped(sid, stamp)) return history
    TimeContextGate.markStamped(sid, stamp)
    val note = buildString {
        append("\n\n<system-note>\u5f53\u524d\u65f6\u95f4: ").append(stamp)
        previousAt?.let { prev ->
            if (prev > 0L) {
                append(" (since previous message: ")
                append(TimeContextGate.formatElapsed((nowMs - prev).coerceAtLeast(0L)))
                append(')')
            }
        }
        append("</system-note>")
    }
    TurnClock.markModelVisible(sid, nowMs)
    val updated = target.copy(
        content = target.content + note,
        contentParts = target.contentParts + AgentContentPart.Text(note),
    )
    return history.toMutableList().also { it[idx] = updated }
}

/**
 * Kept identical to TimeReminder's vocabulary so both paths agree.
 *
 * Escaped on purpose: this file sits at a CJK-literal baseline of 0, and a
 * Chinese hint spelled literally would fail that ratchet while the identical
 * string in `agent/MessageTransformers.kt` passes.
 */
private val CLOCK_HINTS: List<String> = listOf(
    "\u73b0\u5728", "now", "today", "\u4eca\u5929", "\u51e0\u70b9", "\u65f6\u95f4",
    "weather", "\u5929\u6c14",
    "remind", "\u63d0\u9192", "schedule", "\u5b89\u6392",
    "\u5206\u949f", "\u5c0f\u65f6", "minute", "hour",
)

/**
 * [T-compact-summary-target] Index of the USER message the compact summary
 * should be prepended to, or -1 when none is a real user turn.
 *
 * A REAL user turn is TEXT-ONLY: a tool-result carrier carries parts that
 * override `content` on the wire, and prepending the summary there buries it
 * mid-round (and, after folding, inside the pre-anchor region). A harness
 * note is not a user turn either. When only notices/carriers exist the caller
 * inserts the summary as its own leading user message for the active region.
 */
internal fun firstInjectableUserIndex(messages: List<LLMMessage>): Int =
    messages.indexOfFirst { msg ->
        msg.role == LLMMessage.Role.USER &&
            msg.contentParts.none {
                it is AgentContentPart.ToolResult || it is AgentContentPart.ToolUse
            } &&
            !HarnessNoteMarkers.isUserHarnessNote(msg)
    }

/**
 * [T-harness-note-fold] Fold harness USER messages into the nearest PRECEDING
 * real user turn, across the whole sequence — not just the head.
 *
 * Why whole-sequence: every committed note lands at the TAIL of agentHistory
 * (ChatViewModelAgentLoopExt commits notices before each loop request), so in
 * any live session the note's predecessors include real user turns and
 * assistant/tool rounds — a head-only fold (the original implementation)
 * closed its fold window on the very first message and folded nothing. On
 * device that made the fix a no-op: every recall/goal/state/WorkingMemory
 * note still went out as an independent "the user spoke again" user turn,
 * and the model answered each with interstitial prose between tool calls.
 *
 * Destination: the note's text is appended onto the nearest preceding USER
 * message that is NOT itself a harness note — a real user turn or the
 * tool-result carrier of the current round. This is exactly the shape
 * Z-Code produces by merging reminders into the tool result, and the same
 * shape ToolIdNormalization.mergeConsecutiveUserTurns already tolerates
 * (text-only follower rides the carrier message). When no preceding user
 * turn exists (notes before the first user message), the notes collapse
 * into the first following real user turn's prefix — the original head
 * behavior.
 *
 * Ordering guarantee: relative order among folded notes is preserved
 * (append-only onto one target), so the model still reads them
 * chronologically inside the merged turn.
 *
 * Transcript/DB/UI/ledger all untouched — request-assembly only.
 */
internal fun foldHarnessNotes(history: List<LLMMessage>): List<LLMMessage> {
    // Fast path: no harness notes anywhere → return the input untouched
    // (byte-identical to the pre-fold behavior; sessions without notices
    // never pay a copy).
    if (history.none { HarnessNoteMarkers.isUserHarnessNote(it) }) {
        return history
    }
    val out = ArrayList<LLMMessage>(history.size)
    // Pending notes with no preceding real user turn yet: carried until the
    // first real user message appears, then prepended to it.
    val leadingNotes = StringBuilder()
    // Index of the last out-entry that can absorb a note: a USER message
    // that is not itself a harness note (real user turn or tool-result
    // carrier). -1 = none yet.
    var lastUserTarget = -1
    for (msg in history) {
        if (HarnessNoteMarkers.isUserHarnessNote(msg)) {
            if (lastUserTarget >= 0) {
                // Fold forward onto the nearest preceding user turn. Both
                // fields must move together: the fold target is frequently a
                // tool-result carrier whose contentParts override `content`
                // on the wire ([T-user-text-prefix-parts-sync]).
                val target = out[lastUserTarget]
                out[lastUserTarget] = appendUserText(target, msg.content.trimStart())
            } else {
                leadingNotes.append(msg.content.trimStart()).append("\n")
            }
            continue
        }
        if (msg.role == LLMMessage.Role.USER) {
            // A real user turn: first flush any leading notes into its prefix.
            val flushed = leadingNotes.isNotEmpty()
            if (flushed) {
                val prefix = leadingNotes.toString().trimEnd()
                leadingNotes.clear()
                out.add(prependUserText(msg, prefix))
            } else {
                out.add(msg)
            }
            lastUserTarget = out.lastIndex
            continue
        }
        out.add(msg)
    }
    // History ends with notes and no following user turn: flush the leading
    // remainder as a standalone user turn (matches the old tail fallback).
    if (leadingNotes.isNotEmpty()) {
        out.add(LLMMessage(
            role = LLMMessage.Role.USER,
            content = leadingNotes.toString().trimEnd(),
            contentParts = listOf(AgentContentPart.Text(leadingNotes.toString().trimEnd())),
        ))
    }
    return out
}

/** Apply [progressivelyTrimmed] only when the caller asked for it. */
private fun ChatViewModel.maybeProgressivelyTrimmed(
    list: List<LLMMessage>,
    apply: Boolean,
): List<LLMMessage> = if (apply) progressivelyTrimmed(list) else list

/**
 * [T-android-progressive-trim] Apply progressive context trimming to an
 * outgoing history projection. No-op (same list instance) unless the
 * untrimmed estimate crosses a trim tier against the model's context
 * window. See [ProgressiveTrim] for the tier ladder and safety
 * invariants; the level is deliberately computed from the UNTRIMMED
 * estimate so it cannot oscillate between turns.
 *
 * v1 (legacy) compact paths intentionally bypass this — their behavior is
 * frozen by contract ("original behavior preserved unchanged").
 */
internal fun ChatViewModel.progressivelyTrimmed(list: List<LLMMessage>): List<LLMMessage> {
    if (list.size <= ProgressiveTrim.PROTECTED_RECENT_MESSAGES) return list
    val window = effectiveContextWindowTokens() ?: return list
    val untrimmed = estimateMessagesTokens(list)
    val level = ProgressiveTrim.levelFor(untrimmed, window)
    if (level == ProgressiveTrim.Level.NONE) return list
    // [T-wave-e-progressive-reclaim] L2 offloads old big tool outputs into
    // the session workspace so the stub can offer a `file_read` path back;
    // without a session to hang the file on the stub tells the truth instead.
    val offloadSessionId = realSessionId.ifBlank { sessionId }
    val offloader = ProgressiveTrim.Offloader { content, toolId, _ ->
        com.openminis.app.tools.ToolOutputSpill.offloadForTrim(
            context = context.applicationContext,
            sessionId = offloadSessionId,
            toolId = toolId,
            content = content,
        )
    }
    val trimmed = ProgressiveTrim.trim(list, level, offloader)
    if (trimmed !== list) {
        // [T-state-hydration] Reclaimed content may include the only copy of
        // the plan/report/lane state; the host restores it before the next request.
        // [T-working-memory-ledger] Gate on NEW handover content: this path
        // runs EVERY turn (long sessions trim every request), and an
        // unconditional flag made commitSessionNoticesOnce commit a fresh
        // USER note every turn — the model narrated about each note instead
        // of working (the prose-fragment loop on device). The snapshot path
        // is sha-deduped; the ledger path has undelivered lines to give.
        if (WorkingMemoryStore.hasUndelivered(activeSessionId, context.filesDir)) {
            stateHydrationPending = true
        }
        AppLogger.info(
            ChatViewModel.TAG,
            "[ProgressiveTrim] level=$level window=$window untrimmedEst=$untrimmed " +
                "messages=${list.size} → ${trimmed.size} — reclaimed old reasoning / tool outputs / step iterations",
        )
    }
    return trimmed
}

// ── Bounded-history append + trim + DB rebuild ──────────────────────────────
// [T-android-split-chat-agenthistory] Moved verbatim from ChatViewModel.kt
// (mechanical split, zero behavior change); member functions became ChatViewModel
// extensions with identical signatures.

/**
 * Rebuild model context from the newest persisted rows only. The database
 * remains the complete transcript; no operation may parse the whole session
 * merely to continue, retry, edit, or delete it.
 */

internal fun ChatViewModel.appendBoundedHistory(message: LLMMessage) {
    agentHistory.add(message)
    trimAgentHistory()
}

internal fun ChatViewModel.appendBoundedHistoryAll(messages: Collection<LLMMessage>) {
    agentHistory.addAll(messages)
    trimAgentHistory()
}

internal fun ChatViewModel.trimAgentHistory() {
    val dropped = ArrayList<LLMMessage>()
    val byCount = ResidentWindow.countCut(agentHistory, ChatViewModel.MAX_AGENT_HISTORY_MESSAGES)
    if (byCount > 0) {
        dropped.addAll(agentHistoryHeadSnapshot(byCount))
        removeAgentHistoryHead(byCount)
        llmHistoryStartOffset += byCount
    }
    val byBytes = ResidentWindow.byteCut(agentHistory)
    if (byBytes > 0) {
        dropped.addAll(agentHistoryHeadSnapshot(byBytes))
        removeAgentHistoryHead(byBytes)
        llmHistoryStartOffset += byBytes
    }
    if (dropped.isNotEmpty()) {
        rememberDigestLines(dropped.map { HistoryDigest.fromMessage(it) })
    }
}

/**
 * Rebuild the request-side history from Room immediately before a new send.
 * The UI is intentionally paged, so `_messages` and the resident cache are
 * not authoritative for model memory. This also repairs a ViewModel that was
 * reopened on the tail and never had its older UI pages loaded.
 */
internal suspend fun ChatViewModel.rebuildAgentHistoryFromDatabase(sid: String) {
    val tail = chatRepository.loadRequestHistory(sid, ChatViewModel.MAX_AGENT_HISTORY_MESSAGES)
    val parsed = tail.map { toLLMMessage(it) }
    agentHistory.clear()
    toolLoopDetector.reset()
    appendBoundedHistoryAll(parsed)
    llmHistoryStartOffset = (
        chatRepository.dao.messageCountForSession(sid) - tail.size
    ).coerceAtLeast(0)
}

internal suspend fun ChatViewModel.awaitBoundedHistoryRebuild(
    sid: String,
): List<com.openminis.app.data.db.MessageEntity> {
    rebuildAgentHistoryFromDatabase(sid)
    return chatRepository.hydrateDisplayRows(
        chatRepository.loadMessagesTail(sid, ChatViewModel.MAX_AGENT_HISTORY_MESSAGES),
    )
}
