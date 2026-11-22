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
        // The real load path: publishCopy both clones and syncs. If it is ever
        // removed or stops cloning, this fails (the old test copied the
        // implementation by hand and could never catch that).
        val synced = AutoModelGroups.publishCopy(loaded)
        assertTrue("an implicit group must exist after a cold load", synced.modelGroups.any { AutoModelGroups.isAutoId(it.id) })
        assertTrue("the on-disk object is left untouched", loaded.modelGroups.none { AutoModelGroups.isAutoId(it.id) })
    }

    @Test
    fun theImplicitGroupContainsEveryUnhiddenModelOfTheInstance() {
        val cfg = config()
        AutoModelGroups.syncInPlace(cfg)
        val group = cfg.modelGroups.single { AutoModelGroups.isAutoId(it.id) }
        assertEquals("auto-inst_a", group.id)
        // Members are the model entries' real ids (uuids), in config order.
        assertEquals(cfg.modelEntries.map { it.id }, group.memberEntryIds)
    }

    @Test
    fun anInstanceWithOneModelGetsNoImplicitGroup() {
        val single = config().copy(modelEntries = config().modelEntries.take(1).toMutableList())
        assertFalse("a single-model instance cannot form a fallback group", AutoModelGroups.syncInPlace(single))
        assertTrue(single.modelGroups.none { AutoModelGroups.isAutoId(it.id) })
    }
}
