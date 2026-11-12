package com.openminis.app.ui.chat

import android.util.Log
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
): List<LLMMessage> {
    val summary = _compactSummary.value
    val marker = _cachedLatestMarker
    // No compact in play → return full history untouched.
    if (summary.isNullOrBlank() || marker == null) {
        return maybeProgressivelyTrimmed(agentHistory.toList(), applyProgressiveTrim)
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
                        "summary+tail injection (tail=${DETACHED_TAIL_MESSAGES})",
                )
            }
            return maybeProgressivelyTrimmed(
                detachedCompactHistory(effectiveSummaryWrappedText),
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
        val walkBack = walkBackUserTurnsBounded(
            anchorIdx = anchorIdx,
            maxUserTextTurns = keepN,
            maxMessages = preAnchorCap,
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
        val tailBudget = PostAnchorTail.Budget(
            budgetTokens = PostAnchorTail.BUDGET_TOKENS,
            estimate = WalkBack::estimateMessageTokens,
        )
        val postAnchorOutcome = PostAnchorTail.apply(postAnchorRaw, tailBudget)
        val postAnchor = postAnchorOutcome.kept
        if (postAnchorOutcome.droppedRounds > 0) {
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

        val firstUserOffset = postAnchor.indexOfFirst { it.role == LLMMessage.Role.USER }
        if (firstUserOffset >= 0) {
            if (firstUserOffset > 0) {
                result.addAll(postAnchor.subList(0, firstUserOffset))
            }
            val target = postAnchor[firstUserOffset]
            // Prepend `<context-summary>...` to the user content. We
            // edit `content` directly because Android LLMMessage uses
            // `content: String` as the canonical text payload; any
            // contentParts the message also carries get preserved.
            val injected = target.copy(
                content = effectiveSummaryWrappedText + "\n\n" + target.content,
            )
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
        return maybeProgressivelyTrimmed(result, applyProgressiveTrim)
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
        stateHydrationPending = true
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
