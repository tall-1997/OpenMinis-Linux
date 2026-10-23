package com.openminis.app.ui.chat

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.goal.GoalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI-facing Goal reads and GoalSheet actions. The [PlanGoalCard] header row
 * and the [GoalSheet] reload on [GoalManager.version] bumps and call
 * [currentGoal] for the fresh row. Sheet actions mirror
 * [executeGoalCommand] semantics one-for-one — create/resume/budget-unblock
 * kick a goal run, pause and clear do not — but report failures to the
 * sheet as [Result] instead of appending system messages: the sheet renders
 * errors inline, and success is already visible through the version bump.
 * The run trigger itself fires on the main dispatcher, matching
 * [executeGoalCommand].
 */
internal suspend fun ChatViewModel.currentGoal(): SessionGoalEntity? =
    withContext(Dispatchers.IO) {
        runCatching { GoalManager(chatRepository).get(activeSessionId) }.getOrNull()
    }

internal suspend fun ChatViewModel.createGoalFromSheet(
    objective: String,
    tokenBudget: Long?,
): Result<Unit> = runGoalAction {
    val sid = ensureSession()
    GoalManager(chatRepository).createByUser(sid, objective.trim(), tokenBudget)
    true
}

internal suspend fun ChatViewModel.setGoalPausedFromSheet(paused: Boolean): Result<Unit> =
    runGoalAction {
        val sid = ensureSession()
        val updated = GoalManager(chatRepository).setPausedByUser(sid, paused)
        // Resuming an ACTIVE goal is the one pause-family transition that
        // should put the agent back to work.
        !paused && updated?.status == GoalStatus.ACTIVE
    }

internal suspend fun ChatViewModel.updateGoalBudgetFromSheet(tokenBudget: Long?): Result<Unit> =
    runGoalAction {
        val sid = ensureSession()
        val manager = GoalManager(chatRepository)
        val before = manager.get(sid)
        val updated = manager.updateBudgetByUser(sid, tokenBudget)
        before?.status == GoalStatus.BUDGET_LIMITED && updated.status == GoalStatus.ACTIVE
    }

internal suspend fun ChatViewModel.clearGoalFromSheet(): Result<Unit> = runGoalAction {
    GoalManager(chatRepository).clearByUser(ensureSession())
    false
}

/**
 * Runs a sheet action on IO; the block reports whether the agent loop
 * should be kicked afterwards. Failures surface to the sheet as [Result];
 * the run trigger fires on Main, exactly like [executeGoalCommand].
 */
private suspend fun ChatViewModel.runGoalAction(
    block: suspend () -> Boolean,
): Result<Unit> {
    val outcome = withContext(Dispatchers.IO) { runCatching { block() } }
    if (outcome.getOrDefault(false)) {
        withContext(Dispatchers.Main.immediate) { startGoalRun() }
    }
    return outcome.map { }
}

/**
 * Kicks the agent loop for an ACTIVE goal — the same trigger
 * [executeGoalCommand] uses. No-op while a turn is already streaming; the
 * goal mount injects context on the next turn either way.
 */
internal fun ChatViewModel.startGoalRun() {
    if (!_isStreaming.value) {
        sendMessage("", skipContextCheck = true, internalGoalRun = true)
    }
}
