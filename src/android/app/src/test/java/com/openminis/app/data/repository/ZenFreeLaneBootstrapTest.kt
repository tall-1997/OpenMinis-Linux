package com.openminis.app.data.repository

import com.openminis.app.data.model.InstanceRemoval
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelDeletion
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six device states the free lane has to survive, as a matrix.
 *
 * Every one of these is a real device in the wild: fresh installs, installs
 * that carried the seed across versions, installs where #61 deleted the seed on
 * launch, installs where the user deleted it on purpose, installs where the
 * user built their own Zen provider with a real key, and installs where the
 * user pruned individual models. They are indistinguishable in some cases —
 * state S3 and S4 leave the same bytes on disk — which is exactly why the
 * instance-level tombstone exists.
 */
class ZenFreeLaneBootstrapTest {

    private val id get() = ZenFreeLaneBootstrap.INSTANCE_ID

    private fun builtin(label: String = ZenFreeLaneBootstrap.LABEL) = ProviderInstance(
        id = id,
        label = label,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = ZenFreeLaneBootstrap.ENDPOINT,
        appendV1Suffix = false,
    )

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "Zen")

    private fun entry(modelId: String, uuid: String = modelId + "-uuid") = ModelEntry(
        providerInstanceId = id,
        baseModel = model(modelId),
        uuid = uuid,
    )

    // ---- S1: fresh install --------------------------------------------------

    @Test
    fun s1FreshInstallSeedsTheBuiltinInstance() {
        val config = ProviderConfig()
        assertTrue(ZenFreeLaneBootstrap.ensureSeeded(config))
        val seeded = config.instances.single()
        assertEquals("builtin-opencode-zen", seeded.id)
        assertEquals("OpenCode Zen (Free)", seeded.label)
        assertEquals("https://opencode.ai/zen/v1", seeded.customBaseURL)
        assertEquals(ProviderType.openAI, seeded.providerType)
        assertEquals(ProviderCredential.apiKey, seeded.credentialType)
        assertTrue(seeded.isEnabled)
        assertFalse("the endpoint already carries /v1", seeded.appendV1Suffix)
    }

    @Test
    fun s1SeedingIsIdempotent() {
        val config = ProviderConfig()
        assertTrue(ZenFreeLaneBootstrap.ensureSeeded(config))
        assertFalse(ZenFreeLaneBootstrap.ensureSeeded(config))
        assertEquals(1, config.instances.size)
    }

    @Test
    fun s1ANameClashGetsASuffixInsteadOfTwoIdenticalLabels() {
        val config = ProviderConfig()
        config.instances.add(
            ProviderInstance(
                id = "something-else",
                label = ZenFreeLaneBootstrap.LABEL,
                providerType = ProviderType.openRouter,
                credentialType = ProviderCredential.apiKey,
            ),
        )
        assertTrue(ZenFreeLaneBootstrap.ensureSeeded(config))
        val seeded = config.instances.first { it.id == id }
        assertEquals("OpenCode Zen (Free) · Built-in", seeded.label)
    }

    // ---- S2: legacy device with customisation ------------------------------

    @Test
    fun s2AnExistingFreeInstanceKeepsItsCustomisation() {
        val config = ProviderConfig()
        config.instances.add(builtin(label = "My renamed Zen"))
        val kept = entry("deepseek-v4.1-flash-free").let {
            ModelEntry(
                providerInstanceId = id,
                baseModel = it.baseModel,
                uuid = it.uuid,
                isHidden = true,
                userModifiedAt = 1234L,
            )
        }
        config.modelEntries.add(kept)
        config.modelEntries.add(entry("space-bunny-free"))

        assertFalse("an existing free lane is never rebuilt", ZenFreeLaneBootstrap.ensureSeeded(config))
        assertEquals("My renamed Zen", config.instances.single().label)
        // Row order, uuids and per-row user state all survive untouched.
        assertEquals(listOf(kept.uuid, "space-bunny-free-uuid"), config.modelEntries.map { it.uuid })
        assertTrue(config.modelEntries[0].isHidden)
        assertEquals(1234L, config.modelEntries[0].userModifiedAt)
    }

    @Test
    fun s2DeadSeedRowsArePrunedByTheCatalogueNotByBootstrap() {
        // The five ids the old hardcoded seed shipped that upstream killed.
        // Bootstrap must not know this list — pruning is the catalogue's job
        // (ZenFreeLaneCatalog.filterFree), so a model that comes back upstream
        // comes back here without an app update.
        val dead = listOf(
            "space-bunny-free",
            "nemotron-3-ultra-free",
            "ling-3.1-flash-free",
            "longcat-2.5-preview-free",
            "fledge-alpha-free",
        )
        val liveCatalogue = listOf(model("deepseek-v4.1-flash-free"), model("big-pickle"))
        val staleRows = (dead + "deepseek-v4.1-flash-free").map { entry(it) }

        val survivors = com.openminis.app.provider.ZenFreeLaneCatalog.filterFree(
            liveCatalogue,
            com.openminis.app.provider.ZenLearnedFree(),
        ).map { it.id }
        assertEquals(listOf("deepseek-v4.1-flash-free", "big-pickle"), survivors)
        assertTrue("no dead id is hardcoded anywhere in this test's path", dead.none { it in survivors })
        assertEquals(6, staleRows.size)
    }

    // ---- S3 vs S4: who deleted it ------------------------------------------

    @Test
    fun s3AnInstanceRetiredBy61IsReseededUnderTheSameId() {
        // #61 removed the instance and its rows without recording a tombstone —
        // that was our decision, not the user's — so the same id comes back and
        // every old slot reference and last-used pointer revives with it.
        val config = ProviderConfig()
        config.defaultPrimaryGroupId = "entry:deepseek-v4.1-flash-free-uuid"

        assertTrue(ZenFreeLaneBootstrap.ensureSeeded(config))
        assertEquals(id, config.instances.single().id)
    }

    @Test
    fun s4AnInstanceTheUserRemovedIsNotReseeded() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        assertTrue(InstanceRemoval.record(config, id))
        config.instances.removeAll { it.id == id }

        assertFalse(ZenFreeLaneBootstrap.ensureSeeded(config))
        assertTrue(config.instances.isEmpty())
        assertTrue(InstanceRemoval.wasRemovedByUser(config, id))
    }

    @Test
    fun s4ATombstoneForAnotherInstanceDoesNotBlockSeeding() {
        val config = ProviderConfig()
        InstanceRemoval.record(config, "some-other-provider")
        assertTrue(ZenFreeLaneBootstrap.ensureSeeded(config))
    }

    // ---- S5: user-built Zen with a real key --------------------------------

    @Test
    fun s5AUserBuiltZenInstanceWithARealKeyIsNeverTouched() {
        val config = ProviderConfig()
        val mine = ProviderInstance(
            id = "my-own-zen",
            label = "My paid Zen",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://opencode.ai/zen/v1",
            appendV1Suffix = false,
        )
        config.instances.add(mine)
        config.modelEntries.add(
            ModelEntry(providerInstanceId = "my-own-zen", baseModel = model("gpt-5.5")),
        )
        val entriesBefore = config.modelEntries.toList()

        assertFalse(ZenFreeLaneBootstrap.isZenFreeInstance(mine, "sk-real-key"))
        ZenFreeLaneBootstrap.ensureSeeded(config) { instanceId ->
            if (instanceId == "my-own-zen") "sk-real-key" else null
        }

        val untouched = config.instances.first { it.id == "my-own-zen" }
        assertEquals("My paid Zen", untouched.label)
        assertEquals("https://opencode.ai/zen/v1", untouched.customBaseURL)
        assertFalse(untouched.appendV1Suffix)
        assertEquals(entriesBefore, config.modelEntries.filter { it.providerInstanceId == "my-own-zen" })
    }

    @Test
    fun s5ThePublicPlaceholderOnAForeignIdStillCountsAsTheFreeLane() {
        val odd = ProviderInstance(
            id = "zen-free-legacy-spelling",
            label = "Zen",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://opencode.ai/zen/v1",
        )
        assertTrue(ZenFreeLaneBootstrap.isZenFreeInstance(odd, "public"))
        assertFalse(ZenFreeLaneBootstrap.isZenFreeInstance(odd, null))
    }

    @Test
    fun s5ANonZenHostWithAPublicKeyIsNotTheFreeLane() {
        val other = ProviderInstance(
            id = "ollama-local",
            label = "Ollama",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "http://192.168.1.5:11434/v1",
        )
        assertFalse(ZenFreeLaneBootstrap.isZenFreeInstance(other, "public"))
    }

    // ---- S6: model-level tombstones ----------------------------------------

    @Test
    fun s6ModelTombstonesSurviveSeeding() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        val target = entry("big-pickle")
        config.modelEntries.add(target)
        assertTrue(ModelDeletion.recordDeletion(config, target))

        assertFalse(ZenFreeLaneBootstrap.ensureSeeded(config))
        assertEquals(
            listOf("big-pickle"),
            config.deletedModelIds[id].orEmpty(),
        )
    }

    @Test
    fun s6OnlyTheExplicitRestoreChannelClearsModelTombstones() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        val target = entry("big-pickle")
        config.modelEntries.add(target)
        ModelDeletion.recordDeletion(config, target)
        val other = ProviderInstance(
            id = "unrelated", label = "Other",
            providerType = ProviderType.anthropic, credentialType = ProviderCredential.apiKey,
        )
        config.instances.add(other)
        config.modelEntries.add(
            ModelEntry(providerInstanceId = "unrelated", baseModel = model("claude-4.6")).also {
                ModelDeletion.recordDeletion(config, it)
            },
        )

        assertTrue(ZenFreeLaneBootstrap.restore(config))
        assertNull("the free lane's tombstones are cleared", config.deletedModelIds[id])
        assertEquals(
            listOf("claude-4.6"),
            config.deletedModelIds["unrelated"],
        )
    }

    // ---- restore ------------------------------------------------------------

    @Test
    fun restoreRevivesAnInstanceTheUserDeleted() {
        val config = ProviderConfig()
        InstanceRemoval.record(config, id)
        config.deletedModelIds[id] = listOf("big-pickle")

        assertTrue(ZenFreeLaneBootstrap.restore(config))
        assertEquals(id, config.instances.single().id)
        assertFalse(InstanceRemoval.wasRemovedByUser(config, id))
        assertNull(config.deletedModelIds[id])
    }

    @Test
    fun restoreIsANoOpWhenEverythingIsAlreadyThere() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        assertFalse(ZenFreeLaneBootstrap.restore(config))
    }

    @Test
    fun restoreDoesNotDuplicateRowsOrInstances() {
        val config = ProviderConfig()
        assertTrue(ZenFreeLaneBootstrap.restore(config))
        assertFalse(ZenFreeLaneBootstrap.restore(config))
        assertEquals(1, config.instances.size)
    }

    // ---- default slot repair ------------------------------------------------

    @Test
    fun repairDefaultSlotFillsOnlyASlotThisMigrationEmptied() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        config.modelEntries.add(entry("deepseek-v4.1-flash-free"))

        assertTrue(ZenFreeLaneBootstrap.repairDefaultSlot(config, emptiedByMigration = true))
        assertEquals(
            "entry:deepseek-v4.1-flash-free-uuid",
            config.defaultPrimaryGroupId,
        )
    }

    @Test
    fun repairDefaultSlotLeavesASlotTheUserDeliberatelyLeftEmpty() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        config.modelEntries.add(entry("deepseek-v4.1-flash-free"))

        assertFalse(ZenFreeLaneBootstrap.repairDefaultSlot(config, emptiedByMigration = false))
        assertNull(config.defaultPrimaryGroupId)
    }

    @Test
    fun repairDefaultSlotNeverOverwritesAWorkingSlot() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        config.modelEntries.add(entry("deepseek-v4.1-flash-free"))
        config.defaultPrimaryGroupId = "my-existing-group"

        assertFalse(ZenFreeLaneBootstrap.repairDefaultSlot(config, emptiedByMigration = true))
        assertEquals("my-existing-group", config.defaultPrimaryGroupId)
    }

    @Test
    fun repairDefaultSlotWithoutAnyVisibleRowChangesNothing() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        assertFalse(ZenFreeLaneBootstrap.repairDefaultSlot(config, emptiedByMigration = true))
        assertNull(config.defaultPrimaryGroupId)
    }

    // ---- the universal removeInstance bug -----------------------------------

    @Test
    fun removingAnInstanceAlsoClearsItsModelTombstones() {
        // ProviderRepository.removeInstance cleared instances, rows, group
        // members, agent-loop ids, empty groups, the api key, OAuth, voice
        // shadow, last-used entry and thinking rules — but not
        // deletedModelIds[instanceId]. Any instance rebuilt under the same id
        // inherited the old tombstones, so its models vanished silently.
        val config = ProviderConfig()
        config.instances.add(builtin())
        val target = entry("big-pickle")
        config.modelEntries.add(target)
        ModelDeletion.recordDeletion(config, target)
        assertNotNull(config.deletedModelIds[id])

        ZenFreeLaneBootstrap.forgetInstance(config, id)

        assertNull(config.deletedModelIds[id])
        assertTrue(InstanceRemoval.wasRemovedByUser(config, id))
    }

    @Test
    fun forgettingAForeignInstanceClearsTombstonesWithoutASeedTombstone() {
        val config = ProviderConfig()
        val other = ProviderInstance(
            id = "unrelated", label = "Other",
            providerType = ProviderType.anthropic, credentialType = ProviderCredential.apiKey,
        )
        config.instances.add(other)
        config.modelEntries.add(
            ModelEntry(providerInstanceId = "unrelated", baseModel = model("claude-4.6")).also {
                ModelDeletion.recordDeletion(config, it)
            },
        )

        ZenFreeLaneBootstrap.forgetInstance(config, "unrelated")

        assertNull(config.deletedModelIds["unrelated"])
        assertFalse("not our seed, so no re-seed tombstone", InstanceRemoval.wasRemovedByUser(config, "unrelated"))
    }
}
