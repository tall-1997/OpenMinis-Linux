package com.openminis.app.data.model

/**
 * Dedicated video-generation models (Sora / Veo / Kling / …) are not chat
 * completions. When the catalog left [LLMModel.outputModalities] empty we
 * still need to recognize them so chat can call `/videos` instead of the
 * agent loop.
 */
object VideoModality {
    private val idPatterns = listOf(
        "sora",
        "veo",
        "kling",
        "runway-gen",
        "runwaygen",
        "luma-dream",
        "dream-machine",
        "minimax-video",
        "hailuo",
        "vidu",
        "wanx-video",
        "wan-video",
        "video-gen",
        "video_gen",
        "video-generation",
        "cogvideox",
        "cogvideo",
        "seedance",
        "doubao-video",
        "hunyuan-video",
        "wan2.1-t2v",
        "wan2.2-t2v",
        "wanx2.1-t2v",
    )

    fun looksLikeVideoGenerator(id: String, displayName: String = ""): Boolean {
        val hay = "$id $displayName".lowercase()
        return idPatterns.any { hay.contains(it) }
    }
}

val LLMModel.isVideoOutput: Boolean
    get() {
        val out = outputModalities.normalizeModalities()
        if (out != null) return "video" in out
        return VideoModality.looksLikeVideoGenerator(id, displayName)
    }

/** Pure generator: video out and no text out (null outputs still count as text). */
val LLMModel.isPureVideoGenerator: Boolean
    get() {
        val out = outputModalities.normalizeModalities()
        if (out != null) return "video" in out && "text" !in out
        return VideoModality.looksLikeVideoGenerator(id, displayName)
    }

fun LLMModel.withInferredVideoModality(): LLMModel {
    if (outputModalities.normalizeModalities() != null) return this
    if (!VideoModality.looksLikeVideoGenerator(id, displayName)) return this
    return copy(
        inputModalities = inputModalities ?: listOf("text"),
        outputModalities = listOf("video"),
    )
}
