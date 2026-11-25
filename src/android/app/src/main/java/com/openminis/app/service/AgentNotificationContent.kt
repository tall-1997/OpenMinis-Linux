package com.openminis.app.service

import androidx.annotation.DrawableRes
import com.openminis.app.R
import com.openminis.app.ui.chat.friendlyToolTitleFor
import com.openminis.app.ui.chat.toolIconResFor

/**
 * [T-android-live-update-content] Pure content model for the ongoing agent
 * notification / Android 16 Live Update chip. Everything here is a function
 * of tracker state so it can be unit-tested without a Service or a device,
 * and so the notification and the floating overlay resolve the same
 * phase from the same inputs.
 *
 * The phases mirror what the iOS Dynamic Island and the Android overlay
 * capsule already distinguish:
 *  - [COMPLETED]  the run finished and the notification is resting
 *  - [TOOL]       a tool call is executing (icon/title identify the tool)
 *  - [THINKING]   the model is emitting reasoning, no visible text yet
 *  - [GENERATING] the model is streaming the visible reply
 *  - [IDLE]       service alive with nothing running (presence only)
 *  - [WAITING]    nothing running, a chat waits for its next scheduled run
 *                 ([T-port-sched-wait-notification], iOS fa7adaf9e's waiting
 *                 card): counts down to the run and KEEPS Stop, which is the
 *                 only way to turn the schedule off from the notification
 *  - [ASKING]     a user_ask question waits for the user ([T-android-user-ask-chip]):
 *                 its own icon and text, the clock counting down to the
 *                 question's timeout, so a backgrounded user sees it needs them
 *                 instead of a "Running" that never ends
 */
internal enum class AgentPhase { COMPLETED, TOOL, THINKING, GENERATING, IDLE, WAITING, ASKING }

internal fun resolveAgentPhase(
    isCompleted: Boolean,
    toolName: String?,
    isThinking: Boolean,
    hasActiveSessions: Boolean,
    isScheduledWaiting: Boolean = false,
    isAwaitingAnswer: Boolean = false,
): AgentPhase = when {
    // [T-android-user-ask-chip] First: the run is suspended on the user, and
    // that is what the chip must say - not the user_ask tool row, not
    // another chat's streaming, not a scheduled wait.
    isAwaitingAnswer -> AgentPhase.ASKING
    // [T-port-sched-wait-notification] Before COMPLETED: between fires the
    // previous run's finish stamp is still set, which used to rest the
    // notification on "Task completed" with no Stop.
    isScheduledWaiting && !hasActiveSessions -> AgentPhase.WAITING
    isCompleted -> AgentPhase.COMPLETED
    toolName != null -> AgentPhase.TOOL
    !hasActiveSessions -> AgentPhase.IDLE
    isThinking -> AgentPhase.THINKING
    else -> AgentPhase.GENERATING
}

/**
 * Small icon per phase. Notification small icons must be monochrome alpha
 * masks — the system tints them — so these are the app's own `ic_tool_*`
 * vectors (white fill), the same set the overlay capsule draws. The legacy
 * `android.R.drawable.ic_menu_*` bitmaps used before are anti-aliased grey
 * artwork that renders as an indistinct blob at status-bar size, which is
 * what the ColorOS chip in the user's recording showed.
 */
@DrawableRes
internal fun notificationSmallIconFor(phase: AgentPhase, toolName: String?, waitHasClock: Boolean = true): Int = when (phase) {
    // iOS: calendar clock while counting down, hourglass for an on-completion
    // wait (no time to count down to).
    AgentPhase.WAITING -> if (waitHasClock) R.drawable.ic_notification_scheduled else R.drawable.ic_notification_hourglass
    AgentPhase.COMPLETED -> R.drawable.ic_notification_completed
    AgentPhase.TOOL -> toolIconResFor(toolName)
    AgentPhase.THINKING -> R.drawable.ic_tool_psychology
    AgentPhase.ASKING -> R.drawable.ic_notification_question
    AgentPhase.GENERATING, AgentPhase.IDLE -> R.drawable.ic_launcher_monochrome
}

/**
 * Title for the [AgentPhase.TOOL] row: the model-supplied `tool_title`
 * ("Open Baidu home page") when there is one, otherwise the per-tool label
 * the overlay uses ("Execute Shell"). Both are far shorter than the old
 * "Minis Ultra is using Shell" sentence, which matters on OEM chips that fall
 * back to the content title (see [chipTimerText]).
 */
