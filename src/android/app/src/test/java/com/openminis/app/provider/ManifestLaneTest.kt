package com.openminis.app.provider

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The manifest lane is the generic OpenAI-compatible behaviour behind
 * `openaiCompat` manifest entries. Its contract mirrors [KiloFreeLane]
 * where a listing declares membership — a probe verifies liveness, it does
 * not decide free-ness — and the tests below pin the three things a lane
 * driven by remote data must never get wrong: host recognition (credential
 * does not participate), credential convention on probes, and the
 * error-dialect map staying no looser than the compiled lanes.
 */
class ManifestLaneTest {

    private fun entry(
        id: String = "demo-free",
        instanceId: String = "builtin-demo-free",
        baseURL: String = "https://demo.example.com/v1",
        convention: CredentialConvention = CredentialConvention.ACCOUNT_TOKEN,
        credentialValue: String = "",
    ) = ManifestLaneEntry(
        id = id,
        behavior = "openaiCompat",
        instanceId = instanceId,
        label = "Demo (Free)",
        baseURL = baseURL,
        credentialConvention = convention,
        credentialValue = credentialValue,
        offlineSeedIds = listOf("demo-free-model"),
    )

    private val lane = ManifestLane(entry())

    @Before
    fun setUp() {
        FreeLaneHealth.resetForTest()
    }

    @After
    fun tearDown() {
        FreeLaneHealth.resetForTest()
    }

    // ---- host recognition: the credential must not participate ----

    @Test
    fun ownsSameHostRegardlessOfKey() {
        assertTrue(lane.owns("https://demo.example.com/v1", null))
        assertTrue(lane.owns("https://demo.example.com/v1", "user-account-key"))
        assertTrue(lane.owns("https://demo.example.com", "anything"))
    }

    @Test
    fun ownsDefaultsToTheEntryBaseURL() {
        assertTrue(lane.owns(null, null))
        assertTrue(lane.owns("", "some-key"))
    }

    @Test
    fun ownsRejectsAnotherHostAndAPathOnlyURL() {
        assertFalse(lane.owns("https://other.example.com/v1", null))
        // "/v1" is a path, not a host; parsing it yields no host to compare.
        assertFalse(lane.owns("/v1", null))
    }

    // ---- probes honour the credential convention ----

    @Test
    fun fixedConventionProbesWithTheDeclaredCredential() {
        val fixed = ManifestLane(
            entry(convention = CredentialConvention.FIXED, credentialValue = "public"),
        )
        assertEquals(mapOf("Authorization" to "Bearer public"), fixed.probeHeaders())
    }

    @Test
    fun noneAndAccountTokenProbeWithoutCredentials() {
        assertEquals(emptyMap<String, String>(), lane.probeHeaders())
        val none = ManifestLane(entry(convention = CredentialConvention.NONE))
        assertEquals(emptyMap<String, String>(), none.probeHeaders())
    }

    // ---- the listing is the authority ----

    @Test
    fun declaredFreeIsExactlyTheServerVerdict() {
        assertTrue(lane.declaredFree("x", true))
        assertFalse(lane.declaredFree("x", false))
        assertFalse(lane.declaredFree("x", null))
        assertFalse(lane.declaredFree("x-free-suffix", null)) // suffix does not matter here
    }

    @Test
    fun neverDiscoversUndeclaredIds() {
        assertFalse(lane.discoversUndeclaredIds)
    }

    @Test
    fun offlineSeedIdsComeFromTheEntry() {
        assertEquals(listOf("demo-free-model"), lane.offlineSeedIds)
    }

    // ---- error dialect: structured words ----

    @Test
    fun paidModelWordMarksTheModelPaid() {
        val verdict = lane.classify("a-model", 403, """{"error": {"code": "PAID_MODEL_AUTH_REQUIRED"}}""")
        assertEquals(FreeLaneVerdict.PAID, verdict)
        // PAID hides from the free set via FreeLaneLearned.withPaid; the model
        // still exists upstream, so it is not death evidence.
        assertFalse(FreeLaneHealth.isDead("demo-free", "a-model"))
    }

