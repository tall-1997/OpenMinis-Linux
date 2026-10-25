package com.openminis.app.i18n

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationRunnerTest {
    @Test
    fun `saved usable entry wins over the first model`() {
        val picked = resolve("b")
        assertEquals("b", picked?.id)
    }

    @Test
    fun `no saved entry uses the first enabled model`() {
        assertEquals("a", resolve(null)?.id)
    }

    @Test
    fun `stale saved entry does not silently switch models`() {
        assertNull(resolve("gone"))
    }

    @Test
    fun `hidden and disabled entries are not usable`() {
        assertEquals("c", resolve(null, hideFirst = true, disableFirst = true)?.id)
        assertNull(resolve("a", hideFirst = true))
        assertNull(resolve("a", disableFirst = true))
    }

    private fun resolve(
        savedId: String?,
        hideFirst: Boolean = false,
        disableFirst: Boolean = false,
    ): ModelEntry? = TranslationRunner.resolveEntry(
        entries = listOf(
            entry("a", "p1", hidden = hideFirst),
            entry("b", "p1"),
            entry("c", "p2"),
        ),
        instances = listOf(
            inst("p1", enabled = !disableFirst),
            inst("p2"),
        ),
        savedId = savedId,
    )

    private fun entry(id: String, instanceId: String, hidden: Boolean = false) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(id, id, "test"),
        isHidden = hidden,
        uuid = id,
    )

    private fun inst(id: String, enabled: Boolean = true) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = enabled,
    )
}
