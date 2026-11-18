package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Result of a bounded walk-back. `priorIdx` is the agentHistory index
 * the caller should use as the start of preAnchor; `null` means even
 * the first user turn including anchor would exceed `maxMessages`, so
 * preAnchor should be empty.
 *
 * Mirrors iOS `WalkBackResult` in AIChatViewModel.swift (8b76cd74).
 */
internal data class WalkBackResult(
    val priorIdx: Int?,
    val userTextTurnsFound: Int,
    val messageCount: Int,
    /** "userTextTargetMet" | "messageCapWouldExceed" | "tokenBudgetWouldExceed" | "reachedStart" | "invalidAnchor" */
    val stopReason: String,
)

/**
 * The bounded walk-back rule, lifted out of the ViewModel so it can be tested
 * with plain lists. [ChatViewModel.walkBackUserTurnsBounded] is the production
 * entry point and passes `agentHistory` straight through.
 */
internal object WalkBack {

    /**
     * [T-compact-token-budget-first] [T-preanchor-dynamic-budget GH#344]
     * Ceiling of the preAnchor compact corridor. Supplied to [bounded] so the
     * TOKEN estimate decides before the message count — an agent-dense
     * session puts 100+ tool rounds between the anchor and the last
     * user-text turn, and a count-only cap made preAnchor permanently empty
     * while the tail budget dropped the uncovered middle on the other side
     * (live: 444k tokens of work memory vanishing per request). Sized WITH
     * the 60k postAnchor tail budget so summary + preAnchor + postAnchor
     * stays under the compact threshold on every realistic window.
     *
     * 40k is the corridor PR #331 declared; the old 15_000 made the
     * "≤20% window" dynamic term dead code for every window ≥ 75k. The
     * actual per-window value comes from [preAnchorTokenBudget] — this
     * constant is only the ceiling and the no-window fallback.
     */
    internal const val COMPACT_PREANCHOR_TOKEN_BUDGET = 40_000

    /** [T-preanchor-dynamic-budget GH#344] Parity §113: `preAnchor(≤20% 窗口)`. */
    internal const val PREANCHOR_WINDOW_FRACTION = 0.20
    internal const val PREANCHOR_TOKEN_FLOOR = 8_000

    /**
     * [T-preanchor-dynamic-budget GH#344] SINGLE source for the preAnchor
     * token budget. ChatViewModelAgentHistoryExt used to inline
     * `min(15_000, max(0.2·window, 8_000))` — the 15k ceiling saturated for
     * every window ≥ 75k (128k/200k models), so the 20% term never
     * influenced the result and the declared 40k corridor was dead code.
     * Now the no-window fallback AND every windowed call share this one
     * floor / fraction / ceiling triple. Pure arithmetic — no Android or
     * context dependency — so JVM unit tests assert the whole corridor.
     */
    internal fun preAnchorTokenBudget(windowTokens: Int?): Int {
        if (windowTokens == null) return COMPACT_PREANCHOR_TOKEN_BUDGET
        val fractionBudget = (windowTokens * PREANCHOR_WINDOW_FRACTION).toInt()
        // [T-budget-floor-fraction-clamp] The floor is a usability minimum
        // for large windows, never a license to exceed the declared ≤20%
        // share: on a 32k window the old 8k floor forced 25%, and on smaller
        // windows worse — breaking the corridor invariant the assembly doc
        // promises ("summary + preAnchor + postAnchor stays under the 80%
        // compact threshold on every model"). The floor now applies only
        // when it sits BELOW the fraction (large windows, unchanged); small
        // windows take the fraction itself.
        return fractionBudget
            .coerceAtLeast(minOf(PREANCHOR_TOKEN_FLOOR, fractionBudget))
            .coerceAtMost(COMPACT_PREANCHOR_TOKEN_BUDGET)
    }

    internal const val STOP_USER_TEXT_TARGET_MET = "userTextTargetMet"
    internal const val STOP_MESSAGE_CAP_WOULD_EXCEED = "messageCapWouldExceed"
    internal const val STOP_TOKEN_BUDGET_WOULD_EXCEED = "tokenBudgetWouldExceed"
    internal const val STOP_REACHED_START = "reachedStart"
    internal const val STOP_INVALID_ANCHOR = "invalidAnchor"

