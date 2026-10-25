package com.openminis.app.data.model

/**
 * Image-output capability helpers shared by model pickers and the chat tool.
 * Catalogs use both `image` and OpenAI-style `image_output` spellings; model
 * internals normalize both to the bare modality name.
 *
 * When the catalog is silent, [looksLikeImageGenerator] infers dedicated
 * image models from id/name (Seedream, CogView, Hunyuan Image, …) so
 * `generate_image` can select them the same way [isVideoOutput] does.
 */
val LLMModel.isImageOutput: Boolean
    get() {
        val out = outputModalities.normalizeModalities()
        if (out != null) return "image" in out
        if (VideoModality.looksLikeVideoGenerator(id, displayName)) return false
        return looksLikeImageGenerator
    }

/**
 * Dedicated image-generation id/name tokens. Intentionally specific so
 * vision-language chat models (`gpt-4o`, `qwen-vl`, `*-vision`) are not
 * treated as generators.
 */
private val imageGeneratorIdPatterns = listOf(
    "gpt-image",
    "dall-e",
    "dall_e",
    "dalle-",
    "dalle_",
    "seedream",
    "cogview",
    "hunyuan-image",
    "hunyuan_image",
    "glm-image",
    "qwen-image",
    "wanx-v1",
    "wanx-v2",
    "wan2.1-t2i",
    "wan2.2-t2i",
    "wan2-t2i",
    "wanx-t2i",
    "flux-",
    "imagen-",
    "ideogram",
    "recraft",
    "kolors",
    "stable-diffusion",
    "sdxl",
    "sd3",
    "image-01",
)

val LLMModel.looksLikeImageGenerator: Boolean
    get() {
        val hay = "$id $displayName".lowercase()
        return imageGeneratorIdPatterns.any { hay.contains(it) }
    }

/**
 * Fill `outputModalities=["image"]` only when the catalog left outputs empty
 * and the id/name looks like a dedicated generator. Explicit catalog values
 * (including multimodal `["text","image"]`) are never overwritten.
 */
fun LLMModel.withInferredImageModality(): LLMModel {
    if (outputModalities.normalizeModalities() != null) return this
    if (VideoModality.looksLikeVideoGenerator(id, displayName)) return this
    if (!looksLikeImageGenerator) return this
    return copy(outputModalities = listOf("image"))
}
