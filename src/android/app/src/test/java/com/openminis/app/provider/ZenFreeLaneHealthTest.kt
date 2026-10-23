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
class ZenFreeLaneHealthTest {

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        ZenFreeLaneHealth.resetForTest()
        ZenFreeLaneHealth.nowProvider = { now }
    }

    @After
    fun tearDown() {
        ZenFreeLaneHealth.nowProvider = { System.currentTimeMillis() }
        ZenFreeLaneHealth.resetForTest()
    }

    @Test
    fun regionEvidenceNeverMarksAModelDead() {
        ZenFreeLaneHealth.recordFailure("beam-free", ZenFailureEvidence.REGION)
        assertFalse(ZenFreeLaneHealth.isDead("beam-free"))
    }

    @Test
    fun freeTierEvidenceNeverMarksAModelDead() {
        // A FreeTierError means our request shape was refused — our gate bug,
        // never a property of the model.
        ZenFreeLaneHealth.recordFailure("beam-free", ZenFailureEvidence.FREE_TIER)
        assertFalse(ZenFreeLaneHealth.isDead("beam-free"))
    }

    @Test
    fun protocolUnsupportedEvidenceNeverMarksAModelDead() {
        ZenFreeLaneHealth.recordFailure("beam-free", ZenFailureEvidence.PROTOCOL_UNSUPPORTED)
        assertFalse(ZenFreeLaneHealth.isDead("beam-free"))
    }

    @Test
    fun rateLimitedAndServerEvidenceNeverMarkAModelDead() {
        ZenFreeLaneHealth.recordFailure("a-free", ZenFailureEvidence.RATE_LIMITED)
        ZenFreeLaneHealth.recordFailure("b-free", ZenFailureEvidence.SERVER)
        assertFalse(ZenFreeLaneHealth.isDead("a-free"))
        assertFalse(ZenFreeLaneHealth.isDead("b-free"))
    }

    @Test
    fun networkEvidenceNeverMarksAModelDead() {
        ZenFreeLaneHealth.recordFailure("a-free", ZenFailureEvidence.NETWORK)
        assertFalse(ZenFreeLaneHealth.isDead("a-free"))
    }

    @Test
    fun goneEvidenceMarksDead() {
        ZenFreeLaneHealth.recordFailure("retired-free", ZenFailureEvidence.GONE)
        assertTrue(ZenFreeLaneHealth.isDead("retired-free"))
    }

    @Test
    fun modelUnavailableEvidenceMarksDead() {
        ZenFreeLaneHealth.recordFailure("x-free", ZenFailureEvidence.MODEL_UNAVAILABLE)
        assertTrue(ZenFreeLaneHealth.isDead("x-free"))
    }

    @Test
    fun modelNotSupportedEvidenceMarksDead() {
        ZenFreeLaneHealth.recordFailure("y-free", ZenFailureEvidence.MODEL_NOT_SUPPORTED)
        assertTrue(ZenFreeLaneHealth.isDead("y-free"))
    }

    @Test
    fun deadEvidenceSelfHealsAfterTheTtl() {
        ZenFreeLaneHealth.recordFailure("retired-free", ZenFailureEvidence.GONE)
        assertTrue(ZenFreeLaneHealth.isDead("retired-free"))

        now += ZenFreeLaneHealth.DEAD_TTL_MS - 1
        assertTrue("must still be dead just before the ttl", ZenFreeLaneHealth.isDead("retired-free"))

        now += 2
        assertFalse("must self-heal after the ttl", ZenFreeLaneHealth.isDead("retired-free"))
    }

    @Test
    fun aSuccessClearsPriorDeathEvidence() {
        ZenFreeLaneHealth.recordFailure("flaky-free", ZenFailureEvidence.MODEL_UNAVAILABLE)
        assertTrue(ZenFreeLaneHealth.isDead("flaky-free"))
        ZenFreeLaneHealth.recordSuccess("flaky-free")
        assertFalse(ZenFreeLaneHealth.isDead("flaky-free"))
    }

    @Test
    fun deathIsPerIdAndDoesNotLeakAcrossTheCatalogue() {
        ZenFreeLaneHealth.recordFailure("one-free", ZenFailureEvidence.GONE)
        assertTrue(ZenFreeLaneHealth.isDead("one-free"))
        assertFalse(ZenFreeLaneHealth.isDead("two-free"))
        assertEquals(setOf("one-free"), ZenFreeLaneHealth.deadIdsForTest())
    }
}
