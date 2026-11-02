package com.openminis.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

/**
 * The health store must not scrub manifest lanes on the very boot that seeds
 * them, and must scrub a lane once it genuinely stops shipping. That decision
 * is [FreeLaneHealth.registerLanes] + the initialize filter, and it needs a
 * real SharedPreferences to prove — the pure JVM tests cannot see the disk
 * round-trip.
 *
 * Scenario pinned here:
 * 1. A manifest lane records death evidence for a model.
 * 2. On the next boot the manifest is missing (offline first run) and
 *    registerLanes never runs — without a persisted registry the lane's
 *    evidence would be scrubbed as "a lane nothing ships any more" before
 *    the seed pass gets a chance to re-register it.
 * 3. The persisted active-id snapshot is what registerLanes is seeded from,
 *    so the entry must survive that boot too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ManifestLaneHealthScrubTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val healthPrefs = context.getSharedPreferences("free_lane_health", Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences("zen_free_lane_health", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        FreeLaneHealth.resetForTest()
        healthPrefs.edit().clear().commit()
        legacyPrefs.edit().clear().commit()
        FreeLaneHealth.nowProvider = { 1_000_000L }
    }

    @After
    fun tearDown() {
        FreeLaneHealth.nowProvider = { System.currentTimeMillis() }
        FreeLaneHealth.resetForTest()
    }

    private fun writeDeadEntries(vararg keys: String) {
        val json = JSONObject()
        keys.forEach { json.put(it, 900_000L) }
        healthPrefs.edit().putString("dead_ids", json.toString()).commit()
    }

    @Test
    fun aRegisteredManifestLaneSurvivesInitialize() {
        FreeLaneHealth.registerLanes(setOf("manifest-demo"))
        writeDeadEntries("manifest-demo:some-model", "zen:another-model")
        FreeLaneHealth.initialize(context)

        assertTrue(FreeLaneHealth.isDead("manifest-demo", "some-model"))
        assertTrue(FreeLaneHealth.isDead("zen", "another-model"))
    }

    @Test
    fun anUnregisteredScopeIsScrubbedOnInitialize() {
        // The manifest dropped this lane: nothing registers it any more, so
        // its entries must leave the store instead of lingering to TTL.
        FreeLaneHealth.registerLanes(setOf("zen"))
        writeDeadEntries("manifest-demo:gone-model", "zen:still-dead")
        FreeLaneHealth.initialize(context)

        assertFalse(FreeLaneHealth.isDead("manifest-demo", "gone-model"))
        assertTrue(FreeLaneHealth.isDead("zen", "still-dead"))
    }

    @Test
    fun legacyFlatEntriesAdoptTheZenScope() {
        // Flat ids predate lane scoping and always meant Zen; a colon in the
        // MODEL id must not be read as a lane scope. They live in the legacy
        // store, so this exercises the one-time migration, not the live filter.
        val json = JSONObject()
        json.put("deepseek-v4.1-flash-free", 900_000L)
        json.put("nvidia/nemotron-3.5-lightning:free", 900_000L)
        legacyPrefs.edit().putString("dead_ids", json.toString()).commit()
        FreeLaneHealth.initialize(context)

        assertTrue(FreeLaneHealth.isDead(FreeLanes.ZEN_ID, "deepseek-v4.1-flash-free"))
        assertTrue(FreeLaneHealth.isDead(FreeLanes.ZEN_ID, "nvidia/nemotron-3.5-lightning:free"))
    }

    @Test
    fun registeringAfterInitializeRecoversNothing() {
        // Register-lanes-first is the boot order; registering later must not
        // resurrect entries the filter already dropped.
        writeDeadEntries("manifest-demo:gone-model")
        FreeLaneHealth.initialize(context)
        FreeLaneHealth.registerLanes(setOf("manifest-demo"))

        assertFalse(FreeLaneHealth.isDead("manifest-demo", "gone-model"))
    }
}
