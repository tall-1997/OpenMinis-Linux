package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.hasImageInput

/**
 * When a bound model group is asked to handle a turn that needs a modality
 * the currently selected member lacks, prefer a credentialed member that
 * actually supports it. Falls back to the original pool when nobody in the
 * group has the capability (Vision Group / other fallbacks still apply).
 */
enum class ModelCapability {
    IMAGE_INPUT,
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    ;

    fun isSupportedBy(model: LLMModel): Boolean = when (this) {
        IMAGE_INPUT -> model.hasImageInput
        AUDIO_INPUT -> model.hasAudioInput
        AUDIO_OUTPUT -> model.hasAudioOutput
    }
}

data class RoutingDecision(
    val needed: Set<ModelCapability>,
    val members: List<ModelEntry>,
    val reason: String?,
)

object CapabilityRouter {

    fun neededForImages(hasImage: Boolean): Set<ModelCapability> =
        if (hasImage) setOf(ModelCapability.IMAGE_INPUT) else emptySet()

    fun neededForTask(userText: String?, hasImage: Boolean): Set<ModelCapability> {
        val out = mutableSetOf<ModelCapability>()
        if (hasImage) out += ModelCapability.IMAGE_INPUT
        val t = userText.orEmpty()
        if (looksLikeImageAsk(t)) out += ModelCapability.IMAGE_INPUT
        if (looksLikeAudioAsk(t)) out += ModelCapability.AUDIO_INPUT
        return out
    }

    fun decide(
        available: List<ModelEntry>,
        needed: Set<ModelCapability>,
        selectedId: String? = null,
    ): RoutingDecision {
        val members = pickMembers(available, needed)
        val capable = available.filter { entry -> needed.all { it.isSupportedBy(entry.model) } }
        val reason = when {
            needed.isEmpty() -> null
            capable.isEmpty() ->
                "no group member has ${needed.joinToString()} — keeping current pool"
            selectedId != null && members.none { it.id == selectedId } &&
                available.any { it.id == selectedId } ->
                "routed away from $selectedId: need ${needed.joinToString()}"
            needed.isNotEmpty() -> "need ${needed.joinToString()}"
            else -> null
        }
        return RoutingDecision(needed, members, reason)
    }

    fun pickMembers(
        available: List<ModelEntry>,
        needed: Set<ModelCapability>,
    ): List<ModelEntry> {
        if (needed.isEmpty() || available.isEmpty()) return available
        val hit = available.filter { entry ->
            needed.all { it.isSupportedBy(entry.model) }
        }
        return hit.ifEmpty { available }
    }

    private fun looksLikeImageAsk(t: String): Boolean {
        val s = t.lowercase()
        return listOf(
            "看图", "识图", "这张图", "截图", "screenshot", "this image",
            "look at this pic", "ocr this",
        ).any { it in s }
    }

    private fun looksLikeAudioAsk(t: String): Boolean {
        val s = t.lowercase()
        return listOf("听这段", "语音转写", "transcribe this audio", "this recording").any { it in s }
    }
}
