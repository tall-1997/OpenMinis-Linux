package com.openminis.app.data

import com.openminis.app.agent.HarnessConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-harness-config-store] The per-model override: persisted, isolated per
 * model, and never able to break a turn (an invalid file is "no override").
 */
class HarnessConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        HarnessConfigStore.clearForTest()
    }

    @After
    fun tearDown() {
        HarnessConfigStore.clearForTest()
    }

    @Test
    fun missingEntryFallsBackToTheShippedDefaults() {
        val dir = tmp.newFolder()
        assertEquals(HarnessConfig.DEFAULT, HarnessConfigStore.forModel(dir, "m1"))
        assertFalse(HarnessConfigStore.hasOverride(dir, "m1"))
    }

    @Test
    fun aSavedOverrideIsReadBackAndIsolatedPerModel() {
        val dir = tmp.newFolder()
        val custom = HarnessConfig.DEFAULT.copy(postAnchorBudgetTokens = 24_000)
        assertTrue(HarnessConfigStore.save(dir, "m1", custom))
        assertEquals(24_000, HarnessConfigStore.forModel(dir, "m1").postAnchorBudgetTokens)
        // A second model must NOT inherit the first one's number.
        assertEquals(
            HarnessConfig.DEFAULT.postAnchorBudgetTokens,
            HarnessConfigStore.forModel(dir, "m2").postAnchorBudgetTokens,
        )
    }

    @Test
    fun savingNullRestoresTheDefaults() {
        val dir = tmp.newFolder()
        val custom = HarnessConfig.DEFAULT.copy(postAnchorBudgetTokens = 20_000)
        assertTrue(HarnessConfigStore.save(dir, "m1", custom))
        assertTrue(HarnessConfigStore.save(dir, "m1", null))
        assertFalse(HarnessConfigStore.hasOverride(dir, "m1"))
        assertEquals(HarnessConfig.DEFAULT, HarnessConfigStore.forModel(dir, "m1"))
    }

    @Test
    fun anInvalidStoredConfigBehavesLikeNoOverride() {
        val dir = tmp.newFolder()
        val target = HarnessConfigStore.file(dir, "m1")
        target.parentFile?.mkdirs()
        target.writeText("{ this is not json", Charsets.UTF_8)
        assertEquals(HarnessConfig.DEFAULT, HarnessConfigStore.forModel(dir, "m1"))
    }

    @Test
    fun anOutOfRangeStoredConfigIsRejectedByValidation() {
        val dir = tmp.newFolder()
        val target = HarnessConfigStore.file(dir, "m1")
        target.parentFile?.mkdirs()
        val bad = HarnessConfigStore.toJson(HarnessConfig.DEFAULT).replace(
            "\"v\":1", "\"v\":1,\"postAnchorWindowFraction\":5.0",
        )
        target.writeText(bad, Charsets.UTF_8)
        assertEquals(HarnessConfig.DEFAULT, HarnessConfigStore.forModel(dir, "m1"))
    }

    @Test
    fun theJsonRoundTripKeepsEveryField() {
        val custom = HarnessConfig(
            postAnchorBudgetTokens = 33_000,
            noticeReserveTokens = 900,
            absoluteAgeTokens = 12_000,
            digestMaxChars = 1_500,
            ledgerMaxEntries = 300,
        )
        assertEquals(custom, HarnessConfigStore.fromJson(HarnessConfigStore.toJson(custom)))
    }

    @Test
    fun theStoredFileLivesUnderTheHarnessConfigDirectory() {
        val dir = tmp.newFolder()
        HarnessConfigStore.save(dir, "model/with space", HarnessConfig.DEFAULT.copy(postAnchorBudgetTokens = 10_000))
        val stored = HarnessConfigStore.file(dir, "model/with space")
        assertNotNull(stored.parentFile)
        assertEquals(HarnessConfigStore.DIR, stored.parentFile?.name)
        // The unsafe characters must be neutralized, not passed through.
        assertTrue(stored.name.none { it == '/' })
    }

    @Test
    fun anUnknownEntryIdIsNeverStored() {
        val dir = tmp.newFolder()
        assertNull(HarnessConfigStore.keyOf("  "))
        assertFalse(HarnessConfigStore.save(dir, "  ", HarnessConfig.DEFAULT))
        assertEquals(HarnessConfig.DEFAULT, HarnessConfigStore.forModel(dir, ""))
    }
}
