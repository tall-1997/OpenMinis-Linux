package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-switch-midrun-adopt] A model switch made while a run is
 * in flight takes effect on the NEXT request of that same run — the current
 * stream finishes, nothing is abandoned, and the user does not re-send.
 *
 * The loop used to snapshot its provider once at entry. On the healthy path
 * the user's selection was invisible until the whole run ended; on the
 * failure path the run was abandoned outright with "send again". The
 * decision below is what the per-request boundary consults; the caller
 * applies the VM's current provider and rebuilds the fallback chain for it.
 *
 * Mirrored by /var/minis/workspace/sim_model_switch.py.
 */
class ModelSwitchAdoptionTest {

    @Test
    fun `an unchanged epoch keeps the in-flight provider`() {
        // No selection happened since this request started streaming: the
        // retry / fallback machinery must keep operating on exactly the
        // provider it already has, fallback chain included.
        assertFalse(
            ModelSwitchAdoption.shouldAdopt(
                requestEpoch = 4, currentEpoch = 4, providerResolved = true,
            ),
        )
    }

    @Test
    fun `a bumped epoch adopts the selection for the next request`() {
        // The user picked a model between requests. The next request runs on
        // it, and the caller rebuilds the fallback chain for THAT model —
        // the chain resolved for the launch model would walk straight back
        // to it.
        assertTrue(
            ModelSwitchAdoption.shouldAdopt(
                requestEpoch = 4, currentEpoch = 5, providerResolved = true,
            ),
        )
    }

    @Test
    fun `a switch whose provider failed to resolve keeps the current one`() {
        // selectEntry() bumps the epoch BEFORE it resolves, and bails out
        // when the entry has no credential — leaving the VM provider unset.
        // Adopting that would strand the run with no model.
        assertFalse(
            ModelSwitchAdoption.shouldAdopt(
                requestEpoch = 4, currentEpoch = 5, providerResolved = false,
            ),
        )
    }

    @Test
    fun `several switches between requests collapse to the latest selection`() {
        // Two picker taps before the next request move the epoch twice. The
        // boundary reads the VM once per request, so it adopts whatever is
        // current — the latest selection — rather than each tap in turn.
        assertTrue(
            ModelSwitchAdoption.shouldAdopt(
                requestEpoch = 4, currentEpoch = 6, providerResolved = true,
            ),
        )
    }
}
