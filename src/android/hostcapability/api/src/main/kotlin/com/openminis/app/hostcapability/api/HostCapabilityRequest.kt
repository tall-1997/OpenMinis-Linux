package com.openminis.app.hostcapability.api

data class HostCapabilityRequest(
    val capability: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String = "",
    val sessionId: String? = null,
    val timeoutMs: Long? = null,
)
