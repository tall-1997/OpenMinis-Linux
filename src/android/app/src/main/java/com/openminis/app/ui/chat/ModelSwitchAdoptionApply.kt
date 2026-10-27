package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.provider.LLMProvider

/**
 * [T-android-model-switch-midrun-adopt] The request-boundary half of mid-run
 * model switching, extracted out of ChatViewModelAgentLoopExt so that file
 * stays under its architecture-policy line ceiling.
 *
 * [runAgentLoop] calls [resolveModelSwitch] at the top of every request,
 * before a single byte of it is built. A model the user picked since the
 * previous request started is returned HERE so the caller can move its local
 * provider onto it: the stream that was already running finishes untouched,
 * and this next request goes out on the new model. The fallback chain is
 * rebuilt for the adopted model — the chain resolved for the launch model
 * walks group fallback straight back onto the model the user just left.
 *
 * [ModelSwitchAdoption.shouldAdopt] is the decision; this resolves what to
 * adopt. They are split so the decision stays a pure function the unit test
 * and the python mirror can both assert, while resolution touches VM state.
 */
internal class AdoptedModelSwitch(
    val provider: LLMProvider,
    val fallbacks: List<ChatViewModel.FallbackCandidate>,
    val epoch: Long,
)

/**
 * @param requestEpoch the selection epoch the previous request ran under.
 * @param inFlight the provider that request used.
 * @return the model to move onto, or null when nothing changed. Null also
 * covers a bumped epoch whose provider failed to resolve — selectEntry
 * returns early on a credential-less entry and leaves the VM provider unset,
 * and adopting that would strand the run with no model.
 */
internal fun ChatViewModel.resolveModelSwitch(
    requestEpoch: Long,
    inFlight: LLMProvider,
): AdoptedModelSwitch? {
    val picked = currentProvider
    if (!ModelSwitchAdoption.shouldAdopt(
            requestEpoch = requestEpoch,
            currentEpoch = snapshotModelSelectionEpoch(),
            providerResolved = picked != null,
        ) || picked == null || picked.model.id == inFlight.model.id
    ) {
        return null
    }
    Log.i(
        ChatViewModel.TAG,
        "[ModelSwitch] adopting ${picked.model.displayName} for the next request " +
            "(was ${inFlight.model.displayName})",
    )
    return AdoptedModelSwitch(
        provider = picked,
        fallbacks = buildFallbackProviders(picked),
        epoch = snapshotModelSelectionEpoch(),
    )
}
