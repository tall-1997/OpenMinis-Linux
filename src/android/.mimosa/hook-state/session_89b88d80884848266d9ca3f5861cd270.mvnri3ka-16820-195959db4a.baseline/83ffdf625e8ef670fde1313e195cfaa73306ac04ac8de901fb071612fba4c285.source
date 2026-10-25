package com.openminis.app.evolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeliefMaintenanceTest {

    @Test
    fun draftEstablishedCore() {
        val t0 = 1_000_000L
        assertEquals(
            BeliefMaintenance.DRAFT,
            BeliefMaintenance.nextTier(1, t0, BeliefMaintenance.DRAFT, t0, false),
        )
        assertEquals(
            BeliefMaintenance.ESTABLISHED,
            BeliefMaintenance.nextTier(2, t0, BeliefMaintenance.DRAFT, t0, false),
        )
        assertEquals(
            BeliefMaintenance.CORE,
            BeliefMaintenance.nextTier(5, t0, BeliefMaintenance.ESTABLISHED, t0, false),
        )
        assertEquals(
            BeliefMaintenance.CORE,
            BeliefMaintenance.nextTier(2, t0, BeliefMaintenance.ESTABLISHED, t0, true),
        )
    }

    @Test
    fun staleThenDecayButCoreHolds() {
        val t0 = 1_000_000L
        assertEquals(
            BeliefMaintenance.STALE,
            BeliefMaintenance.nextTier(
                hitCount = 3,
                lastHitAt = t0,
                current = BeliefMaintenance.ESTABLISHED,
                now = t0 + BeliefMaintenance.STALE_MS,
                acceptedCore = false,
            ),
        )
        assertEquals(
            BeliefMaintenance.DECAYED,
            BeliefMaintenance.nextTier(
                hitCount = 3,
                lastHitAt = t0,
                current = BeliefMaintenance.STALE,
                now = t0 + BeliefMaintenance.DECAY_MS,
                acceptedCore = false,
            ),
        )
        assertEquals(
            BeliefMaintenance.CORE,
            BeliefMaintenance.nextTier(
                hitCount = 3,
                lastHitAt = t0,
                current = BeliefMaintenance.CORE,
                now = t0 + BeliefMaintenance.DECAY_MS,
                acceptedCore = true,
            ),
        )
    }

    @Test
    fun mergeNearFingerprints() {
        assertTrue(
            BeliefMaintenance.shouldMerge(
                "Prefer bash over zsh for scripts",
                "Prefer bash not zsh in scripts",
            ),
        )
        assertFalse(
            BeliefMaintenance.shouldMerge(
                "Prefer bash",
                "Always confirm calendar deletes",
            ),
        )
    }

    @Test
    fun contradictsApprovedRule() {
        assertTrue(
            BeliefMaintenance.contradicts(
                "Prefer bash over zsh",
                "不对，不要再用 bash over zsh",
            ),
        )
        assertFalse(
            BeliefMaintenance.contradicts(
                "Prefer bash over zsh",
                "继续调研下一步",
            ),
        )
    }
}
