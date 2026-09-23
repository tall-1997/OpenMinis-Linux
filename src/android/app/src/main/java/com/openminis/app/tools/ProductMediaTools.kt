package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Product-facing media tools. describe_image aliases read_image.
 * generate_image / generate_video / transcribe_audio / translate_text are
 * executed from ChatViewModel against the active provider when possible.
 *
 * Names match XINCODE-Public (GPL-3.0-or-later).
 */
object ProductMediaTools {
    const val GENERATE_IMAGE = "generate_image"
    const val GENERATE_VIDEO = "generate_video"
    const val DESCRIBE_IMAGE = "describe_image"
    const val TRANSCRIBE_AUDIO = "transcribe_audio"
    const val TRANSLATE_TEXT = "translate_text"

    fun generateImageDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = GENERATE_IMAGE,
        description = "Generate an image from a text prompt using the configured image-output model. Returns markdown that displays in chat.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "prompt" to AgentToolParam("string", "Image prompt."),
        ),
        required = listOf("tool_title", "prompt"),
        propertyOrdering = listOf("tool_title", "prompt"),
    )

    fun generateVideoDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = GENERATE_VIDEO,
        description = "Generate a short video from a text prompt using a configured video model (Sora, Veo, Kling, or an OpenAI-compatible /videos endpoint). Returns markdown that plays in chat. Some gateways require mode; if omitted and the provider says mode is required, std is sent once.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "prompt" to AgentToolParam("string", "Video prompt. Describe the shot, motion, and style."),
            "mode" to AgentToolParam("string", "Optional provider mode, such as std or pro. Omit to let the provider default; std is retried only when the provider says mode is required."),
        ),
        required = listOf("tool_title", "prompt"),
        propertyOrdering = listOf("tool_title", "prompt", "mode"),
    )

    fun describeImageDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = DESCRIBE_IMAGE,
        description = "Describe an image at a Linux path. Same as read_image.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "path" to AgentToolParam("string", "Linux path to the image."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path"),
    )

    fun transcribeAudioDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TRANSCRIBE_AUDIO,
        description = "Transcribe an audio file at a Linux path using the configured speech model.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "path" to AgentToolParam("string", "Linux path to audio."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path"),
    )

    fun translateTextDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = TRANSLATE_TEXT,
        description = "Translate text into a target language using the current chat model.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "text" to AgentToolParam("string", "Source text."),
            "target_lang" to AgentToolParam("string", "Target language, e.g. zh, en, ja."),
        ),
        required = listOf("tool_title", "text", "target_lang"),
        propertyOrdering = listOf("tool_title", "text", "target_lang"),
    )

    fun notConfigured(name: String, extra: String = ""): ToolExecutionResult =
        ToolExecutionResult(
            "$name is not configured on this provider. $extra".trim(),
            false,
            toolTitle = name,
        )
}