    @Test
    fun notFoundWordMarksTheModelGone() {
        val verdict = lane.classify("b-model", 404, """{"error": {"code": "Not Found"}}""")
        assertEquals(FreeLaneVerdict.DEAD, verdict)
        assertTrue(FreeLaneHealth.isDead("demo-free", "b-model"))
    }

    @Test
    fun notFoundDetailMarksTheModelGoneEvenWithOtherCodes() {
        val verdict = lane.classify(
            "c-model", 500,
            """{"error": {"code": "SOME_OTHER", "message": "model not found for account"}}""",
        )
        assertEquals(FreeLaneVerdict.DEAD, verdict)
    }

    @Test
    fun invalidTokenWordStaysUnknownAndRecordsCredentialRejected() {
        val verdict = lane.classify("d-model", 401, """{"error": {"code": "INVALID_TOKEN"}}""")
        assertEquals(FreeLaneVerdict.UNKNOWN, verdict)
        // Evidence recorded, but CREDENTIAL_REJECTED is not model death.
        assertFalse(FreeLaneHealth.isDead("demo-free", "d-model"))
    }

    @Test
    fun unmappedWordStaysUnknown() {
        val verdict = lane.classify("e-model", 400, """{"error": {"code": "SOME_FUTURE_WORD"}}""")
        assertEquals(FreeLaneVerdict.UNKNOWN, verdict)
        assertFalse(FreeLaneHealth.isDead("demo-free", "e-model"))
    }

    // ---- transport fallback: no structured word ----

    @Test
    fun http200IsFreeAndAnswersTheModel() {
        assertEquals(FreeLaneVerdict.FREE, lane.classify("f-model", 200, "{}"))
        assertFalse(FreeLaneHealth.isDead("demo-free", "f-model"))
    }

    @Test
    fun http404WithoutStructureMarksGone() {
        assertEquals(FreeLaneVerdict.DEAD, lane.classify("g-model", 404, "no body"))
        assertTrue(FreeLaneHealth.isDead("demo-free", "g-model"))
    }

    @Test
    fun unavailableMessageOn400MarksGone() {
        assertEquals(FreeLaneVerdict.DEAD, lane.classify("h-model", 400, "model unavailable here"))
        assertTrue(FreeLaneHealth.isDead("demo-free", "h-model"))
    }

    @Test
    fun rateLimitAndServerErrorsStayUnknown() {
        assertEquals(FreeLaneVerdict.UNKNOWN, lane.classify("i-model", 429, ""))
        assertEquals(FreeLaneVerdict.UNKNOWN, lane.classify("j-model", 503, ""))
        assertFalse(FreeLaneHealth.isDead("demo-free", "i-model"))
        assertFalse(FreeLaneHealth.isDead("demo-free", "j-model"))
    }

    @Test
    fun bare401And403MeanNothingWithoutAStructuredWord() {
        assertEquals(FreeLaneVerdict.UNKNOWN, lane.classify("k-model", 401, "unauthorized"))
        assertEquals(FreeLaneVerdict.UNKNOWN, lane.classify("l-model", 403, "forbidden"))
        assertFalse(FreeLaneHealth.isDead("demo-free", "k-model"))
        assertFalse(FreeLaneHealth.isDead("demo-free", "l-model"))
    }

    // ---- health isolation between lanes ----

    @Test
    fun evidenceIsScopedToThisLaneOnly() {
        val other = ManifestLane(entry(id = "other-free", instanceId = "builtin-other-free"))
        lane.classify("m-model", 404, "")
        // Same model id on the other lane is a different fact.
        assertFalse(FreeLaneHealth.isDead(other.id, "m-model"))
        assertTrue(FreeLaneHealth.isDead(lane.id, "m-model"))
    }
}
