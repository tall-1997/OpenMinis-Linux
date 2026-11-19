package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.CompactMarkerView
import com.openminis.app.data.repository.SessionTailRow
import com.openminis.app.data.repository.sessionTailDisplayRows
import com.openminis.app.data.repository.toReceiptHistoryRows
import com.openminis.app.data.repository.toView
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
    history: List<LLMMessage> = agentHistorySnapshot(),
    windowTokens: Int? = effectiveContextWindowTokens(),
    marker: CompactMarkerView? = _cachedLatestMarker?.toView(),
    onUncoveredDrop: (Int) -> Unit = {},
): List<LLMMessage> {
    val summary = marker?.summary
    // No compact in play → return full history untouched.
    if (summary.isNullOrBlank() || marker == null) {
        return maybeProgressivelyTrimmed(history, applyProgressiveTrim, windowTokens)
    }

    // Excerpts supplement, never replace, the cumulative summary. The
    // selected marker carries the asset references on every projection path.
    // [P3-3] An over-budget pool degrades to its newest fitting records
    // instead of being dropped whole.
    val chunkRetrieved = fitSummaryChunks(marker.summaryChunks)?.let { json ->
        val query = history.lastOrNull { it.role == LLMMessage.Role.USER }?.content.orEmpty()
        selectSummaryChunks(json, query).takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n---\n\n")
    }
    val effectiveSummaryWrappedText = buildCompactSummaryEnvelope(summary, chunkRetrieved, marker.assetsJson)

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
        val anchorIdx = anchorId?.let { id -> history.indexOfLast { it.dbMessageId == id } } ?: -1
        if (anchorIdx < 0) {
            // A detached summary is still usable. Do not cap the resident tail by row
            // count: the anchor's eviction does not prove those rows were summarized.
            val tail = buildDetachedCompactHistory(effectiveSummaryWrappedText, history, history.size)
            return maybeProgressivelyTrimmed(foldHarnessNotes(tail), applyProgressiveTrim, windowTokens)
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
        val preAnchorBudget = WalkBack.preAnchorTokenBudget(windowTokens)
        val walkBack = WalkBack.bounded(
            history = history,
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
        val preAnchorRaw = history.subList(priorIdx, anchorIdx + 1)

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
        val postAnchorRaw = history.subList(anchorIdx + 1, history.size)

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
        val postAnchorBudget = PostAnchorTail.budgetTokensForWindow(windowTokens, applyTailBudget)
        val tailBudget = PostAnchorTail.Budget(
            budgetTokens = postAnchorBudget,
            estimate = WalkBack::estimateMessageTokens,
            // [T-postanchor-dropped-digest] The summary predates every
            // post-anchor round, so it cannot stand in for them. Preserve the
            // dropped user instructions / assistant decisions as bounded
            // excerpts in the SAME request; the tool ledger stays the
            // structured record for tool calls.
            digest = DroppedRoundDigest::render,
        )
        val postAnchorOutcome = PostAnchorTail.apply(postAnchorRaw, tailBudget)
        val postAnchor = postAnchorOutcome.kept
        if (postAnchorOutcome.droppedRounds > 0) onUncoveredDrop(postAnchorOutcome.droppedRounds)

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
            // Rare: no user message after anchor. Append everything
            // post-anchor (typically empty) then a standalone summary
            // user turn. Safe — no later user follows it to break
            // alternation.
            result.addAll(postAnchor)
            result.add(LLMMessage(role = LLMMessage.Role.USER, content = effectiveSummaryWrappedText))
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
        return maybeProgressivelyTrimmed(foldHarnessNotes(result), applyProgressiveTrim, windowTokens)
    }


    // ─── v1 (legacy) markers ──────────────────────────────────────
    //
    // Original behavior preserved unchanged so old markers keep
    // rendering / sending data the same way they always did.
    val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = effectiveSummaryWrappedText)
    val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
        ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

    if (firstKeptId != null) {
        val keepStart = history.indexOfFirst { it.dbMessageId == firstKeptId }
        if (keepStart >= 0) return listOf(summaryHead) + history.drop(keepStart)
        // A missing legacy boundary cannot prove that any resident row is covered.
        return history
    }
    val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
    val lcmIdx = lcmId?.let { id -> history.indexOfLast { it.dbMessageId == id } } ?: -1
    return listOf(summaryHead) + history.drop(lcmIdx + 1)
}

/**
 * [T-compact-summary-target] Index of the USER message the compact summary
 * should be prepended to.
 *
 * The postAnchor head is normally the dropped-rounds notice — a harness note.
 * Prepending the summary to THAT message made [foldHarnessNotes] fold the
 * notice (summary included) back into the nearest preceding real user turn,
 * which lives inside the already-summarized pre-anchor region: the active
 * tail then started with no summary at all, and the carrier briefly grew a
 * summary-shaped "user" message. Prefer the first REAL user turn after the
 * notice; fall back to any USER message only when none exists.
 */
internal fun firstInjectableUserIndex(messages: List<LLMMessage>): Int {
    val real = messages.indexOfFirst {
        it.role == LLMMessage.Role.USER && !HarnessNoteMarkers.isUserHarnessNote(it)
    }
    if (real >= 0) return real
    return messages.indexOfFirst { it.role == LLMMessage.Role.USER }
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

/** Keep reasoning reclamation pure. Tool-result shortening belongs to the shared request layers. */
private fun maybeProgressivelyTrimmed(
    list: List<LLMMessage>,
    apply: Boolean,
    windowTokens: Int?,
): List<LLMMessage> {
    if (!apply || list.size <= ProgressiveTrim.PROTECTED_RECENT_MESSAGES || windowTokens == null) return list
    val estimated = com.openminis.app.data.ContextTokenMeter.rawMessagesTokens(list)
    return if (ProgressiveTrim.levelFor(estimated, windowTokens) == ProgressiveTrim.Level.NONE) list
    else ProgressiveTrim.trim(list, ProgressiveTrim.Level.STRIP_OLD_THINKING)
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
    reconcileReceiptRows(sid, tail.toReceiptHistoryRows())
    val parsed = tail.map { toLLMMessage(it) }
    agentHistory.clear()
    toolLoopDetector.reset()
    llmHistoryStartOffset = (chatRepository.dao.messageCountForSession(sid) - tail.size).coerceAtLeast(0)
    appendBoundedHistoryAll(parsed)
}

internal suspend fun ChatViewModel.awaitBoundedHistoryRebuild(
    sid: String,
): List<SessionTailRow> {
    rebuildAgentHistoryFromDatabase(sid)
    return chatRepository.sessionTailDisplayRows(sid, ChatViewModel.MAX_AGENT_HISTORY_MESSAGES)
}
