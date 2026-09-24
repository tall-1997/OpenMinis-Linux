package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.isVideoOutput
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.tools.ProductMediaTools
import com.openminis.app.tools.ToolExecutionResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * One-shot URL of a video that just finished in this process. Historical
 * bubbles must not open the player again when the chat is reopened.
 */
internal object GeneratedVideoAutoPlay {
    var url by mutableStateOf<String?>(null)
}

internal data class SavedGeneratedVideo(
    val relPath: String,
    val linuxPath: String,
)

internal suspend fun ChatViewModel.runVideoGenerationTurn(
    provider: LLMProvider,
    prompt: String,
    activeSessionId: String,
) {
    val assistantId = java.util.UUID.randomUUID().toString()
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value + ChatMessage(
            id = assistantId,
            role = "assistant",
            content = "",
            isStreaming = true,
            isAwaitingModelResponse = true,
        )
    }
    try {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) error("视频提示词为空")
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value.map {
                if (it.id == assistantId) it.copy(content = "正在生成视频…") else it
            }
        }
        val response = provider.generateVideo(trimmed)
        val att = response.mediaAttachments.firstOrNull {
            it.type == LLMMediaAttachment.MediaType.VIDEO && it.data.isNotEmpty()
        } ?: error(response.text.ifBlank { "接口没有返回视频" })
        val saved = persistGeneratedVideo(activeSessionId, att.data)
        val caption = response.text.trim()
        val videoUrl = "minis://attachments/${saved.relPath}"
        GeneratedVideoAutoPlay.url = videoUrl
        val md = buildString {
            append("![video](").append(videoUrl).append(")")
            if (caption.isNotEmpty()) {
                append("\n\n")
                append(caption)
            }
        }
        val partsJson = "[{\"type\":\"text\",\"value\":" + escapeJson(md) + "}]"
        val persisted = chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value.map {
                if (it.id == assistantId) it.copy(
                    id = persisted.id,
                    content = md,
                    isStreaming = false,
                    isAwaitingModelResponse = false,
                ) else it
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val err = "视频生成失败: ${e.message ?: e.javaClass.simpleName}"
        val partsJson = "[{\"type\":\"text\",\"value\":" + escapeJson(err) + "}]"
        val persisted = runCatching {
            chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
        }.getOrNull()
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value.map {
                if (it.id == assistantId) it.copy(
                    id = persisted?.id ?: assistantId,
                    content = err,
                    isStreaming = false,
                    isAwaitingModelResponse = false,
                ) else it
            }
        }
    }
}

internal fun ChatViewModel.persistGeneratedVideo(sessionId: String, bytes: ByteArray): SavedGeneratedVideo {
    val name = "video-${System.currentTimeMillis()}.mp4"
    val rel = "generated/$name"
    val dir = File(
        com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "attachments"),
        "generated",
    )
    dir.mkdirs()
    File(dir, name).writeBytes(bytes)
    return SavedGeneratedVideo(rel, "/var/minis/attachments/$rel")
}

internal suspend fun ChatViewModel.executeGenerateVideoTool(
    argsJson: String,
    current: LLMProvider?,
    activeSessionId: String,
): ToolExecutionResult {
    val args = try {
        JSONObject(argsJson)
    } catch (_: Exception) {
        JSONObject()
    }
    val prompt = args.optString("prompt", "").trim()
    val mode = args.optString("mode", "").trim().ifEmpty { null }
    if (prompt.isEmpty()) {
        return ToolExecutionResult("prompt is required", false, toolTitle = ProductMediaTools.GENERATE_VIDEO)
    }
    val provider = resolveVideoProvider(current)
        ?: return ProductMediaTools.notConfigured(
            ProductMediaTools.GENERATE_VIDEO,
            "Add an OpenAI-compatible video model (Sora / Veo / Kling) and enable Video Output.",
        )
    return try {
        if (provider is com.openminis.app.provider.openai.OpenAIProvider) {
            provider.videoMode = mode
        }
        val response = provider.generateVideo(prompt)
        val att = response.mediaAttachments.firstOrNull {
            it.type == LLMMediaAttachment.MediaType.VIDEO && it.data.isNotEmpty()
        } ?: return ToolExecutionResult(
            response.text.ifBlank { "No video returned" },
            false,
            toolTitle = ProductMediaTools.GENERATE_VIDEO,
        )
        val sid = activeSessionId.ifEmpty { realSessionId.ifEmpty { sessionId } }
        val saved = persistGeneratedVideo(sid, att.data)
        val md = "![video](minis://attachments/${saved.relPath})"
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
            toolTitle = ProductMediaTools.GENERATE_VIDEO,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolExecutionResult(
            "Video generation failed: ${e.message ?: e.javaClass.simpleName}",
            false,
            toolTitle = ProductMediaTools.GENERATE_VIDEO,
        )
    }
}

internal fun ChatViewModel.resolveVideoProvider(current: LLMProvider?): LLMProvider? {
    if (current is OpenAIProvider && current.model.isVideoOutput) return current
    val cfg = providerRepository.config.value
    for (entry in cfg.modelEntries) {
        if (entry.isHidden) continue
        if (!entry.model.isVideoOutput) continue
        val inst = cfg.instances.find { it.id == entry.providerInstanceId } ?: continue
        val key = providerRepository.loadApiKey(inst.id) ?: ""
        val created = ProviderFactory.create(inst, key, entry.model, context)
        if (created is OpenAIProvider) return created
    }
    return current as? OpenAIProvider
}
