package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
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
        // The user-visible half of the same mistake. UNKNOWN has to leave the id
        // out of the paid set too, because Verdict.PAID feeds withPaid and
        // filterFree drops every paid id — a model hidden without ever being
        // marked dead, which is exactly what a gate regression used to do to the
        // whole lane.
        assertTrue(
            "a generic ModelError must not drop the model out of the free lane",
            ZenFreeLaneCatalog.filterFree(
                listOf(LLMModel(id = id, displayName = id, provider = "zen")),
                ZenLearnedFree(free = setOf(id)),
            ).isNotEmpty(),
        )
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

    // ── Unmapped error types ──────────────────────────────────────────────
    //
    // `FreeUsageLimitError` arrived on a live request in 2026-10 with a
    // retry-after of 94s. Nothing in the app knew the name, so classify() let
    // it fall through to the transport map — where 401/403 means "this model
    // needs a credential". A quota refusal is about the account and expires on
    // its own; read as PAID it feeds withPaid and the model leaves the free
    // lane for good, never marked dead, so nothing ever brings it back. The
    // assertion that matters is the lane one: a verdict can look harmless and
    // still hide the model.

    @Test
    fun aQuotaRefusalIsNotACredentialVerdict() {
        val verdict = ZenFreeLaneDirectory.classify(
            id, 403, zenError("FreeUsageLimitError", "Free usage limit reached."),
        )
        assertEquals(ZenFreeLaneDirectory.Verdict.UNKNOWN, verdict)
        assertFalse("a quota is not a property of the model", ZenFreeLaneHealth.isDead(id))
        assertTrue("the model must survive its own quota", laneKeepsKnownFree(verdict).isNotEmpty())
    }

    @Test
    fun aQuotaRefusalKeepsTheModelOnAnyStatus() {
        // Upstream picks the status; we must not inherit its choice as a verdict.
        for (code in listOf(401, 403, 429, 503)) {
            val verdict = ZenFreeLaneDirectory.classify(
                id, code, zenError("FreeUsageLimitError", "Free usage limit reached."),
            )
            assertEquals("http $code", ZenFreeLaneDirectory.Verdict.UNKNOWN, verdict)
            assertTrue("http $code emptied the lane", laneKeepsKnownFree(verdict).isNotEmpty())
        }
        assertFalse(ZenFreeLaneHealth.isDead(id))
    }

    @Test
    fun aTypeInventedTomorrowIsUnknownToday() {
        val verdict = ZenFreeLaneDirectory.classify(id, 401, zenError("WhateverComesNextError"))
        assertEquals(ZenFreeLaneDirectory.Verdict.UNKNOWN, verdict)
        assertFalse(ZenFreeLaneHealth.isDead(id))
        assertTrue(laneKeepsKnownFree(verdict).isNotEmpty())
    }

    // ── 200-shaped refusals ───────────────────────────────────────────────

    @Test
    fun aRefusalInsideAnSseFrameIsNotLearnedAsFree() {
        val verdict = ZenFreeLaneDirectory.classify(
            id, 200, sse(zenError("FreeUsageLimitError", "Free usage limit reached.")),
        )
        assertEquals(
            "a refusal we failed to parse gets learned as a working free model",
            ZenFreeLaneDirectory.Verdict.UNKNOWN,
            verdict,
        )
        assertTrue(laneForUnprovenId(verdict).isEmpty())
    }

    @Test
    fun aStreamedNotSupportedIsStillDeath() {
        val verdict = ZenFreeLaneDirectory.classify(
            id, 200, sse(zenError("ModelError", "not supported")),
        )
        assertEquals(ZenFreeLaneDirectory.Verdict.DEAD, verdict)
        assertTrue(ZenFreeLaneHealth.isDead(id))
        assertTrue(laneForUnprovenId(verdict).isEmpty())
    }

    @Test
    fun aHealthyStreamIsStillFree() {
        val verdict = ZenFreeLaneDirectory.classify(
            id,
            200,
            sse("""{"choices":[{"delta":{"content":"pong"}}]}""", "[DONE]"),
        )
        assertEquals(ZenFreeLaneDirectory.Verdict.FREE, verdict)
        assertTrue(laneForUnprovenId(verdict).isNotEmpty())
    }

    @Test
    fun streamTextMentioningAnErrorIsNotAnErrorFrame() {
        // The word inside generated content must not be mistaken for a dialect.
        val verdict = ZenFreeLaneDirectory.classify(
            id,
            200,
            sse("""{"choices":[{"delta":{"content":"no error was found"}}]}""", "[DONE]"),
        )
        assertEquals(ZenFreeLaneDirectory.Verdict.FREE, verdict)
        assertTrue(laneForUnprovenId(verdict).isNotEmpty())
    }

    @Test
    fun aModelThatAnswersAgainIsRevivedByTheProbe() {
        ZenFreeLaneHealth.recordFailure(id, ZenFailureEvidence.MODEL_NOT_SUPPORTED)
        assertTrue(ZenFreeLaneHealth.isDead(id))
        val verdict = ZenFreeLaneDirectory.classify(
            id, 200, sse("""{"choices":[{"delta":{"content":"pong"}}]}""", "[DONE]"),
        )
        assertEquals(ZenFreeLaneDirectory.Verdict.FREE, verdict)
        assertFalse(
            "one-sided evidence keeps a recovered model hidden until the ttl runs out",
            ZenFreeLaneHealth.isDead(id),
        )
        assertTrue(laneForUnprovenId(verdict).isNotEmpty())
    }

    private fun sse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" }

    /**
     * The picker's view of an id already sitting in the lane, after
     * [ZenFreeLaneDirectory.launchProbe] persists this verdict. UNKNOWN writes
     * nothing, so the id stays; PAID is the one verdict that removes a model
     * without ever marking it dead.
     */
    private fun laneKeepsKnownFree(verdict: ZenFreeLaneDirectory.Verdict): List<LLMModel> {
        val learned = ZenLearnedFree().withFree(id)
        return show(
            when (verdict) {
                ZenFreeLaneDirectory.Verdict.PAID -> learned.withPaid(id)
                else -> learned
            },
        )
    }

    /**
     * The picker's view of an id nobody has proven yet. Only FREE puts it in
     * front of the user, which is why a refusal misread as a healthy stream is
     * worse than one misread as anything else.
     */
    private fun laneForUnprovenId(verdict: ZenFreeLaneDirectory.Verdict): List<LLMModel> =
        show(
            when (verdict) {
                ZenFreeLaneDirectory.Verdict.FREE -> ZenLearnedFree().withFree(id)
                else -> ZenLearnedFree()
            },
        )

    private fun show(learned: ZenLearnedFree) = ZenFreeLaneCatalog.filterFree(
        listOf(LLMModel(id = id, displayName = id, provider = "zen")),
        learned,
    )
}
