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
class ZenFreeLaneCatalogTest {

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "zen")

    private val learnedNothing = ZenLearnedFree()

    @Test
    fun anyFreeSuffixCountsRegardlessOfCase() {
        assertTrue(ZenFreeLaneCatalog.isFreeLaneModelId("deepseek-v4.1-flash-free"))
        assertTrue(ZenFreeLaneCatalog.isFreeLaneModelId("Some-New-Model-FREE"))
        assertTrue(ZenFreeLaneCatalog.isFreeLaneModelId("a-Free"))
    }

    @Test
    fun bigPickleIsTheOnlyKnownSuffixFreeException() {
        assertTrue(ZenFreeLaneCatalog.isFreeLaneModelId("big-pickle"))
        assertTrue(ZenFreeLaneCatalog.isFreeLaneModelId("Big-Pickle"))
        assertFalse(ZenFreeLaneCatalog.isFreeLaneModelId("gpt-5.5"))
        assertFalse(ZenFreeLaneCatalog.isFreeLaneModelId("free"))
        assertFalse(ZenFreeLaneCatalog.isFreeLaneModelId("freebird"))
        assertFalse(ZenFreeLaneCatalog.isFreeLaneModelId(""))
    }

    @Test
    fun aNewlyLearnedFreeIdSurvivesTheFilter() {
        val learned = ZenLearnedFree(free = setOf("mystery-model"))
        val kept = ZenFreeLaneCatalog.filterFree(
            listOf(model("mystery-model"), model("gpt-5.5")),
            learned,
        )
        assertEquals(listOf("mystery-model"), kept.map { it.id })
    }

    @Test
    fun paidIdsNeverAppearEvenWhenTheCatalogueListsThem() {
        val learned = ZenLearnedFree(
            free = setOf("mystery-model"),
            paid = setOf("mystery-model", "another-one"),
        )
        val kept = ZenFreeLaneCatalog.filterFree(
            listOf(model("mystery-model"), model("another-one"), model("x-free")),
            learned,
        )
        assertEquals(listOf("x-free"), kept.map { it.id })
    }

    @Test
    fun deadIdsAreFilteredOutEvenWhenTheyLookFree() {
        ZenFreeLaneHealth.resetForTest()
        ZenFreeLaneHealth.recordFailure("retired-free", ZenFailureEvidence.GONE)
        val kept = ZenFreeLaneCatalog.filterFree(
            listOf(model("retired-free"), model("alive-free")),
            learnedNothing,
        )
        assertEquals(listOf("alive-free"), kept.map { it.id })
        ZenFreeLaneHealth.resetForTest()
    }

    @Test
    fun needsProbeReturnsOnlyIdsThatAreNewAndSuffixFree() {
        val learned = ZenLearnedFree(free = setOf("known-free-model"), paid = setOf("known-paid"))
        val probe = ZenFreeLaneCatalog.needsProbe(
            listOf(
                model("known-free-model"),
                model("known-paid"),
                model("obvious-free"),
                model("big-pickle"),
                model("brand-new-name"),
            ),
            learned,
        )
        assertEquals(listOf("brand-new-name"), probe)
    }

    @Test
    fun needsProbeIsEmptyWhenEverythingIsAlreadyKnown() {
        val learned = ZenLearnedFree(free = setOf("a-model"), paid = setOf("b-model"))
        assertTrue(
            ZenFreeLaneCatalog.needsProbe(listOf(model("a-model"), model("b-model")), learned).isEmpty(),
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
