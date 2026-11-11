package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OpenMinis#306] Cold-start contract for the built-in model catch-up.
 *
 * The production bug: `addMissingBuiltInModels` called
 * `repo.ensureConfigLoaded()` and IGNORED the returned false. During the
 * Main-thread cold-start window the config is still the empty placeholder, so
 * the following `workingCopy()` + `saveConfig()` persisted an empty config and
 * `ProviderConfigDao.replaceDiff` deleted every stored provider instance.
 *
 * `addMissingBuiltInModelsGuarded` takes the repository operations as
 * parameters, so a fake repository can reproduce exactly that window:
 * `ensureConfigLoaded = { false }`.
 */
class ModelCatalogCacheColdStartTest {

    private fun openAiInstance() = ProviderInstance(
        id = "instance-1",
        label = "OpenAI",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )

    @Test
    fun `main thread with unloaded config must not copy or save the placeholder`() {
        val instance = openAiInstance()
        // The placeholder a cold start publishes: the instance may be absent or
        // partial; the important part is that it is NOT the persisted config.
        val placeholder = ProviderConfig()
        var workingCopyCalls = 0
        var saveCalls = 0

        val added = addMissingBuiltInModelsGuarded(
            instance = instance,
            configLock = Any(),
            ensureConfigLoaded = { false }, // main-thread refusal (Wave3-fix-1)
            isThirdPartyOpenAICompat = { false },
            workingCopy = {
                workingCopyCalls++
                placeholder
            },
            saveConfig = { saveCalls++ },
        )

        assertFalse("must report nothing was seeded", added)
        assertEquals("must not even read the empty placeholder", 0, workingCopyCalls)
        assertEquals("must not persist anything", 0, saveCalls)
        assertTrue(placeholder.instances.isEmpty())
        assertTrue(placeholder.modelEntries.isEmpty())
    }

    @Test
    fun `loaded config still seeds the built-in models once`() {
        val instance = openAiInstance()
        val config = ProviderConfig(instances = mutableListOf(instance))
        var saveCalls = 0

        val added = addMissingBuiltInModelsGuarded(
            instance = instance,
            configLock = Any(),
            ensureConfigLoaded = { true },
            isThirdPartyOpenAICompat = { false },
            workingCopy = { config },
            saveConfig = { saveCalls++ },
        )

        assertTrue(added)
        assertEquals(1, saveCalls)
        assertEquals(
            instance.providerType.builtInModels.size,
            config.modelEntries.count { it.providerInstanceId == instance.id },
        )
    }

    @Test
    fun `third-party openai-compat instance is never seeded`() {
        val instance = openAiInstance()
        val config = ProviderConfig(instances = mutableListOf(instance))
        var ensureCalls = 0
        var saveCalls = 0

        val added = addMissingBuiltInModelsGuarded(
            instance = instance,
            configLock = Any(),
            ensureConfigLoaded = {
                ensureCalls++
                true
            },
            isThirdPartyOpenAICompat = { true },
            workingCopy = { config },
            saveConfig = { saveCalls++ },
        )

        assertFalse(added)
        assertEquals(0, ensureCalls)
        assertEquals(0, saveCalls)
        assertTrue(config.modelEntries.isEmpty())
    }
}
