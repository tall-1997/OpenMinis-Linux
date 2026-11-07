package com.openminis.app.vscreen.tier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision table for the tier ladder order and the honest failure summary. */
class TierSelectorTest {

    private fun ok(caps: Set<VscreenCapability> = emptySet()) = ProbeResult.Available(caps)
    private fun down(reason: String = "x", at: Long = 0) = ProbeResult.Degraded(reason, at)
    private fun off(reason: String = "no su") = ProbeResult.Unavailable(reason)

    @Test
    fun `plan order keeps only available tiers in priority sequence`() {
        val sheet = mapOf(
            VscreenTier.ROOT to off(),
            VscreenTier.SHIZUKU to ok(),
            VscreenTier.NATIVE to ok(),
        )
        assertEquals(listOf(VscreenTier.SHIZUKU, VscreenTier.NATIVE), TierSelector.planOrder(sheet))
    }

    @Test
    fun `degraded tiers are skipped until their backoff reprobe`() {
        val sheet = mapOf(
            VscreenTier.ROOT to ok(),
            VscreenTier.SHIZUKU to down("host start timed out"),
            VscreenTier.NATIVE to ok(),
        )
        assertEquals(listOf(VscreenTier.ROOT, VscreenTier.NATIVE), TierSelector.planOrder(sheet))
    }

    @Test
    fun `an empty plan means nothing is attemptable`() {
        val sheet = mapOf(
            VscreenTier.ROOT to off(),
            VscreenTier.SHIZUKU to off(),
            VscreenTier.NATIVE to down(),
        )
        assertTrue(TierSelector.planOrder(sheet).isEmpty())
        assertNull(TierSelector.highestAvailable(sheet))
    }

    @Test
    fun `failure summary carries one honest line per tier`() {
        val summary = TierSelector.failureSummary(
            mapOf(
                VscreenTier.ROOT to off("no host su binary"),
                VscreenTier.SHIZUKU to down("bind failed", 5),
                VscreenTier.NATIVE to off("ROM refused"),
            ),
        )
        assertTrue(summary.contains("root: no host su binary"))
        assertTrue(summary.contains("shizuku: skipped, degraded: bind failed"))
        assertTrue(summary.contains("native: ROM refused"))
    }

    @Test
    fun `upgrade hint fires only when a live screen sits below the best tier`() {
        val sheet = mapOf(
            VscreenTier.ROOT to off(),
            VscreenTier.SHIZUKU to ok(),
            VscreenTier.NATIVE to ok(),
        )
        val hint = TierSelector.upgradeHint(setOf(VscreenTier.NATIVE), sheet)
        assertTrue(hint!!.contains("shizuku"))
        assertNull(TierSelector.upgradeHint(setOf(VscreenTier.SHIZUKU), sheet))
        assertNull(TierSelector.upgradeHint(emptySet(), sheet))
        assertNull(TierSelector.upgradeHint(setOf(VscreenTier.NATIVE), mapOf(VscreenTier.NATIVE to ok())))
    }
}
