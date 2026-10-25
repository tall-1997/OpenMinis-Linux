package com.openminis.app.ui.chat

import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel.PreSendContextAction
import com.openminis.app.R

/**
 * Consult [ContextPolicy] before sending. Returns true to proceed. The
 * Android MVP doesn't surface a "Compact before send" dialog (iOS does),
 * so we only warn via [appendSystemInfo] at the `needsCompact` /
 * `exhausted` boundaries and still allow the send. That gives the user
 * a signal to invoke `/compact` explicitly without blocking their turn.
 */
internal fun ChatViewModel.checkContextBeforeSend(): PreSendContextAction {
    val tokens = contextTokensForPolicy()
    if (tokens <= 0) return PreSendContextAction.PROCEED
    // [T-context-window-live-read] Live window (entry re-resolved + group
    // contextLimitTokens folded in) — not the currentModel snapshot.
    val window = effectiveContextWindowTokens() ?: return PreSendContextAction.PROCEED
    val policy = ContextPolicy.forContextWindow(window, effectiveCompactPercent())
    return when (policy.check(tokens, window)) {
        ContextPolicy.CheckResult.OK -> PreSendContextAction.PROCEED

        // Mirrors iOS AIChatViewModel.swift:2224. Previously Android only
        // appended a notice here and sent anyway, which meant the very
        // request that tripped the threshold still went out over-length —
        // the warning arrived alongside the failure it was meant to avoid.
        ContextPolicy.CheckResult.NEEDS_COMPACT -> {
            if (effectiveAutoCompact()) {
                AppLogger.info(
                    ChatViewModel.TAG,
                    "[Context] pre-send near capacity ($tokens / $window) — auto-compacting (pref on)",
                )
                PreSendContextAction.COMPACT_THEN_SEND
            } else {
                AppLogger.info(
                    ChatViewModel.TAG,
                    "[Context] pre-send near capacity ($tokens / $window) — prompting user",
                )
                PreSendContextAction.ASK_USER
            }
        }

        // Exhausted tiers have compactThreshold = 0 by policy: the window is
        // too small for a summary to pay for itself, so compacting is not
        // on offer. Keep the advisory-and-proceed behaviour rather than
        // blocking the user out of their own chat. (An automatic last-ditch
        // compact here was tried and reverted: it silently dropped context
        // the user could still see, which reads as data loss.)
        ContextPolicy.CheckResult.EXHAUSTED -> {
            appendSystemInfo(
                text = context.getString(R.string.vm_context_near_limit, tokens, window),
                iconKind = "compact",
            )
            PreSendContextAction.PROCEED
        }
    }
}
