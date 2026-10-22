package com.openminis.app.ui.chat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-model-switch-midrun-stall] The user switched models while the agent loop
 * was mid-run.
 *
 * Extracted to its own file: ChatViewModelAgentLoopExt is at the
 * architecture-policy line ceiling.
 *
 * Symptom: mid-task the user switches from model A to B, then the session
 * appears stuck; retries keep hammering A.
 *
 * Root cause: the loop snapshots [ChatViewModel.currentProvider] into a local
 * (`var currentProvider = provider`) at start; failure retries and group
 * fallback both read that local. `selectEntry()` writes the VM field, which
 * the running loop never sees. So the old model — usually the one that was
 * failing — gets retried until the budget runs out, and the user thinks the
 * switch "didn't take".
 *
 * `ModelSwitchGate`'s epoch only guarded the fallback writeback, not the retry
 * target. Here the retry decision point abandons the turn outright: the user
 * has clearly said they no longer want the old model, so retrying it is
 * pointless. Control returns, and the next send uses the new model.
 */

/**
 * Did the user switch models during this run?
 *
 * The epoch bumps on every user-driven model selection; the loop snapshots it
 * once at start. Unequal = the user switched mid-run.
 */
internal fun ChatViewModel.shouldAbandonForModelSwitch(snapshottedEpoch: Long): Boolean =
    !ModelSwitchGate.mayWriteBack(snapshottedEpoch, snapshotModelSelectionEpoch())

/**
 * If the user switched models mid-run, tidy up and return true (the caller
 * should end the turn).
 */
internal suspend fun ChatViewModel.abandonIfModelSwitched(snapshottedEpoch: Long): Boolean {
    if (!shouldAbandonForModelSwitch(snapshottedEpoch)) return false
    abandonTurnForModelSwitch(snapshottedEpoch)
    return true
}

/**
 * End the current turn and clean up state: clear the retry countdown and
 * prompt the user to re-send.
 */
internal suspend fun ChatViewModel.abandonTurnForModelSwitch(snapshottedEpoch: Long) {
    val current = snapshotModelSelectionEpoch()
    Log.i(
        ChatViewModel.TAG,
        "[ModelSwitch] user switched model mid-run (epoch $snapshottedEpoch -> $current), " +
            "abandoning old-model retries, ending turn",
    )
    withContext(Dispatchers.Main) {
        _autoRetryAttempt.value = 0
        _autoRetryCountdown.value = 0
        setTransientInlineError("Model switched — send again to use the new model")
    }
}
