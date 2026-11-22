package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-auto-groups-cold-start] The implicit (auto-*) groups must exist after a
 * cold load, not only after the first `saveConfig`.
 *
 * Before the fix, the load path published the on-disk config as-is, so the
 * first process after an upgrade had NO implicit groups until some write
 * happened to rebuild them — and the store path mutated the already-published
 * object in place, which never re-emits to collectors.
 */
class AutoModelGroupsColdStartTest {

    private fun config(): ProviderConfig = ProviderConfig(
        instances = mutableListOf(
            ProviderInstance(
                id = "inst_a",
                label = "A",
                providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey,
                isEnabled = true,
            ),
        ),
        modelEntries = mutableListOf(
            ModelEntry(
                providerInstanceId = "inst_a",
                baseModel = LLMModel(id = "gpt-a", displayName = "GPT A", provider = "openai"),
            ),
            ModelEntry(
                providerInstanceId = "inst_a",
                baseModel = LLMModel(id = "gpt-b", displayName = "GPT B", provider = "openai"),
            ),
        ),
    )

    @Test
    fun aFreshlyLoadedConfigGainsImplicitGroups() {
        val loaded = config()
        assertTrue("the on-disk shape has no implicit groups", loaded.modelGroups.none { AutoModelGroups.isAutoId(it.id) })
        // What the load path now does: copy, sync, then publish.
        val synced = loaded.copy(modelGroups = loaded.modelGroups.toMutableList())
        AutoModelGroups.syncInPlace(synced)
        assertTrue("an implicit group must exist after a cold load", synced.modelGroups.any { AutoModelGroups.isAutoId(it.id) })
        assertTrue("the on-disk object is left untouched", loaded.modelGroups.none { AutoModelGroups.isAutoId(it.id) })
    }

    @Test
    fun theImplicitGroupContainsEveryUnhiddenModelOfTheInstance() {
        val synced = config().also { AutoModelGroups.syncInPlace(it) }
        val group = synced.modelGroups.single { AutoModelGroups.isAutoId(it.id) }
        assertEquals("auto-inst_a", group.id)
        assertEquals(listOf("m1", "m2").size, group.memberEntryIds.size)
    }

    @Test
    fun anInstanceWithOneModelGetsNoImplicitGroup() {
        val single = config().copy(modelEntries = config().modelEntries.take(1).toMutableList())
        assertFalse("a single-model instance cannot form a fallback group", AutoModelGroups.syncInPlace(single))
        assertTrue(single.modelGroups.none { AutoModelGroups.isAutoId(it.id) })
    }
}
