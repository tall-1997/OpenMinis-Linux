package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-zen-bundled-model-patch] A hand-built instance pointing at the
 * bundled Zen endpoint wins the reconciliation skip check ("already represents
 * this service") — which previously meant the bundled free models were never
 * seeded anywhere and a never-refreshed hand-built instance stayed an empty
 * shell: the exact "no free models anywhere" report. The patch fills only the
 * empty case; every other shape must stay untouched.
 */
class ZenBundledModelSeedTest {

    private fun zenInstance(baseURL: String? = ZEN_BUNDLED_ENDPOINT) = ProviderInstance(
        id = "hand-built-zen",
        label = "Opencode",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = true,
        customBaseURL = baseURL,
        appendV1Suffix = false,
    )

    private fun entry(instanceId: String, modelId: String) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(modelId, modelId, "OpenCode Zen"),
    )

    @Test
    fun `empty hand-built zen instance needs the bundled models`() {
        assertTrue(zenInstanceNeedsBundledModels(zenInstance(), emptyList()))
    }

    @Test
    fun `zen instance with any own entry does not get re-seeded`() {
        assertFalse(
            zenInstanceNeedsBundledModels(
                zenInstance(),
                listOf(entry("hand-built-zen", "some-live-model")),
            ),
        )
    }

    @Test
    fun `entries belonging to other instances do not block the seed`() {
        // A foreign entry under a different instance id must not hide the
        // fact that THIS instance shows zero models.
        assertTrue(
            zenInstanceNeedsBundledModels(
                zenInstance(),
                listOf(entry("other-instance", "some-live-model")),
            ),
        )
    }

    @Test
    fun `trailing slash on the hand-built endpoint still matches`() {
        // isZenInstance matches with trimEnd('/'); the patch predicate must
        // agree, otherwise the seed silently disagrees with the skip check.
        assertTrue(
            zenInstanceNeedsBundledModels(
                zenInstance("$ZEN_BUNDLED_ENDPOINT/"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `non-zen endpoint never gets the bundled models`() {
        assertFalse(
            zenInstanceNeedsBundledModels(
                zenInstance("https://example.com/v1"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `null endpoint never gets the bundled models`() {
        assertFalse(zenInstanceNeedsBundledModels(zenInstance(null), emptyList()))
    }

    @Test
    fun `bundled models all survive the live-refresh filter`() {
        // If a bundled model id failed zenVisibleModels, a later live refresh
        // would visibly REMOVE it right after the seed added it.
        val ids = bundledZenModels().map { it.id }
        for (id in ids) {
            assertTrue(
                "bundled model '$id' would be dropped by the refresh filter",
                zenVisibleModels(listOf(LLMModel(id, id, "OpenCode Zen"))).isNotEmpty(),
            )
        }
    }

    /**
     * [T-zen-free-lane-follow] The visible list FOLLOWS the catalogue's free
     * lane; the only free ids that leave it are the ones with live
     * retirement evidence in the dead record. Advertised-but-flaky rows
     * (429 quota, 500 upstream) stay — the upstream advertises them, the
     * user can hide them, and a transient blip must not delete a row.
     */
    @Test
    fun `zenVisibleModels drops only dead-recorded free ids`() {
        val catalog = listOf(
            LLMModel("ling-3.0-flash-fin-free", "x", "OpenCode Zen"),   // retired: 400 endpoint-unavailable
            LLMModel("jev-1.13-free", "x", "OpenCode Zen"),              // flaky: 500 — stays
            LLMModel("muse-spark-1.2-contributor-free", "x", "OpenCode Zen"), // flaky: 429 — stays
        ) + bundledZenModels()
        val dead = setOf("ling-3.0-flash-fin-free")
        val visible = zenVisibleModels(catalog, dead)
        val expected = (catalog.map { it.id } - dead).sorted()
        assertEquals(expected, visible.map { it.id }.sorted())
    }

    @Test
    fun `zenVisibleModels follows new free ids the catalogue starts advertising`() {
        // The whole point of [T-zen-free-lane-follow]: upstream opens a new
        // free lane and it appears on the next refresh — no app release.
        val catalog = bundledZenModels() + LLMModel("brand-new-lane-free", "Brand New", "OpenCode Zen")
        assertTrue(zenVisibleModels(catalog).any { it.id == "brand-new-lane-free" })
    }

    @Test
    fun `isZenFreeLaneId matches the free-lane id shape`() {
        assertTrue(isZenFreeLaneId("big-pickle"))
        assertTrue(isZenFreeLaneId("anything-free"))
        assertTrue(isZenFreeLaneId("muse-spark-1.2-contributor-free"))
        assertFalse(isZenFreeLaneId("claude-opus-5"))
        assertFalse(isZenFreeLaneId("gpt-5.6"))
        assertFalse(isZenFreeLaneId("freestyle"))
        assertFalse(isZenFreeLaneId(""))
    }

    @Test
    fun `zenVisibleModels drops paid-lane rows a keyless instance cannot drive`() {
        val catalog = listOf(
            LLMModel("claude-something", "Claude", "OpenCode Zen"),
            LLMModel("gpt-something", "GPT", "OpenCode Zen"),
        ) + bundledZenModels()
        assertEquals(bundledZenModels().map { it.id }, zenVisibleModels(catalog).map { it.id })
    }

    @Test
    fun `the bundled free lane covers every id measured to answer 200`() {
        // The whole point of the fix: a keyless user picks from the free roster
        // and every row works. Re-measured 2026-10-08: mimo-v2.5-free answered
        // 401 "Model is not supported" and was de-advertised from the live
        // catalogue, so it left the roster — the other eight still stream 200.
        val measured = listOf(
            "big-pickle", "space-bunny-free", "mimo-v2.6-flash-free",
            "nemotron-3-ultra-free", "nemotron-3.5-lightning-free", "ling-3.1-flash-free",
            "longcat-2.5-preview-free", "fledge-alpha-free",
        )
        assertEquals(
            "bundled roster drifted from the measured-usable set",
            measured.toSet(),
            zenUsableFreeIds(),
        )
    }

    @Test
    fun `zenStaleEntryIds sweeps paid-lane and dead rows, spares flaky and custom`() {
        val custom = entry("zen-1", "claude-x").copy(isCustom = true) // user's own call — untouchable
        val entries = listOf(
            entry("zen-1", "jev-1.13-free"),   // free-lane, flaky 500 — survives now
            entry("zen-1", "big-pickle"),      // measured 200 — survives
            entry("zen-1", "claude-x"),        // paid lane — swept
            custom,                              // user-added paid id — SPARED
            entry("other-1", "claude-x"),     // different instance — untouchable
        )
        val stale = zenStaleEntryIds("zen-1", entries, deadIds = setOf("big-pickle"))
        // big-pickle is dead-recorded here, so it goes; jev stays; the custom
        // row and the foreign row never move.
        assertEquals(setOf(entries[2].id, entries[1].id), stale)
    }

    /**
     * The installed app seeded only space-bunny-free and swept everything
     * else. The sweep must leave the live free-lane rows alone so the caller
     * re-seeds the bundle on top of a non-empty instance — and the dead
     * record is what evicts the retired ones, not a shipped table.
     */
    @Test
    fun `the stale sweep keeps flaky free rows and evicts only the dead`() {
        val preUpgrade = listOf(
            entry("zen-1", "space-bunny-free"),
            entry("zen-1", "jev-1.13-free"),
            entry("zen-1", "muse-spark-1.3-contributor-free"),
        )
        val stale = zenStaleEntryIds(
            "zen-1",
            preUpgrade,
            deadIds = setOf("muse-spark-1.3-contributor-free"),
        )
        assertEquals(
            setOf(preUpgrade[2].id),
            stale,
        )
        // The surviving rows keep the instance non-empty, so the caller's
        // re-seed tops up rather than rescues.
        assertTrue(preUpgrade.any { it.id !in stale })
    }

    /**
     * [T-zen-usable-free-lane-fallback] The models.dev fallback is a
     * HOSTNAME-keyed catalogue, so for the Zen host it answers with every paid
     * lane plus every advertised free-lane id. Writing it back unfiltered is
     * what put 86 rows in front of a user picking a keyless free model, so
     * the fallback must go through the same filter the live /v1/models
     * fetch does — free lane minus the dead record, not a shipped table.
     */
    @Test
    fun `zenVisibleModels collapses a full models dev shaped catalogue to the free lane`() {
        val catalogue = buildList {
            addAll(bundledZenModels())
            add(LLMModel("claude-opus-5", "Claude Opus", "OpenCode Zen"))
            add(LLMModel("gpt-5.6", "GPT", "OpenCode Zen"))
            add(LLMModel("gemini-3-pro", "Gemini", "OpenCode Zen"))
            listOf(
                "ling-3.0-flash-fin-free", "jev-1.13-free", "deepseek-v4-flash-free",
                "muse-spark-1.2-contributor-free", "muse-spark-1.3-contributor-free",
            ).forEach { add(LLMModel(it, it, "OpenCode Zen")) }
        }
        val dead = setOf("ling-3.0-flash-fin-free", "deepseek-v4-flash-free")
        val visible = zenVisibleModels(catalogue, dead)
        val expectedFreeLane = catalogue.map { it.id }.filter { isZenFreeLaneId(it) } - dead
        assertEquals(
            "only the catalogue's live free lane may reach a keyless Zen instance",
            expectedFreeLane.sorted(),
            visible.map { it.id }.sorted(),
        )
    }
}
