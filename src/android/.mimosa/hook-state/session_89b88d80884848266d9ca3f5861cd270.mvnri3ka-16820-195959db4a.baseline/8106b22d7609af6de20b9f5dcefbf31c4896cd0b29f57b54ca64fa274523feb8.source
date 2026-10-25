package com.openminis.app.hostcapability.api

interface CapabilityRegistry {
    fun register(handler: HostCapabilityHandler)
    fun unregister(name: String)
    fun get(name: String): HostCapabilityHandler?
    fun names(): Set<String>
}

class InMemoryCapabilityRegistry : CapabilityRegistry {
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, HostCapabilityHandler>()
    override fun register(handler: HostCapabilityHandler) { handlers[handler.name] = handler }
    override fun unregister(name: String) { handlers.remove(name) }
    override fun get(name: String): HostCapabilityHandler? = handlers[name]
    override fun names(): Set<String> = handlers.keys.toSet()
}
