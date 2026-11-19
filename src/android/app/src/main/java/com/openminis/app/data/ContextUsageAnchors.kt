package com.openminis.app.data

import com.openminis.app.data.model.ContextUsageAnchor
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.api.ChatRequest

/** Frozen before dispatch so neither failover nor a later history rewrite can change the sample. */
internal class ContextUsageAttempt internal constructor(
    internal val modelSnapshot: ModelAttributionSnapshot?,
    internal val markerId: String?,
    internal val prefixLength: Int,
    internal val prefixFingerprint: String?,
    internal val rawRequestTokens: Int,
    /** [T-anchor-persisted-fingerprint] Request-local appended bytes the measurement includes but persistence will not. */
    internal val requestLocalTokens: Int = 0,
)

/** Total for the projected request; a measured prefix is never re-scaled. */
internal data class ContextTokenEstimate(val tokens: Int, val measuredPrefixTokens: Int? = null)

internal fun captureContextUsageAttempt(
    request: ChatRequest,
    modelSnapshot: ModelAttributionSnapshot?,
    markerId: String?,
    rawRequestTokens: Int = ContextTokenMeter.rawRequestTokens(request),
    /**
     * Fingerprint over the persisted form (request-local appends stripped).
     * The projector computes it where it knows exactly what it appended; null
     * falls back to hashing the request as-is.
     */
    fingerprintOverride: String? = null,
    requestLocalTokens: Int = 0,
): ContextUsageAttempt = ContextUsageAttempt(
    modelSnapshot = modelSnapshot,
    markerId = markerId,
    prefixLength = request.messages.size,
    prefixFingerprint = fingerprintOverride ?: if (modelSnapshot?.contextUsageIdentity() != null && markerId?.isBlank() != true) {
        ContextTokenMeter.requestFingerprint(request)
    } else null,
    rawRequestTokens = rawRequestTokens,
    requestLocalTokens = requestLocalTokens,
)

/** Call only for the accepted attempt, with that attempt's input usage (not the billing ledger). */
internal fun settleContextUsageAttempt(attempt: ContextUsageAttempt, usage: LLMUsage?): ContextUsageAnchor? {
    val model = attempt.modelSnapshot ?: return null
    val fingerprint = attempt.prefixFingerprint ?: return null
    val identity = model.contextUsageIdentity() ?: return null
    val actual = reportedRequestInputTokens(usage, anthropic = identity.provider == ProviderType.anthropic) ?: return null
    TokenUsageCalibration.observe(identity.calibrationKey(), attempt.rawRequestTokens, actual)
    // The measured input includes bytes that never persist (recall reminder,
    // receipt notice, stall nudge). Store the persisted-prefix size so the next
    // request's anchor comparison is apples-to-apples.
    val prefixInput = (actual - attempt.requestLocalTokens).takeIf { it > 0 } ?: return null
    return ContextUsageAnchor(model, attempt.markerId, attempt.prefixLength, fingerprint, prefixInput)
}

/** Provider-reported occupied input, separate from the existing fresh/cache billing buckets. */
internal fun reportedRequestInputTokens(usage: LLMUsage?, anthropic: Boolean): Int? {
    if (usage == null) return null
    if (!anthropic) return usage.latestContextTokens.takeIf { it > 0 }
    val fresh = usage.inputTokens
    val read = usage.cacheReadInputTokens ?: 0
    val write = usage.cacheCreationInputTokens ?: 0
    if (fresh < 0 || read < 0 || write < 0) return null
    return (fresh.toLong() + read + write).takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
}

internal fun estimateProjectedContext(
    request: ChatRequest,
    modelSnapshot: ModelAttributionSnapshot?,
    markerId: String?,
    rawRequestTokens: Int = ContextTokenMeter.rawRequestTokens(request),
): ContextTokenEstimate {
    val messages = request.messages
    // At most one prefix hash on this hot path. Use the newest measured assistant row;
    // if the request it describes cannot be proved, fall back to estimating the whole projection.
    val index = messages.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.contextUsageAnchor != null }
    val anchor = if (index < 0) null else messages[index].contextUsageAnchor
    if (anchor == null || !anchor.isValidContextUsageAnchor() || anchor.markerId != markerId ||
        anchor.prefixLength != index || !anchor.modelSnapshot.sameContextUsageModel(modelSnapshot)
    ) return ContextTokenEstimate(calibratedContextTokens(modelSnapshot, rawRequestTokens))

    // Explicit legacy request images are attached to the last user of the full
    // projection. Do not move them to an older user just because we hash a prefix.
    val imageIndex = if (request.imageParts.isEmpty()) -1 else messages.indexOfLast { it.role == LLMMessage.Role.USER }
    val prefixImages = if (imageIndex in 0 until index) request.imageParts else emptyList()
    val fingerprint = ContextTokenMeter.requestFingerprint(
        messages.subList(0, index), request.systemPrompt, request.tools, prefixImages,
    )
    if (anchor.prefixFingerprint != fingerprint) {
        return ContextTokenEstimate(calibratedContextTokens(modelSnapshot, rawRequestTokens))
    }

    // Input usage was measured BEFORE generating the assistant carrying it. Include
    // that assistant (and any later messages) once, but not the paid-for prefix/system/tools.
    val suffixImages = if (imageIndex >= index) request.imageParts else emptyList()
    val rawSuffix = ContextTokenMeter.rawRequestTokens(messages.subList(index, messages.size), imageParts = suffixImages)
    val suffix = calibratedContextTokens(modelSnapshot, rawSuffix)
    val total = (anchor.inputTokens.toLong() + suffix).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return ContextTokenEstimate(total, measuredPrefixTokens = anchor.inputTokens)
}

internal fun calibratedContextTokens(modelSnapshot: ModelAttributionSnapshot?, rawTokens: Int): Int {
    if (rawTokens <= 0) return 0
    val identity = modelSnapshot?.contextUsageIdentity() ?: return rawTokens
    return TokenUsageCalibration.estimate(identity.calibrationKey(), rawTokens)
}

internal data class ContextUsageIdentity(    val provider: ProviderType,
    val instanceId: String,
    val modelId: String,
    val routeId: String,
) {
    fun calibrationKey(): String = "context-v2:$provider:$routeId:${instanceId.length}:$instanceId:$modelId"
}

internal fun ModelAttributionSnapshot.contextUsageIdentity(): ContextUsageIdentity? {
    val provider = ProviderType.decoded(providerTypeRaw)
    val instance = providerInstanceId?.takeIf { it.isNotBlank() } ?: return null
    val route = contextRouteId?.takeIf { it.isSha256Hex() } ?: return null
    if (!provider.isUsable || modelId.isBlank()) return null
    return ContextUsageIdentity(provider, instance, modelId, route)
}

/** A display-name change does not affect route identity. Missing identity never matches. */
internal fun ModelAttributionSnapshot.sameContextUsageModel(other: ModelAttributionSnapshot?): Boolean =
    contextUsageIdentity()?.let { it == other?.contextUsageIdentity() } == true

internal fun ContextUsageAnchor.isValidContextUsageAnchor(): Boolean =
    modelSnapshot.contextUsageIdentity() != null && markerId?.isBlank() != true && prefixLength >= 0 &&
        inputTokens > 0 && prefixFingerprint.isSha256Hex()

private fun String.isSha256Hex(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