    /**
     * Walk back from `anchorIdx` toward 0, deciding ONLY at user-message
     * boundaries whether to include the next round. Stops when:
     * - we've collected `maxUserTextTurns` user-text turns (success), OR
     * - including the next user round would push total messages over
     *   `maxMessages` (cap reason — don't split a user/assistant/tool round
     *   in the middle, otherwise a tool_use would be orphaned without its
     *   tool_result), OR
     * - [maxEstimatedTokens] is set and the slice would exceed it
     *   ([T-compact-keep-budget] — the token axis opencode's `keep` models;
     *   message-turn counts alone let a few huge tool results blow past the
     *   budget), OR
     * - we hit index 0 (start of history).
     *
     * Port of iOS `walkBackUserTurnsBounded` (AIChatViewModel.swift, 8b76cd74).
     */
    fun bounded(
        history: List<LLMMessage>,
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
        maxEstimatedTokens: Int? = null,
    ): WalkBackResult {
        if (anchorIdx < 0 || anchorIdx >= history.size) {
            return WalkBackResult(null, 0, 0, STOP_INVALID_ANCHOR)
        }
        var acceptedPriorIdx: Int? = null
        var acceptedUserTextTurns = 0
        var acceptedMessageCount = 0
        // [T-walkback-streaming-budget] Accumulate the candidate slice while
        // walking left instead of pre-computing a prefix array over the WHOLE
        // history. The prefix pass was O(anchor) on every request and could
        // not stop early; a token-heavy tail now aborts after a few messages.
        var candidateTokens = 0

        var i = anchorIdx
        while (i >= 0) {
            val msg = history[i]
            if (maxEstimatedTokens != null) {
                candidateTokens += estimateMessageTokens(msg)
            }
            if (msg.role != LLMMessage.Role.USER) {
                i -= 1
                continue
            }
            // [T-android-compact-orphan-toolcall] A user message CARRYING a
            // tool result is the second half of a round, not the start of one.
            // This walk-back's whole premise is that `role == USER` marks a
            // round boundary — but tool results are themselves persisted as
            // USER messages (see the agentHistory.add at the end of the tool
            // dispatch loop), so stopping on one cuts between an assistant's
            // tool_use and its own tool_result. The call is then discarded with
            // pre-history while the result survives in preAnchor and goes out
            // alone, which every OpenAI-compatible provider answers with
            //     400 No tool call found for function call output with call_id …
            // and, since the slice is recomputed identically on every retry and
            // fallback, the session wedges permanently. Port of iOS c7f6a299e.
            if (msg.contentParts.any { it is AgentContentPart.ToolResult }) {
                i -= 1
                continue
            }
            // [T-compact-token-budget-first] Token budget decides BEFORE the
            // message cap: in an agent-dense session the anchor can sit behind
            // 100+ tool rounds with zero user-text turns, and letting the
            // message cap return first made preAnchor permanently empty — the
            // work memory between the summary and the tail simply vanished
            // (observed live: 605-message run, 432 rounds dropped by the tail
            // budget, walkBack returning 0 turns every request). The token
            // estimate covers the same risk the cap guarded against (a few
            // huge tool results), with an actual budget instead of a count.
            if (maxEstimatedTokens != null && candidateTokens > maxEstimatedTokens) {
                return WalkBackResult(
                    priorIdx = acceptedPriorIdx,
                    userTextTurnsFound = acceptedUserTextTurns,
                    messageCount = acceptedMessageCount,
                    stopReason = STOP_TOKEN_BUDGET_WOULD_EXCEED,
                )
            }
            val candidateMessageCount = anchorIdx - i + 1
            if (candidateMessageCount > maxMessages && maxEstimatedTokens == null) {
                // No token budget supplied → the message count is the only
                // guard (legacy behaviour preserved for such callers).
                return WalkBackResult(
                    priorIdx = acceptedPriorIdx,
                    userTextTurnsFound = acceptedUserTextTurns,
                    messageCount = acceptedMessageCount,
                    stopReason = STOP_MESSAGE_CAP_WOULD_EXCEED,
                )
            }
            // Accept this user as the new tentative priorIdx.
            acceptedPriorIdx = i
            acceptedMessageCount = candidateMessageCount
            val hasText = msg.content.isNotBlank() ||
                msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }
            if (hasText) {
                acceptedUserTextTurns += 1
                if (acceptedUserTextTurns >= maxUserTextTurns) {
                    return WalkBackResult(
                        priorIdx = acceptedPriorIdx,
                        userTextTurnsFound = acceptedUserTextTurns,
                        messageCount = acceptedMessageCount,
                        stopReason = STOP_USER_TEXT_TARGET_MET,
                    )
                }
            }
            i -= 1
        }
        return WalkBackResult(
            priorIdx = acceptedPriorIdx,
            userTextTurnsFound = acceptedUserTextTurns,
            messageCount = acceptedMessageCount,
            stopReason = STOP_REACHED_START,
        )
    }

    /**
     * [T-compact-keep-budget] Coarse per-message token estimate (chars / 4).
     *
     * Deliberately the same approximation the send path already logs
     * (`approxTokens = chars / 4`) and that `CompactAttempt.maxOutFor` uses, so
     * the budget decision and the reported context size cannot disagree by
     * using two different rulers.
     *
     * [T-budget-ruler-complete] Tool-call ARGUMENTS and image payloads are
     * part of the request too. Leaving them out let a 12-call build round
     * (huge `file_write` / `file_edit` inputs) count as ~12 tokens against a
     * 60k budget, so the "budgeted" request could still blow a 128k window.
     * Arguments are serialized here exactly as the wire does; images use the
     * same 1,500-token weight as `estimateMessagesTokens`.
     */
    fun estimateMessageTokens(msg: LLMMessage): Int {
        var chars = msg.content.length
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.Text -> chars += part.text.length
                is AgentContentPart.ToolResult -> {
                    chars += part.content.length
                    if (part.imageData != null) chars += IMAGE_CHARS
                }
                is AgentContentPart.ToolUse ->
                    // [T-token-scan-memo] reuse the same per-object memo as the
                    // send-path estimator: this runs for every budget pass, and
                    // input.toString() is a linear copy of multi-KB args.
                    chars += TokenScanMemo.toolUseInputJson(part.input).length
                is AgentContentPart.ImageData -> chars += IMAGE_CHARS
            }
        }
        return (chars / 4).coerceAtLeast(1)
    }

    /** Same image weight as ChatViewModel.estimateMessagesTokens (1,500 tok). */
    private const val IMAGE_CHARS = 6_000
}

internal fun ChatViewModel.walkBackUserTurnsBounded(
    anchorIdx: Int,
    maxUserTextTurns: Int,
    maxMessages: Int,
    maxEstimatedTokens: Int? = null,
): WalkBackResult = WalkBack.bounded(
    history = agentHistory,
    anchorIdx = anchorIdx,
    maxUserTextTurns = maxUserTextTurns,
    maxMessages = maxMessages,
    maxEstimatedTokens = maxEstimatedTokens,
)
