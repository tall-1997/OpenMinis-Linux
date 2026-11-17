package com.openminis.app.ui.chat

/**
 * [T-process-summary-duration] Formats an aggregated process duration for the
 * fold-bar suffix. Mirrors the OmniBot run-group header granularity: whole
 * seconds under a minute, m+s under an hour, h+m beyond. Returns null for
 * non-positive totals so the bar simply omits the suffix (blocks without a
 * recorded duration must not render "0s").
 *
 * [T-arch-ratchet] Extracted from ChatAssistantMessageUI.kt when the merged
 * thinking-block work pushed that file past its baseline ceiling: both
 * helpers here are pure and already pinned by ChatFlatItemsProcessFoldTest, so
 * the file they lived in could shed 60+ lines without moving any behaviour.
 *
 * [T-android-format-converge] Body moved to [formatDurationCompact] (the
 * chat package's single duration ladder); this name stays as the
 * truncating, hours-branching, null-on-non-positive profile the fold-bar
 * and its tests pin.
 */
internal fun formatProcessDuration(totalMs: Long): String? =
    formatDurationCompact(
        totalMs,
        subSecondDecimals = 0,
        hoursBranch = true,
        nullWhenNonPositive = true,
    )

/**
 * [P-process-card] What the card is doing right now. Pure so the header
 * copy is testable without composition.
 *
 *  - a settled run reads "思考 N · 工具 M · 时长" — the counts stay so a
 *    finished run is still scannable; no ✅ emoji (user direction: the
 *    check mark reads as noise next to the status icon);
 *  - a running run, collapsed, is the fixed "AI 过程" label with the
 *    counts, with the duration ticking;
 *  - a running run, expanded, drops the counts: the steps are on screen,
 *    so the header is only the current state.
 *
 * "输出中" is deliberately absent. Reply text ends the card on arrival, so
 * that state would render for a single frame and then vanish.
 */
/**
 * [P-process-card][T-process-card-i18n] Pre-resolved localized labels for
 * [processCardHeader]. The function stays pure (JVM-test pinned) — the
 * composable caller resolves the strings via stringResource; the old
 * hardcoded Chinese literals rendered Chinese headers in every locale.
 */
internal data class ProcessCardHeaderLabels(
    val thinking: String,
    val tools: String,
    val aiProcess: String,
    val thinkingNow: String,
    val callingTool: String,
)

internal fun processCardHeader(
    thinkingCount: Int,
    toolCount: Int,
    running: Boolean,
    expanded: Boolean,
    activity: String,
    activityTitle: String,
    duration: String?,
    labels: ProcessCardHeaderLabels,
): String {
    val counts = buildString {
        append(labels.thinking).append(" ").append(thinkingCount)
        append(" · ").append(labels.tools).append(" ").append(toolCount)
        if (duration != null) append(" · ").append(duration)
    }
    val state = when (activity) {
        "thinking" -> labels.thinkingNow
        "tool" -> if (activityTitle.isBlank()) labels.callingTool else "${labels.callingTool} $activityTitle"
        else -> ""
    }
    return when {
        // Settled (reply text arrived, or a pure-tool/error/timeout round
        // ended): always the done summary.
        !running -> counts
        // Running + expanded: the steps are on screen, so the header shows
        // only the live state; fall back to the generic label when idle.
        expanded -> state.ifEmpty { labels.aiProcess }
        // Running + collapsed: fixed label with ticking duration. The live
        // state stays out — that is what the expanded header is for.
        else -> "${labels.aiProcess} · $counts"
    }
}
