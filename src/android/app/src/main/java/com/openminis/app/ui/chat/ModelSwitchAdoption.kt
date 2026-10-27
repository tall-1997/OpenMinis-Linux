package com.openminis.app.ui.chat

/**
 * [T-android-model-switch-midrun-adopt] Whether the next request of a running
 * agent loop should move onto a model the user picked while it was running.
 *
 * `runAgentLoop` used to copy its provider into a local at entry and read
 * only that local. A mid-run selection wrote the VM field, which the loop
 * never re-read: on the healthy path the new model waited until the whole
 * run ended, and on the failure path the run was abandoned with a prompt to
 * re-send. The per-request boundary now asks [shouldAdopt] instead, and
 * applies the VM's current provider itself when the answer is yes.
 *
 * Adoption requires BOTH: the user bumped the selection epoch since this
 * request started, and the selection resolved to a provider.
 * [ChatViewModel.selectEntry] bumps the epoch before it resolves and returns
 * early when the entry has no credential, leaving the VM provider null —
 * adopting that would strand the run with no model, so [providerResolved]
 * gates it out.
 *
 * The caller rebuilds the fallback chain for the adopted model. Reusing the
 * chain resolved for the launch model would walk group fallback straight
 * back onto the model the user just left.
 *
 * What this replaces: the retry decision point used to detect the bumped
 * epoch, abandon the turn outright, and show "Model switched — send again to
 * use the new model" (the old ChatViewModelModelSwitchExt). That threw the
 * run away. The defect was the snapshot, so the fix is to re-read the VM's
 * provider here, on the request boundary, and keep the run.
 *
 * [ModelSwitchGate] still guards the fallback writeback, a separate race: a
 * run's tail persisting its fallback model must not clobber a model the user
 * picked while that tail was in flight.
 *
 * Mirrored by /var/minis/workspace/sim_model_switch.py — the device sandbox
 * does not run the Gradle suite, so the decision is verified there. Keep the
 * two copies of [shouldAdopt] in lockstep.
 */
internal object ModelSwitchAdoption {

    fun shouldAdopt(requestEpoch: Long, currentEpoch: Long, providerResolved: Boolean): Boolean =
        currentEpoch != requestEpoch && providerResolved
}
