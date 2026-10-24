package com.openminis.app.provider

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The health record only ever hides a model when the upstream said the MODEL is
 * the problem. Six of the nine evidence kinds are about us, our network, or our
 * rate budget — hiding a model for those makes a working free lane look empty,
 * which is exactly the failure this restoration exists to undo.
 *
 * REGION in particular used to be death evidence in the old build. The lane is
 * reachable from a locked region through the user's own proxy, so a region
 * refusal says nothing about whether the model exists.
 */
class FreeLaneHealthTest {

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        FreeLaneHealth.resetForTest()
        FreeLaneHealth.nowProvider = { now }
    }

    @After
    fun tearDown() {
        FreeLaneHealth.nowProvider = { System.currentTimeMillis() }
        FreeLaneHealth.resetForTest()
    }

    @Test
    fun regionEvidenceNeverMarksAModelDead() {
        FreeLaneHealth.recordFailure("beam-free", FreeLaneEvidence.REGION)
        assertFalse(FreeLaneHealth.isDead("beam-free"))
    }

    @Test
    fun freeTierEvidenceNeverMarksAModelDead() {
        // A FreeTierError means our request shape was refused — our gate bug,
        // never a property of the model.
        FreeLaneHealth.recordFailure("beam-free", FreeLaneEvidence.FREE_TIER)
        assertFalse(FreeLaneHealth.isDead("beam-free"))
    }

    @Test
    fun protocolUnsupportedEvidenceNeverMarksAModelDead() {
        FreeLaneHealth.recordFailure("beam-free", FreeLaneEvidence.PROTOCOL_UNSUPPORTED)
        assertFalse(FreeLaneHealth.isDead("beam-free"))
    }

    @Test
    fun rateLimitedAndServerEvidenceNeverMarkAModelDead() {
        FreeLaneHealth.recordFailure("a-free", FreeLaneEvidence.RATE_LIMITED)
        FreeLaneHealth.recordFailure("b-free", FreeLaneEvidence.SERVER)
        assertFalse(FreeLaneHealth.isDead("a-free"))
        assertFalse(FreeLaneHealth.isDead("b-free"))
    }

    @Test
    fun networkEvidenceNeverMarksAModelDead() {
        FreeLaneHealth.recordFailure("a-free", FreeLaneEvidence.NETWORK)
        assertFalse(FreeLaneHealth.isDead("a-free"))
    }

    @Test
    fun goneEvidenceMarksDead() {
        FreeLaneHealth.recordFailure("retired-free", FreeLaneEvidence.GONE)
        assertTrue(FreeLaneHealth.isDead("retired-free"))
    }

    @Test
    fun modelUnavailableEvidenceMarksDead() {
        FreeLaneHealth.recordFailure("x-free", FreeLaneEvidence.MODEL_UNAVAILABLE)
        assertTrue(FreeLaneHealth.isDead("x-free"))
    }

    @Test
    fun modelNotSupportedEvidenceMarksDead() {
        FreeLaneHealth.recordFailure("y-free", FreeLaneEvidence.MODEL_NOT_SUPPORTED)
        assertTrue(FreeLaneHealth.isDead("y-free"))
    }

    @Test
    fun deadEvidenceSelfHealsAfterTheTtl() {
        FreeLaneHealth.recordFailure("retired-free", FreeLaneEvidence.GONE)
        assertTrue(FreeLaneHealth.isDead("retired-free"))

        now += FreeLaneHealth.DEAD_TTL_MS - 1
        assertTrue("must still be dead just before the ttl", FreeLaneHealth.isDead("retired-free"))

        now += 2
        assertFalse("must self-heal after the ttl", FreeLaneHealth.isDead("retired-free"))
    }

    @Test
    fun aSuccessClearsPriorDeathEvidence() {
        FreeLaneHealth.recordFailure("flaky-free", FreeLaneEvidence.MODEL_UNAVAILABLE)
        assertTrue(FreeLaneHealth.isDead("flaky-free"))
        FreeLaneHealth.recordSuccess("flaky-free")
        assertFalse(FreeLaneHealth.isDead("flaky-free"))
    }

    @Test
    fun deathIsPerIdAndDoesNotLeakAcrossTheCatalogue() {
        FreeLaneHealth.recordFailure("one-free", FreeLaneEvidence.GONE)
        assertTrue(FreeLaneHealth.isDead("one-free"))
        assertFalse(FreeLaneHealth.isDead("two-free"))
        assertEquals(setOf("one-free"), FreeLaneHealth.deadIdsForTest())
    }
}
