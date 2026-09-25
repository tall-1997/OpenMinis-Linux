package com.openminis.app.provider.api

data class ProviderCapabilities(
    val streaming: Boolean = true,
    val images: Boolean = true,
    val tools: Boolean = true,
    val thinking: Boolean = true,
    val models: Boolean = true,
    val video: Boolean = false,
)
