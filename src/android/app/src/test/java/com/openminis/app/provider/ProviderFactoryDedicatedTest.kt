package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-endpoint-override-isolation] `model_use` rewrites
 * `OpenAIProvider.absoluteEndpointOverride` on the instance it calls. When that
 * instance came from the memo, the override stayed on the provider every later
 * chat request shared — a model-use turn could redirect the next chat request to
 * e.g. `/images/generations` until the process restarted.
 */
class ProviderFactoryDedicatedTest {

    private fun instance(id: String) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        createdAt = 1L,
    )

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "openai")

    @After
    fun tearDown() {
        ProviderFactory.invalidateAll()
    }

    @Test
    fun aDedicatedProviderIsNeverTheSharedInstance() {
        val inst = instance("inst-dedicated")
        val first = ProviderFactory.create(inst, "key", model("m1"))
        val dedicated = ProviderFactory.createDedicated(inst, "key", model("m1"))
        assertNotSame("a mutable provider must not be handed out shared", first, dedicated)
        // The memo still serves the same shared instance to plain callers.
        assertSame(ProviderFactory.create(inst, "key", model("m1")), first)
        // And mutating the dedicated copy does not touch the shared one.
        (dedicated as OpenAIProvider).absoluteEndpointOverride = "/images/generations"
        assertNull((first as OpenAIProvider).absoluteEndpointOverride)
    }
}
