package com.openminis.app.data.repository

import com.openminis.app.data.model.InstanceRemoval
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelDeletion
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.BuiltinFreeLanes
import com.openminis.app.provider.KiloFreeLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device states the built-in free lanes have to survive, as a matrix.
 *
 * Every one of these is a real device in the wild: fresh installs, installs
 * that carried the seed across versions, installs where #61 deleted the seed on
 * launch, installs where the user deleted it on purpose, installs where the
 * user built their own provider at the same upstream with a real key, and
 * installs where the user pruned individual models. They are indistinguishable
 * in some cases — state S3 and S4 leave the same bytes on disk — which is
 * exactly why the instance-level tombstone exists.
 *
 * The matrix is written against one lane (Zen, the oldest) but every assertion
 * that matters holds per lane, because seeding, recognition and restore all
 * take a [com.openminis.app.provider.BuiltinFreeLane] rather than naming one.
 * The two-lane cases are pinned separately at the bottom.
 */
class FreeLaneBootstrapTest {

    private val zen get() = BuiltinFreeLanes.ZEN
    private val id get() = zen.instanceId

    private fun builtin(label: String = zen.label) = ProviderInstance(
        id = id,
        label = label,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = zen.baseURL,
        appendV1Suffix = false,
    )

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "Zen")

    private fun entry(modelId: String, uuid: String = modelId + "-uuid") = ModelEntry(
        providerInstanceId = id,
        baseModel = model(modelId),
        uuid = uuid,
    )

    private fun ProviderConfig.instanceIds() = instances.map { it.id }

    /** Which of our seeds this config already counts as present. */
    private fun seededIds(config: ProviderConfig) = FreeLaneBootstrap.ensureSeeded(config).map { it.instanceId }

    // ---- S1: fresh install --------------------------------------------------

    @Test
    fun s1FreshInstallSeedsEveryBuiltinLane() {
        val config = ProviderConfig()
        assertEquals(listOf(zen.instanceId, BuiltinFreeLanes.KILO.instanceId), seededIds(config))

        val seeded = config.instances.first { it.id == id }
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
        assertEquals(2, seededIds(config).size)
        assertTrue("a second pass adds nothing", FreeLaneBootstrap.ensureSeeded(config).isEmpty())
        assertEquals(2, config.instances.size)
    }

    @Test
    fun s1ANameClashGetsASuffixInsteadOfTwoIdenticalLabels() {
        val config = ProviderConfig()
        config.instances.add(
            ProviderInstance(
                id = "something-else",
                label = zen.label,
                providerType = ProviderType.openRouter,
                credentialType = ProviderCredential.apiKey,
            ),
        )
        assertTrue(FreeLaneBootstrap.ensureSeeded(config).isNotEmpty())
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

        assertEquals(
            "an existing free lane is never rebuilt; only the missing one is seeded",
            listOf(BuiltinFreeLanes.KILO.instanceId),
            seededIds(config),
        )
        assertEquals("My renamed Zen", config.instances.first { it.id == id }.label)
        // Row order, uuids and per-row user state all survive untouched.
        assertEquals(listOf(kept.uuid, "space-bunny-free-uuid"), config.modelEntries.map { it.uuid })
        assertTrue(config.modelEntries[0].isHidden)
        assertEquals(1234L, config.modelEntries[0].userModifiedAt)
    }

    @Test
    fun s2DeadSeedRowsArePrunedByTheCatalogueNotByBootstrap() {
        // The five ids the old hardcoded seed shipped that upstream killed.
        // Bootstrap must not know this list — pruning is the catalogue's job
        // (FreeLaneDirectory.visible), so a model that comes back upstream
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

        val survivors = com.openminis.app.provider.FreeLaneDirectory.visible(
            com.openminis.app.provider.ZenFreeLane,
            liveCatalogue,
            emptyMap(),
            com.openminis.app.provider.FreeLaneLearned(),
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

        assertTrue(FreeLaneBootstrap.ensureSeeded(config).isNotEmpty())
        assertTrue(id in config.instanceIds())
        assertEquals("entry:deepseek-v4.1-flash-free-uuid", config.defaultPrimaryGroupId)
    }

    @Test
    fun s4AnInstanceTheUserRemovedIsNotReseeded() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        assertTrue(InstanceRemoval.record(config, id))
        config.instances.removeAll { it.id == id }

        assertEquals(
            "the other lane still seeds",
            listOf(BuiltinFreeLanes.KILO.instanceId),
            seededIds(config),
        )
        assertFalse(id in config.instanceIds())
        assertTrue(InstanceRemoval.wasRemovedByUser(config, id))
    }

    @Test
    fun s4ATombstoneForAnotherInstanceDoesNotBlockSeeding() {
        val config = ProviderConfig()
        InstanceRemoval.record(config, "some-other-provider")
        assertEquals(2, seededIds(config).size)
    }

    // ---- S5: a user-built provider at the same upstream ---------------------

    @Test
    fun s5AUserBuiltInstanceWithARealKeyIsNeverTouched() {
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

        assertFalse(FreeLaneBootstrap.belongsTo(zen, mine, "sk-real-key"))
        FreeLaneBootstrap.ensureSeeded(config) { instanceId ->
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
        assertTrue(FreeLaneBootstrap.belongsTo(zen, odd, "public"))
        assertFalse(FreeLaneBootstrap.belongsTo(zen, odd, null))
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
        assertFalse(FreeLaneBootstrap.belongsTo(zen, other, "public"))
        assertNull(BuiltinFreeLanes.forInstance(other, "public"))
    }

    // ---- S6: model-level tombstones ----------------------------------------

    @Test
    fun s6ModelTombstonesSurviveSeeding() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        val target = entry("big-pickle")
        config.modelEntries.add(target)
        assertTrue(ModelDeletion.recordDeletion(config, target))

        assertFalse(id in seededIds(config))
        assertEquals(listOf("big-pickle"), config.deletedModelIds[id].orEmpty())
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

        assertEquals(zen, FreeLaneBootstrap.restore(config, zen))
        assertNull("this lane's tombstones are cleared", config.deletedModelIds[id])
        assertEquals(listOf("claude-4.6"), config.deletedModelIds["unrelated"])
    }

    // ---- restore ------------------------------------------------------------

    @Test
    fun restoreRevivesAnInstanceTheUserDeleted() {
        val config = ProviderConfig()
        InstanceRemoval.record(config, id)
        config.deletedModelIds[id] = listOf("big-pickle")

        assertEquals(zen, FreeLaneBootstrap.restore(config, zen))
        assertTrue(id in config.instanceIds())
        assertFalse(InstanceRemoval.wasRemovedByUser(config, id))
        assertNull(config.deletedModelIds[id])
    }

    @Test
    fun restoreIsANoOpWhenEverythingIsAlreadyThere() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        assertNull(FreeLaneBootstrap.restore(config, zen))
    }

    @Test
    fun restoreDoesNotDuplicateRowsOrInstances() {
        val config = ProviderConfig()
        assertEquals(zen, FreeLaneBootstrap.restore(config, zen))
        assertNull(FreeLaneBootstrap.restore(config, zen))
        assertEquals(1, config.instances.count { it.id == id })
    }

    @Test
    fun restoreOnlyRevivesTheLaneItWasAskedAbout() {
        // A global "restore everything" would undo deletions the user made on
        // purpose on the other lane. The channel is per lane by design.
        val config = ProviderConfig()
        InstanceRemoval.record(config, id)
        InstanceRemoval.record(config, BuiltinFreeLanes.KILO.instanceId)

        assertEquals(zen, FreeLaneBootstrap.restore(config, zen))
        assertTrue(id in config.instanceIds())
        assertTrue(
            "the other lane stays deleted",
            InstanceRemoval.wasRemovedByUser(config, BuiltinFreeLanes.KILO.instanceId),
        )
        assertFalse(BuiltinFreeLanes.KILO.instanceId in config.instanceIds())
    }

    // ---- default slot repair ------------------------------------------------

    @Test
    fun repairDefaultSlotFillsOnlyASlotThisMigrationEmptied() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        config.modelEntries.add(entry("deepseek-v4.1-flash-free"))

        assertTrue(FreeLaneBootstrap.repairDefaultSlot(config, emptiedByMigration = true))
        assertEquals("entry:deepseek-v4.1-flash-free-uuid", config.defaultPrimaryGroupId)
    }

    @Test
    fun repairDefaultSlotLeavesASlotTheUserDeliberatelyLeftEmpty() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        config.modelEntries.add(entry("deepseek-v4.1-flash-free"))

        assertFalse(FreeLaneBootstrap.repairDefaultSlot(config, emptiedByMigration = false))
        assertNull(config.defaultPrimaryGroupId)
    }

    @Test
    fun repairDefaultSlotNeverOverwritesAWorkingSlot() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        config.modelEntries.add(entry("deepseek-v4.1-flash-free"))
        config.defaultPrimaryGroupId = "my-existing-group"

        assertFalse(FreeLaneBootstrap.repairDefaultSlot(config, emptiedByMigration = true))
        assertEquals("my-existing-group", config.defaultPrimaryGroupId)
    }

    @Test
    fun repairDefaultSlotWithoutAnyVisibleRowChangesNothing() {
        val config = ProviderConfig()
        config.instances.add(builtin())
        assertFalse(FreeLaneBootstrap.repairDefaultSlot(config, emptiedByMigration = true))
        assertNull(config.defaultPrimaryGroupId)
    }

    @Test
    fun repairDefaultSlotFallsThroughToTheNextLaneWhenTheFirstHasNoVisibleRow() {
        // The pointer is "a usable free row", not "a Zen row": a device whose
        // Zen catalogue came back empty but whose second lane is populated must
        // still get a working default instead of an empty composer.
        val config = ProviderConfig()
        config.instances.add(builtin())
        config.modelEntries.add(
            ModelEntry(
                providerInstanceId = BuiltinFreeLanes.KILO.instanceId,
                baseModel = LLMModel(id = "kilo-something", displayName = "kilo-something", provider = "Kilo"),
                uuid = "kilo-row",
            ),
        )

        assertTrue(FreeLaneBootstrap.repairDefaultSlot(config, emptiedByMigration = true))
        assertEquals("entry:kilo-row", config.defaultPrimaryGroupId)
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

        FreeLaneBootstrap.forgetInstance(config, id)

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

        FreeLaneBootstrap.forgetInstance(config, "unrelated")

        assertNull(config.deletedModelIds["unrelated"])
        assertFalse("not our seed, so no re-seed tombstone", InstanceRemoval.wasRemovedByUser(config, "unrelated"))
    }

    @Test
    fun everyBuiltinLaneIdIsRecognisedAsASeed() {
        for (builtin in BuiltinFreeLanes.ALL) {
            assertTrue(
                "${builtin.instanceId} must be recognised so its deletion is remembered",
                FreeLaneBootstrap.isBuiltinFreeInstanceId(builtin.instanceId),
            )
            assertEquals(builtin, BuiltinFreeLanes.byInstanceId(builtin.instanceId))
        }
        assertFalse(FreeLaneBootstrap.isBuiltinFreeInstanceId("unrelated"))
        assertEquals(KiloFreeLane.DISPLAY_NAME, BuiltinFreeLanes.KILO.label)
        assertEquals("", BuiltinFreeLanes.KILO.apiKey)
    }
}
