package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.BuiltinFreeLanes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retired builtin lanes must not survive an upgrade as empty provider rows.
 *
 * Dropping a lane from the build does not remove the instance it seeded — the
 * row lives on in `provider_config.json` pointing at a loopback port nothing
 * binds, so every refresh fails and the model picker keeps offering models that
 * cannot answer. [BuiltinLaneRetirement] is the single decision point for which
 * shipped rows are dead, and [ProviderConfigStore.purgeRetiredBuiltInLanes] is
 * its only caller.
 */
class BuiltinLaneRetirementTest {

    private fun instance(id: String) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )

    @Test
    fun thePurgeListIsNotAlsoShipped() {
        // Ordering hazard guard: listing an id as retired while still seeding it
        // would delete a live row on every boot.
        val live = BuiltinFreeLanes.ALL.map { it.instanceId }.toSet()
        assertTrue(BuiltinFreeLanes.RETIRED_IDS.isNotEmpty())
        for (retired in BuiltinFreeLanes.RETIRED_IDS) {
            assertTrue("$retired is shipped and retired at once", retired !in live)
        }
    }

    @Test
    fun onlyRetiredIdsAreReturned() {
        val retired = BuiltinFreeLanes.RETIRED_IDS.first()
        val config = ProviderConfig(
            instances = mutableListOf(
                instance("user-own-relay"),
                instance(retired),
                instance(BuiltinFreeLanes.ZEN.instanceId),
            ),
        )

        assertEquals(listOf(retired), BuiltinLaneRetirement.instancesToPurge(config))
    }

    @Test
    fun userRowsAreNeverCandidates() {
        val config = ProviderConfig(
            instances = mutableListOf(instance("user-own-relay")),
        )

        assertTrue(BuiltinLaneRetirement.instancesToPurge(config).isEmpty())
    }

}
