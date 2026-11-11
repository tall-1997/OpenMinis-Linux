package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.isImageOutput
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.tools.ProductMediaTools
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

internal data class SavedGeneratedImage(
    val relPath: String,
    val linuxPath: String,
    val mimeType: String,
)

internal suspend fun ChatViewModel.executeGenerateImageTool(
    argsJson: String,
    current: LLMProvider?,
    activeSessionId: String,
): ToolExecutionResult {
    val prompt = try {
        JSONObject(argsJson).optString("prompt", "").trim()
    } catch (_: Exception) {
        ""
    }
    if (prompt.isEmpty()) {
        return ToolExecutionResult("prompt is required", false, toolTitle = ProductMediaTools.GENERATE_IMAGE)
    }
    val provider = resolveImageProvider(current)
        ?: return ProductMediaTools.notConfigured(
            ProductMediaTools.GENERATE_IMAGE,
            "Add a configured image-output model (OpenAI-compatible, Gemini, or similar).", 
        )
    return try {
        val response = provider.generateImage(prompt)
        val att = response.mediaAttachments.firstOrNull {
            it.type == LLMMediaAttachment.MediaType.IMAGE && it.data.isNotEmpty()
        } ?: return ToolExecutionResult(
            response.text.ifBlank { "No image returned" },
            false,
            toolTitle = ProductMediaTools.GENERATE_IMAGE,
        )
        val sid = activeSessionId.ifEmpty { realSessionId.ifEmpty { sessionId } }
        val saved = persistGeneratedImage(sid, att)
        val md = "![image](minis://attachments/${saved.relPath})"
        ToolExecutionResult(
            buildString {
                append(md)
                append("\nSaved to ")
                append(saved.linuxPath)
                if (response.text.isNotBlank()) {
                    append("\n")
                    append(response.text)
                }
            },
            true,
            toolTitle = ProductMediaTools.GENERATE_IMAGE,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolExecutionResult(
            "Image generation failed: ${e.message ?: e.javaClass.simpleName}",
            false,
            toolTitle = ProductMediaTools.GENERATE_IMAGE,
        )
    }
}

internal fun ChatViewModel.persistGeneratedImage(
    sessionId: String,
    attachment: LLMMediaAttachment,
): SavedGeneratedImage {
    val extension = when (attachment.mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }
    val name = "image-${System.currentTimeMillis()}.$extension"
    val rel = "generated/$name"
    val dir = File(
        com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "attachments"),
        "generated",
    )
    dir.mkdirs()
    File(dir, name).writeBytes(attachment.data)
    return SavedGeneratedImage(rel, "/var/minis/attachments/$rel", attachment.mimeType)
}

internal fun ChatViewModel.resolveImageProvider(current: LLMProvider?): LLMProvider? {
    if (current?.model?.isImageOutput == true) return current
    val cfg = providerRepository.config.value
    for (entry in cfg.modelEntries) {
        if (entry.isHidden || !entry.model.isImageOutput) continue
        val inst = cfg.instances.find { it.id == entry.providerInstanceId } ?: continue
        val key = providerRepository.loadApiKey(inst.id) ?: ""
        val created = ProviderFactory.create(inst, key, entry.model, context)
        return created
    }
    return null
}

/**
 * [T-codex-gpt-image2 -> wired] The chat agent loop's MediaAttachment chunk:
 * persist the model-generated image to the session attachments store (the
 * same path the generate_image tool uses) and return the inline-image
 * Markdown link for the turn text — the existing chat renderer shows it. The
 * raw bytes still ride LLMResponse.mediaAttachments for the minis-model-use
 * CLI path; returning the link does not strip them. Null when the attachment
 * is not a non-empty image or persistence failed.
 */
internal fun ChatViewModel.generatedImageMarkdown(att: LLMMediaAttachment): String? {
    if (att.type != LLMMediaAttachment.MediaType.IMAGE || att.data.isEmpty()) return null
    return runCatching {
        val sid = activeSessionId.ifEmpty { realSessionId.ifEmpty { sessionId } }
        val saved = persistGeneratedImage(sid, att)
        "![image](minis://attachments/" + saved.relPath + ")"
    }.getOrNull()
}
