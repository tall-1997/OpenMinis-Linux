package com.openminis.app.provider.api

/** Registry boundary for dependency injection; concrete construction stays in ProviderFactory. */
interface ProviderRegistry {
    fun register(providerId: String, provider: ModelProvider)
    fun unregister(providerId: String)
    fun get(providerId: String): ModelProvider?
    fun clear()
}

class InMemoryProviderRegistry : ProviderRegistry {
    private val providers = java.util.concurrent.ConcurrentHashMap<String, ModelProvider>()
    override fun register(providerId: String, provider: ModelProvider) { providers[providerId] = provider }
    override fun unregister(providerId: String) { providers.remove(providerId) }
    override fun get(providerId: String): ModelProvider? = providers[providerId]
    override fun clear() { providers.clear() }
}
