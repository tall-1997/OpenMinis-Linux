package com.openminis.app.ui.chat

import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel.InLoopContextAction
import com.openminis.app.R

/**
 * [T-android-auto-compact-inloop] Max in-loop compactions per runAgentLoop.
 * Bounds compact-thrash within a single turn; the MAX_AGENT_TURNS ceiling is
 * never reset by compaction, so this is a second, tighter backstop.
 */
private val maxInLoopCompactions = 3

/**
 * [T-android-auto-compact-inloop] Re-evaluate [ContextPolicy] between agent
 * iterations and act on it (iOS f70ac173).
 *
 * Why this exists: [checkContextBeforeSend] only runs at the SEND entry
 * point. A single turn that fans out into many tool iterations can cross the
 * compact/exhausted thresholds mid-loop, and offload alone cannot recover
 * when the bulk is the model's own text — the turn then slams into the
 * provider's context ceiling.
 *
 * Blocks until the compaction attempt settles, because the next API call
 * must read the freshly-compacted history.
 */
internal suspend fun ChatViewModel.inLoopContextCheck(compactionsSoFar: Int): InLoopContextAction {
    val tokens = contextTokensForPolicy()
    if (tokens <= 0) return InLoopContextAction.PROCEED
    val window = effectiveContextWindowTokens() ?: return InLoopContextAction.PROCEED
    val policy = ContextPolicy.forContextWindow(window, effectiveCompactPercent())
    return when (policy.check(tokens, window)) {
        ContextPolicy.CheckResult.OK -> InLoopContextAction.PROCEED

        ContextPolicy.CheckResult.NEEDS_COMPACT -> {
            if (compactionsSoFar >= maxInLoopCompactions) {
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[AutoCompact] still over threshold after $compactionsSoFar compaction(s) — stopping",
                )
                return InLoopContextAction.STOP
            }
            // NOTE: deliberately NOT gated on AutoCompactPrefs. That flag
            // governs the SEND-time decision (compact silently vs. ask
            // first) — mid-loop there is nobody to ask, and the alternative
            // to compacting is aborting the user's turn outright. iOS makes
            // the same call: its in-loop branch
            // (AIChatViewModel.swift:4739) never consults
            // autoCompactEnabled either.
            AppLogger.info(
                ChatViewModel.TAG,
                "[AutoCompact] mid-loop compact #${compactionsSoFar + 1}: $tokens / $window tokens " +
                    "(autoCompactPref=${com.openminis.app.data.AutoCompactPrefs.isEnabled()}, not a gate here)",
            )
            appendSystemInfo(
                text = context.getString(R.string.vm_context_filling_compacting, tokens, window),
                iconKind = "compact",
            )
            val ok = awaitCompaction()
            if (!ok) return InLoopContextAction.STOP
            // [T-android-auto-compact-inloop] Invalidate the stale reading.
            // `_lastTurnContextTokens` is only refreshed by a usage chunk,
            // which needs a COMPLETED API call — but this path compacts and
            // `continue`s without one. Leaving the pre-compaction value in
            // place made the very next iteration read the same number and
            // compact again immediately, burning the whole budget in
            // seconds (observed on device: two compactions 3s apart, both
            // logging an identical 66358). Zeroing it makes the guard
            // PROCEED once, so the next real response measures the
            // post-compaction size and the decision is made on fresh data.
            _lastTurnContextTokens.value = 0
            InLoopContextAction.COMPACTED
        }

        // EXHAUSTED is only ever returned by `exhaustedOnly` tiers — windows
        // under 64K, where ContextPolicy sets compactThreshold = 0 precisely
        // BECAUSE the window is too small for auto-compact to pay for itself
        // (the summary plus re-appended recent turns would eat the headroom
        // it just freed). Attempting a "rescue" compaction here would
        // contradict the policy, so stop and let the user decide.
        ContextPolicy.CheckResult.EXHAUSTED -> {
            AppLogger.warning(
                ChatViewModel.TAG,
                "[AutoCompact] exhausted on a no-auto-compact tier ($tokens / $window) — stopping",
            )
            InLoopContextAction.STOP
        }
    }
}

