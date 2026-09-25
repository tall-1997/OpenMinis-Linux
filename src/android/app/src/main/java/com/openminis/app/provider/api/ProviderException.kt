package com.openminis.app.provider.api

/** Normalized provider failure envelope; legacy LLMError remains the wire/runtime exception. */
class ProviderException(
    message: String,
    val code: String,
    val retryable: Boolean,
    val providerId: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