internal fun toolRowTitle(toolName: String, toolTitle: String?): String =
    toolTitle?.takeIf { it.isNotBlank() } ?: friendlyToolTitleFor(toolName)

/**
 * The tracker's default status line is the raw `"Running: <tool_name>"`.
 * Replace that exact default with the friendly tool label; keep any richer
 * status a tool reported itself (offload handlers write their own).
 */
internal fun humanizeToolStatus(status: String, toolName: String?): String {
    if (toolName == null) return status
    if (status != "Running: $toolName") return status
    return friendlyToolTitleFor(toolName)
}

/**
 * Compact elapsed text for the status-bar chip: `m:ss`, growing to `h:mm:ss`
 * past an hour. Kept ≤ 7 characters — the length `setShortCriticalText` is
 * documented to display without truncation.
 */
internal fun chipTimerText(elapsedMs: Long): String {
    val total = (elapsedMs / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

/**
 * [T-port-sched-wait-notification] The WAITING status line, iOS's waiting-card
 * format: "Next run · 2/30", or just "Next run" for a one-shot.
 */
internal fun waitStatusText(nextRunLabel: String, runCounter: String?): String =
    runCounter?.let { "$nextRunLabel · $it" } ?: nextRunLabel

/** When / chronometer settings of the ongoing notification for one phase. */
internal data class NotificationClock(
    val showWhen: Boolean,
    val whenMs: Long,
    val usesChronometer: Boolean,
    val countDown: Boolean,
)

/**
 * The notification's clock. Running phases count up from the run's start;
 * COMPLETED freezes at the finish. [T-port-sched-wait-notification] WAITING
 * counts DOWN to the next run with the system Chronometer
 * (setChronometerCountDown), so the countdown costs no per-second notify; with
 * no time to count down to (an on-completion wait, or a run already due) it
 * shows no clock rather than a frozen or negative one.
 */
internal fun notificationClockFor(
    phase: AgentPhase,
    runStartWallMs: Long,
    finishedWallMs: Long,
    nextRunWallMs: Long?,
    nowWallMs: Long,
    presenceOnly: Boolean = false,
    askExpiresWallMs: Long? = null,
): NotificationClock = when {
    // [T-fgs-presence-no-chip] Nothing has run: there is no task to time, and
    // a clock counting service uptime reads as an agent at work.
    presenceOnly ->
        NotificationClock(showWhen = false, whenMs = nowWallMs, usesChronometer = false, countDown = false)
    // [T-android-user-ask-chip] Counts down to the question's timeout, like
    // WAITING counts down to the next run; no clock for a question that waits
    // indefinitely.
    phase == AgentPhase.ASKING -> clockForPhase(AgentPhase.WAITING, runStartWallMs, finishedWallMs, askExpiresWallMs, nowWallMs)
    else -> clockForPhase(phase, runStartWallMs, finishedWallMs, nextRunWallMs, nowWallMs)
}

/**
 * [T-android-user-ask-chip] The question the ongoing notification shows while
 * ASKING: of every pending ask, the one that times out first (an indefinite
 * one only when none has a deadline).
 */
internal data class AskingState(val sessionTitle: String?, val expiresAtMs: Long?)

internal fun askingStateFor(asks: Collection<com.openminis.app.agent.userask.PendingAsk>): AskingState? =
    asks.filter { it.state == com.openminis.app.agent.userask.UserAskState.PENDING }
        .minWithOrNull(compareBy(nullsLast()) { it.expiresAtMs })
        ?.let { AskingState(it.sessionTitle?.takeIf { t -> t.isNotBlank() }, it.expiresAtMs) }

/**
 * [T-android-user-ask-chip] The chip's short text while ASKING: the time left
 * as m:ss (ticked once a second like a running clock) under an hour, whole
 * minutes beyond ("2h0m", within the chip's 7 characters), or "?" for a
 * question with no deadline.
 */
internal fun askChipText(remainingMs: Long?): String = when {
    remainingMs == null -> "?"
    remainingMs >= 3_600_000L -> waitChipText(remainingMs) ?: "?"
    else -> chipTimerText(remainingMs.coerceAtLeast(0))
}

/**
 * [T-fgs-presence-no-chip] The service is kept only because the user is in a
 * chat (T166 keep-alive): nothing running, no scheduled task holding a chat,
 * no finished run to show. The ongoing notification is then a plain
 * low-priority row - not promoted to the status-bar chip, no clock, no
 * per-second refresh. Every other phase keeps the chip.
 */
internal fun isPresenceOnly(phase: AgentPhase, hasScheduledHold: Boolean): Boolean =
    phase == AgentPhase.IDLE && !hasScheduledHold

private fun clockForPhase(
    phase: AgentPhase,
    runStartWallMs: Long,
    finishedWallMs: Long,
    nextRunWallMs: Long?,
    nowWallMs: Long,
): NotificationClock = when (phase) {
    AgentPhase.WAITING ->
        if (nextRunWallMs != null && nextRunWallMs > nowWallMs) {
            NotificationClock(showWhen = true, whenMs = nextRunWallMs, usesChronometer = true, countDown = true)
        } else {
            NotificationClock(showWhen = false, whenMs = nowWallMs, usesChronometer = false, countDown = false)
        }
    AgentPhase.COMPLETED ->
        NotificationClock(showWhen = true, whenMs = finishedWallMs, usesChronometer = false, countDown = false)
    else -> NotificationClock(showWhen = true, whenMs = runStartWallMs, usesChronometer = true, countDown = false)
}

/**
 * [T-port-sched-wait-notification] The Android 16 chip's short text while
 * WAITING: whole minutes, rounded up so it never reads 0 while the run is
 * still ahead - "12m", "1h5m", "2d3h" (at most 6 chars). Minutes, not
 * seconds, so the chip needs one re-post a minute instead of one a second
 * during a wait that can last hours. null when there is nothing to count down
 * to.
 */
internal fun waitChipText(remainingMs: Long?): String? {
    if (remainingMs == null || remainingMs <= 0) return null
    val minutes = (remainingMs + 59_999) / 60_000
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h${minutes % 60}m"
        else -> "${minutes / (24 * 60)}d${(minutes % (24 * 60)) / 60}h"
    }
}

/**
 * [T-port-sched-wait-notification] How long until the WAITING notification
 * must be re-posted, or null for never (no clock, or already due):
 * - with the Android 16 chip, at the next whole-minute change of
 *   [waitChipText];
 * - otherwise once, just after the countdown ends, so the row drops its
 *   chronometer instead of counting into negative time if the run is late.
 */
internal fun waitRefreshDelayMs(remainingMs: Long?, promotedChip: Boolean): Long? {
    if (remainingMs == null || remainingMs <= 0) return null
    if (!promotedChip) return remainingMs + WAIT_REFRESH_MARGIN_MS
    val toBoundary = remainingMs % 60_000
    return (if (toBoundary == 0L) 60_000 else toBoundary) + WAIT_REFRESH_MARGIN_MS
}

private const val WAIT_REFRESH_MARGIN_MS = 250L

/**
 * [T-port-sched-running-counter] A running phase's status with the scheduled
 * fire's run counter, iOS "Working… · 2/30": "Running shell · 2/30". Plain
 * status without a counter.
 */
internal fun withRunCounter(status: String, runCounter: String?): String =
    runCounter?.let { "$status · $it" } ?: status

/**
 * [T-port-sched-overlay-waiting] The wait the floating capsule shows instead of
 * "completed" between two scheduled fires: [wait] when it belongs to the chat
 * the capsule is bound to (or the capsule is bound to none), else null.
 */
internal fun overlayWaitFor(
    wait: com.openminis.app.scheduled.ScheduledWait?,
    capsuleSessionId: String?,
): com.openminis.app.scheduled.ScheduledWait? =
    wait?.takeIf { capsuleSessionId == null || it.sessionId == capsuleSessionId }

/**
 * [T-port-sched-overlay-waiting] A wall-clock instant on the elapsedRealtime
 * clock, which is what the capsule's Chronometer counts in.
 */
internal fun wallClockToElapsedRealtime(wallMs: Long, nowWallMs: Long, nowElapsedMs: Long): Long =
    nowElapsedMs + (wallMs - nowWallMs)

/**
 * [T-port-sched-completed-copy] The COMPLETED line. After the last run of a
 * schedule (or any run whose capsule keeps the service for its completion
 * linger) no session is active, present or scheduled any more, so the count
 * is 0 and the line read "0 个会话 | 用时 0:01". With no session to count,
 * the line is the duration alone ("用时 0:01" / "Completed in 0:01").
 */
@androidx.annotation.StringRes
internal fun completedTextResFor(sessionCount: Int): Int =
    if (sessionCount > 0) R.string.bg_service_notification_text_completed
    else R.string.bg_service_notification_text_completed_no_count
