package com.openminis.app.data

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.mayImportLegacyProviderMirror
import com.openminis.app.data.repository.shouldRepairProviderMirror
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialCapabilityTest {
    @Test
    fun `capability table accepts only implemented provider credential pairs`() {
        val oauthTypes = setOf(
            ProviderType.anthropic,
            ProviderType.openAI,
            ProviderType.openRouter,
            ProviderType.xAI,
            ProviderType.kimiCode,
            ProviderType.openAIResponses,
        )
        for (type in ProviderType.values()) {
            assertTrue(ProviderRepository.supportsCredential(type, ProviderCredential.apiKey))
            assertEquals(type in oauthTypes, ProviderRepository.supportsCredential(type, ProviderCredential.oauth))
        }
    }

    @Test
    fun `legacy JSON seeds only a confirmed empty database`() {
        assertTrue(mayImportLegacyProviderMirror(dbReadFailed = false, dbInstanceCount = 0))
        assertFalse(mayImportLegacyProviderMirror(dbReadFailed = true, dbInstanceCount = 0))
        assertFalse(mayImportLegacyProviderMirror(dbReadFailed = false, dbInstanceCount = 3))
    }

    @Test
    fun `hash mismatch requests DB mirror repair but never JSON import`() {
        assertFalse(shouldRepairProviderMirror(liveHash = "same", dbHash = "same"))
        assertTrue(shouldRepairProviderMirror(liveHash = "stale-json", dbHash = "new-db"))
        assertTrue(shouldRepairProviderMirror(liveHash = null, dbHash = "new-db"))
    }

    @Test
    fun `failed legacy migration is not accepted as a loaded config`() = runBlocking {
        try {
            com.openminis.app.data.repository.persistLegacyProviderMirror<Int> {
                error("simulated Room write failure")
            }
            throw AssertionError("migration failure must propagate")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("could not be persisted"))
            assertEquals("simulated Room write failure", e.cause?.message)
        }
    }

    @Test
    fun `capability list is stable for debug schema`() {
        assertEquals(
            listOf(ProviderCredential.apiKey, ProviderCredential.oauth),
            ProviderRepository.supportedCredentials(ProviderType.openRouter),
        )
        assertEquals(
            listOf(ProviderCredential.apiKey),
            ProviderRepository.supportedCredentials(ProviderType.unsupported),
        )
        assertFalse(ProviderRepository.supportsCredential(ProviderType.unsupported, ProviderCredential.oauth))
    }
}
