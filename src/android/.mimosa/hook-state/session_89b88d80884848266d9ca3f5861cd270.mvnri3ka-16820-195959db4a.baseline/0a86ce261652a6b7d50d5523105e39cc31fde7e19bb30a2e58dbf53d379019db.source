package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONArray
import org.json.JSONObject

// MARK: - Codex image generation (gpt-image-2)

/**
 * [T-codex-gpt-image2-oauth-android] Build the Codex image_generation
 * request body. The wire model is gpt-5.5 (the Codex backend invokes the
 * underlying gpt-image-2 via the built-in image_generation tool); the user
 * turn is the fixed "Use the image generation tool to create: <prompt>"
 * instruction. The <prompt> is the latest user text — plain string content
 * or the concatenated text parts of the last user message.
 */
internal fun OpenAIProvider.buildCodexImageBody(messages: List<LLMMessage>): JSONObject {
    val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER }
    val prompt = lastUser?.let { m ->
        m.content.takeIf { it.isNotBlank() }
            ?: m.contentParts.filterIsInstance<AgentContentPart.Text>()
                .joinToString(" ") { it.text }.trim()
    }.orEmpty()
    return JSONObject().apply {
        put("model", "gpt-5.5")
        put("instructions", "You are a helpful assistant. Use tools when available.")
        put("input", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", "Use the image generation tool to create: $prompt")
        }))
        put("store", false)
        put("tools", JSONArray().put(JSONObject().put("type", "image_generation")))
        put("reasoning", JSONObject().put("effort", "low"))
        put("include", JSONArray())
        put("tool_choice", "auto")
        put("parallel_tool_calls", true)
        put("stream", true)
    }
}

/**
 * [T-android-codex-image-stream-parse-fix] Detect an image's MIME type from
 * its magic bytes. Mirrors iOS `detectImageMime`. gpt-image-2 can return
 * PNG, JPEG, or WebP, so the previous hardcoded "image/png" mislabeled
 * non-PNG output. Falls back to image/png when too short / unrecognized.
 */
internal fun OpenAIProvider.detectImageMime(data: ByteArray): String {
    if (data.size < 4) return "image/png"
    val b = data.map { it.toInt() and 0xFF }
    return when {
        b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "image/png"
        b[0] == 0xFF && b[1] == 0xD8 -> "image/jpeg"
        b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 -> "image/webp" // RIFF (WebP)
        b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 -> "image/gif"
        else -> "image/png"
    }
}
