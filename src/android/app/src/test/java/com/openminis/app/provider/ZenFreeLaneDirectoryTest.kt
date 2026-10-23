package com.openminis.app.provider

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The probe verdict is where the 2026-10-17 retirement went wrong, so it gets
 * its own contract.
 *
 * The retired build read `403 FreeTierError` as "this model needs a credential"
 * and hid the lane. But the 2026-10-23 re-measurement showed FreeTierError is
 * what the endpoint returns when the request BODY misses the gate — it is the
 * signature of our own regression, not a property of the model. Same for
 * RegionError: the lane is reachable from a locked region through the user's
 * proxy, so a region refusal must never delete a model that works elsewhere.
 *
 * Both therefore have to come back UNKNOWN (write nothing, retry next refresh)
 * while genuine model-level refusals come back DEAD.
 */
class ZenFreeLaneDirectoryTest {

    private val id = "probe-target"

    @Before
    fun setUp() = ZenFreeLaneHealth.resetForTest()

    @After
    fun tearDown() = ZenFreeLaneHealth.resetForTest()

    private fun zenError(type: String, message: String = "") =
        """{"type":"error","error":{"type":"$type","message":"$message"}}"""

    @Test
    fun aTwoHundredMeansTheLaneReallyAnswered() {
        assertEquals(
            ZenFreeLaneDirectory.Verdict.FREE,
            ZenFreeLaneDirectory.classify(id, 200, "data: {\"choices\":[]}\n\n"),
        )
        assertFalse(ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun freeTierErrorIsOurBugNotTheModelsProperty() {
        assertEquals(
            ZenFreeLaneDirectory.Verdict.UNKNOWN,
            ZenFreeLaneDirectory.classify(id, 403, zenError("FreeTierError")),
        )
        assertFalse("a gate regression must not delete the model", ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun regionErrorNeverRemovesTheModel() {
        assertEquals(
            ZenFreeLaneDirectory.Verdict.UNKNOWN,
            ZenFreeLaneDirectory.classify(id, 403, zenError("RegionError", "not available in your region")),
        )
        assertFalse("locked-region users still get to see the model", ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun modelErrorNotSupportedIsRealDeath() {
        assertEquals(
            ZenFreeLaneDirectory.Verdict.DEAD,
            ZenFreeLaneDirectory.classify(id, 401, zenError("ModelError", "not supported")),
        )
        assertTrue(ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun aGenericModelErrorIsNotDeathEvidence() {
        assertEquals(
            ZenFreeLaneDirectory.Verdict.UNKNOWN,
            ZenFreeLaneDirectory.classify(id, 401, zenError("ModelError", "something transient")),
        )
        assertFalse(ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun goneIsDeath() {
        assertEquals(ZenFreeLaneDirectory.Verdict.DEAD, ZenFreeLaneDirectory.classify(id, 410, ""))
        assertTrue(ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun modelUnavailableIsDeath() {
        assertEquals(
            ZenFreeLaneDirectory.Verdict.DEAD,
            ZenFreeLaneDirectory.classify(id, 400, "{\"error\":\"Model is unavailable.\"}"),
        )
        assertTrue(ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun aBare401MeansCredentialsRequired() {
        assertEquals(
            ZenFreeLaneDirectory.Verdict.PAID,
            ZenFreeLaneDirectory.classify(id, 401, "{\"error\":\"invalid api key\"}"),
        )
        assertFalse("paid is a catalogue decision, not a death", ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun rateLimitAndServerErrorsWriteNothing() {
        assertEquals(ZenFreeLaneDirectory.Verdict.UNKNOWN, ZenFreeLaneDirectory.classify(id, 429, ""))
        assertEquals(ZenFreeLaneDirectory.Verdict.UNKNOWN, ZenFreeLaneDirectory.classify(id, 503, ""))
        assertFalse(ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun unparseableBodiesDoNotThrow() {
        assertEquals(
            ZenFreeLaneDirectory.Verdict.UNKNOWN,
            ZenFreeLaneDirectory.classify(id, 502, "<html>Bad Gateway</html>"),
        )
        assertEquals(
            ZenFreeLaneDirectory.Verdict.UNKNOWN,
            ZenFreeLaneDirectory.classify(id, 418, ""),
        )
    }

    @Test
    fun onlyThePlaceholderCredentialCountsAsTheKeylessLane() {
        assertTrue(ZenFreeLaneDirectory.isKeylessLane("public"))
        assertFalse(ZenFreeLaneDirectory.isKeylessLane("sk-real-key"))
        assertFalse(ZenFreeLaneDirectory.isKeylessLane(""))
        assertFalse(ZenFreeLaneDirectory.isKeylessLane(null))
    }

    @Test
    fun cacheKeyIsStableAcrossTrailingSlashSpellings() {
        assertEquals(
            ZenFreeLaneDirectory.cacheKey("https://opencode.ai/zen/v1"),
            ZenFreeLaneDirectory.cacheKey("https://opencode.ai/zen/v1/"),
        )
    }
}
