package com.openminis.app.data.model

/** Measured input for the request that produced this assistant row, bound to the snapshot's context route. */
data class ContextUsageAnchor(
    val modelSnapshot: ModelAttributionSnapshot,
    val markerId: String?,
    val prefixLength: Int,
    val prefixFingerprint: String,
    val inputTokens: Int,
)
