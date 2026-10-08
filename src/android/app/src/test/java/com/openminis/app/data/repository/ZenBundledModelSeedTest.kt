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

    /** [T-zen-usable-free-lane] The visible list IS the usable list. */
    @Test
    fun `zenVisibleModels drops free-lane ids the upstream refuses`() {
        // Live-measured 2026-10-05 with a canonical session id: three ids are
        // retired upstream (400/500 naming the endpoint or model) and two are
        // geo-fenced (403 RegionError). None of them can ever answer, so
        // showing them is what made the user pick a model that never works.
        val dead = listOf(
            "ling-3.0-flash-fin-free",   // 400 "Endpoint is unavailable"
            "deepseek-v4-flash-free",    // 400 "Model is unavailable"
            "jev-1.13-free",             // 500 upstream
            "muse-spark-1.2-contributor-free",  // 403 RegionError
            "muse-spark-1.3-contributor-free",  // 403 RegionError
        )
        val catalog = dead.map { LLMModel(it, it, "OpenCode Zen") } + bundledZenModels()
        val visible = zenVisibleModels(catalog)
        assertEquals(bundledZenModels().map { it.id }, visible.map { it.id })
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
    fun `zenStaleEntryIds marks only this instance's entries outside the usable set`() {
        val entries = listOf(
            entry("zen-1", "jev-1.13-free"),         // 500 upstream — dead
            entry("zen-1", "big-pickle"),            // measured 200 — must survive
            entry("zen-1", "claude-x"),              // paid lane — dead for keyless
            entry("other-1", "big-pickle"),         // different instance — untouchable
        )
        val stale = zenStaleEntryIds("zen-1", entries)
        assertEquals(setOf(entries[0].id, entries[2].id), stale)
    }

    /**
     * The installed app seeded only space-bunny-free and swept everything else.
     * Now that the roster is measured-usable, the sweep must RE-ADD the other
     * eight — otherwise every existing installation keeps seeing one model
     * after the upgrade that ships them.
     */
    @Test
    fun `the stale sweep re-seeds the full measured roster into an existing instance`() {
        val preUpgrade = listOf(
            entry("zen-1", "space-bunny-free"),
            entry("zen-1", "jev-1.13-free"),
            entry("zen-1", "muse-spark-1.3-contributor-free"),
        )
        val stale = zenStaleEntryIds("zen-1", preUpgrade)
        // The dead rows go, and because a usable row survives, the instance is
        // not emptied — so the caller re-seeds the bundle on top of it.
        assertEquals(
            setOf("jev-1.13-free", "muse-spark-1.3-contributor-free"),
            stale.map { id -> preUpgrade.first { it.id == id }.baseModel.id }.toSet(),
        )
        assertTrue(
            "the surviving row must keep the instance non-empty",
            preUpgrade.any { it.id !in stale },
        )
    }

    /**
     * [T-zen-usable-free-lane-fallback] The models.dev fallback is a
     * HOSTNAME-keyed catalogue, so for the Zen host it answers with every paid
     * lane plus every refused free-lane id. Writing it back unfiltered is what
     * put 86 rows (12 of them permanently 403/500) in front of a user picking
     * a keyless free model, so the fallback must go through the same filter the
     * live /v1/models fetch does.
     */
    @Test
    fun `zenVisibleModels collapses a full models dev shaped catalogue to the usable set`() {
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
        val visible = zenVisibleModels(catalogue)
        assertEquals(
            "only the measured-usable free lane may reach a keyless Zen instance",
            bundledZenModels().map { it.id },
            visible.map { it.id },
        )
    }
}
