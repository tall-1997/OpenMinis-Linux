package com.openminis.app.service

import android.content.Context

/**
 * [T-live-status-model] The only place that resolves the localized labels the
 * shared [LiveStatus] needs.
 *
 * Kept out of [LiveStatus] so the model stays pure and JVM-testable, and out of
 * `AgentForegroundService` so both status surfaces build the model the same way
 * instead of each repeating the three-way label lookup.
 */
internal fun liveStatusOf(
    context: Context,
    state: ForegroundNotificationState,
    sessionLabel: String,
): LiveStatus {
    val app = context.applicationContext
    return LiveStatus.from(
        state = state,
        appLabel = ForegroundNotificationCopy.toolLabelShort(app, null),
        toolShort = ForegroundNotificationCopy.toolLabelShort(app, state.toolName),
        sessionLabel = sessionLabel,
        subtitle = state.subtitle,
    )
}
