package com.openminis.app.hostcapability.api

data class CapabilityError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: Map<String, String> = emptyMap(),
)
