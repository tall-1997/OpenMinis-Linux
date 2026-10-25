package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable

/**
 * Composer-side header cluster: the plan/goal card, its management sheet,
 * and the live supervisor-worker phase bar. Extracted from ChatScreen
 * (T-supervisor-status-bar) so the screen file keeps shrinking; the
 * cluster renders in this exact order directly above the composer.
 *
 * [sessionId] gates the supervisor bar to the foregrounded conversation —
 * a live session never follows the user into another chat.
 */
@Composable
internal fun ChatComposerHeader(
    viewModel: ChatViewModel,
    sessionId: String,
) {
    PlanGoalCard(viewModel)
    GoalSheet(viewModel)
    // [T-supervisor-status-bar] Live supervisor-worker phase bar.
    // Render-side sessionId gating lives inside the bar; the halt button
    // routes to the runner's user-halt path.
    SupervisorStatusBar(
        sessionId = sessionId,
        onHalt = { viewModel.haltSupervisorSession() },
        onResume = { viewModel.resumeSupervisorSession() },
        onDiscard = { viewModel.discardSupervisorRecovery() },
    )
}
