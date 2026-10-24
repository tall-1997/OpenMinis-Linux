package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The free-lane catalogue is a RULE plus learned evidence, never a list.
 *
 * The old build shipped eight hardcoded ids and five of them are dead now, so
 * the last test here walks the production sources and fails if any `-free` id
 * is hardcoded anywhere except the offline first-install seed. That guard is
 * the point of this file: without it the list creeps back in.
 */
class FreeLaneMembershipTest {

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "zen")

    private val learnedNothing = FreeLaneLearned()

    @Test
    fun anyFreeSuffixCountsRegardlessOfCase() {
        assertTrue(ZenFreeLaneRule.isFreeLaneModelId("deepseek-v4.1-flash-free"))
        assertTrue(ZenFreeLaneRule.isFreeLaneModelId("Some-New-Model-FREE"))
        assertTrue(ZenFreeLaneRule.isFreeLaneModelId("a-Free"))
    }

    @Test
    fun bigPickleIsTheOnlyKnownSuffixFreeException() {
        assertTrue(ZenFreeLaneRule.isFreeLaneModelId("big-pickle"))
        assertTrue(ZenFreeLaneRule.isFreeLaneModelId("Big-Pickle"))
        assertFalse(ZenFreeLaneRule.isFreeLaneModelId("gpt-5.5"))
        assertFalse(ZenFreeLaneRule.isFreeLaneModelId("free"))
        assertFalse(ZenFreeLaneRule.isFreeLaneModelId("freebird"))
        assertFalse(ZenFreeLaneRule.isFreeLaneModelId(""))
    }

    @Test
    fun aNewlyLearnedFreeIdSurvivesTheFilter() {
        val learned = FreeLaneLearned(free = setOf("mystery-model"))
        val kept = FreeLaneDirectory.visible(
            ZenFreeLane,
            listOf(model("mystery-model"), model("gpt-5.5")),
            emptyMap(),
            learned,
        )
        assertEquals(listOf("mystery-model"), kept.map { it.id })
    }

    @Test
    fun paidIdsNeverAppearEvenWhenTheCatalogueListsThem() {
        val learned = FreeLaneLearned(
            free = setOf("mystery-model"),
            paid = setOf("mystery-model", "another-one"),
        )
        val kept = FreeLaneDirectory.visible(
            ZenFreeLane,
            listOf(model("mystery-model"), model("another-one"), model("x-free")),
            emptyMap(),
            learned,
        )
        assertEquals(listOf("x-free"), kept.map { it.id })
    }

    @Test
    fun deadIdsAreFilteredOutEvenWhenTheyLookFree() {
        FreeLaneHealth.resetForTest()
        FreeLaneHealth.recordFailure(FreeLanes.ZEN_ID, "retired-free", FreeLaneEvidence.GONE)
        val kept = FreeLaneDirectory.visible(
            ZenFreeLane,
            listOf(model("retired-free"), model("alive-free")),
            emptyMap(),
            learnedNothing,
        )
        assertEquals(listOf("alive-free"), kept.map { it.id })
        FreeLaneHealth.resetForTest()
    }

    @Test
    fun needsProbeCoversDiscoveryAndLivenessButNeverASettledId() {
        val learned = FreeLaneLearned(free = setOf("known-free-model"), paid = setOf("known-paid"))
        val probe = FreeLaneDirectory.needsProbe(
            ZenFreeLane,
            listOf(
                model("known-free-model"),
                model("known-paid"),
                model("obvious-free"),
                model("big-pickle"),
                model("brand-new-name"),
            ),
            emptyMap(),
            learned,
            emptyMap(),
        )
        // known-paid is decided, known-free-model is proven and settled. The
        // other three each carry no verdict yet: the two Zen-shaped ids get a
        // liveness probe (nothing has ever observed whether upstream still
        // serves them) and the undeclared name gets a discovery probe.
        assertEquals(listOf("obvious-free", "big-pickle", "brand-new-name"), probe)
    }

    @Test
    fun aLaneThatDoesNotDiscoverOnlyProbesWhatItsOwnListingDeclared() {
        // Kilo declares free-ness in its listing, so an undeclared id is not a
        // mystery to resolve - it is simply not free, and probing it would only
        // manufacture evidence about a model the lane never shows.
        val probe = FreeLaneDirectory.needsProbe(
            KiloFreeLane,
            listOf(model("listed-free"), model("unlisted"), model("already-known")),
            mapOf("listed-free" to true, "unlisted" to false),
            FreeLaneLearned(free = setOf("already-known")),
            emptyMap(),
        )
        assertEquals(listOf("listed-free"), probe)
    }

    @Test
    fun aRecentlyProbedIdWaitsOutTheBackoffWindow() {
        val now = 1_000L
        val models = listOf(model("obvious-free"))
        val justProbed = FreeLaneDirectory.needsProbe(
            ZenFreeLane, models, emptyMap(), FreeLaneLearned(), mapOf("obvious-free" to now), now,
        )
        assertEquals(emptyList<String>(), justProbed)
        val afterWindow = FreeLaneDirectory.needsProbe(
            ZenFreeLane,
            models,
            emptyMap(),
            FreeLaneLearned(),
            mapOf("obvious-free" to now),
            now + FreeLaneDirectory.PROBE_BACKOFF_MS,
        )
        assertEquals(listOf("obvious-free"), afterWindow)
    }

    @Test
    fun needsProbeIsEmptyWhenEverythingIsAlreadyKnown() {
        val learned = FreeLaneLearned(free = setOf("a-model"), paid = setOf("b-model"))
        assertTrue(
            FreeLaneDirectory.needsProbe(ZenFreeLane, listOf(model("a-model"), model("b-model")),
                emptyMap(), learned, emptyMap()).isEmpty(),
        )
    }

    @Test
    fun noHardcodedFreeIdListExistsInProductionSources() {
        val root = productionSourceRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                FREE_ID.findAll(file.readText()).forEach { match ->
                    val id = match.groupValues[1]
                    if (id !in OFFLINE_SEED_IDS) offenders += "${file.name}: \"$id\""
                }
            }
        assertTrue(
            "free ids hardcoded outside the offline seed (the catalogue must be a rule, " +
                "not a list): $offenders",
            offenders.isEmpty(),
        )
    }

    private fun productionSourceRoot(): File {
        val candidates = listOf(
            "src/main/java",
            "app/src/main/java",
            "src/android/app/src/main/java",
        )
        for (candidate in candidates) {
            val dir = File(candidate)
            if (dir.isDirectory) return dir
        }
        error(
            "cannot locate production sources; user.dir=${File(".").absolutePath} " +
                "tried=$candidates",
        )
    }

    private companion object {
        val FREE_ID = Regex("\"([A-Za-z0-9._+-]+-free)\"")

        /** The only ids allowed to appear as literals: the no-network first-install seed. */
        val OFFLINE_SEED_IDS = setOf("deepseek-v4.1-flash-free")
    }
}
