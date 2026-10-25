package com.openminis.app.provider

import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.CancellationException
import java.util.Collections
import java.util.WeakHashMap

/**
 * One sampling gate for every chat provider. Callers pass the temperature
 * stored on the model entry that will actually serve the request. This object
 * decides whether that value is allowed on the wire.
 *
 * Default is omit. Known reasoning families omit. A rejection is remembered
 * for this process only, keyed by provider instance plus model id, and the
 * field is stripped for one retry. The value is never rewritten to 1.
 */
object SamplingPolicy {
    const val MIN = 0.0
    const val MAX = 2.0

    private val denied = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun parseUserInput(raw: String): Double? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return canonical(text.toDoubleOrNull())
    }

    fun canonical(raw: Double?): Double? {
        if (raw == null || raw.isNaN() || raw.isInfinite()) return null
        if (raw < MIN || raw > MAX) return null
        return raw
    }

    fun wire(
        instanceId: String,
        modelId: String,
        requested: Double?,
        thinkingEnabled: Boolean,
    ): Double? {
        val value = canonical(requested) ?: return null
        if (thinkingEnabled) return null
        if (rejectsFamily(modelId)) return null
        if (denied.contains(key(instanceId, modelId))) return null
        return value
    }

    fun deny(instanceId: String, modelId: String) {
        denied.add(key(instanceId, modelId))
    }

    fun isDenied(instanceId: String, modelId: String): Boolean =
        denied.contains(key(instanceId, modelId))

    /** Test-only. Process memory is otherwise never cleared. */
    internal fun clearDeniedForTests() {
        denied.clear()
    }

    fun isTemperatureRejection(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        if (chain.any { it is CancellationException && it.cause == null }) return false
        val text = chain.joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }
            .lowercase()
        if (!text.contains("temperature")) return false
        return REJECTION_MARKERS.any { text.contains(it) }
    }

    fun rejectsFamily(modelId: String): Boolean {
        if (AnthropicProvider.modelRejectsTemperature(modelId)) return true
        val id = modelId.lowercase()
        if (id.contains("gpt-5")) return true
        if (id.contains("reasoner") || id.contains("deepseek-r1") || id.contains("-r1")) return true
        if (id.contains("qwq")) return true
        return O_SERIES.containsMatchIn(id)
    }

    private fun key(instanceId: String, modelId: String): String =
        "${instanceId.trim()}\u0000${modelId.trim()}"

    private val O_SERIES = Regex("""(?:^|[^a-z0-9])o[134](?:[^a-z0-9]|$)""")
    private val REJECTION_MARKERS = listOf(
        "unsupported",
        "not supported",
        "deprecated",
        "rejected",
        "invalid",
        "unknown",
        "extra_forbidden",
        "unrecognized",
        "only temperature",
        "must be 1",
        "not allowed",
        "cannot",
        "400",
    )
}

/** Binds a live provider to the instance that created it. Weak so tests and discarded providers do not leak. */
object SamplingIdentity {
    private val ids: MutableMap<LLMProvider, String> =
        Collections.synchronizedMap(WeakHashMap())

    fun bind(provider: LLMProvider, instanceId: String) {
        if (instanceId.isNotBlank()) ids[provider] = instanceId
    }

    fun of(provider: LLMProvider): String = ids[provider].orEmpty()
}
