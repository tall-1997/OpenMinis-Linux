package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.provider.safeOptString
import okhttp3.Request
import org.json.JSONObject

    /**
     * Parse the `/images/generations` response into an [LLMResponse] carrying
     * the decoded image bytes as [LLMMediaAttachment]s. Supports `b64_json`
     * (inline) and `url` (downloaded) item shapes. Mirrors iOS
     * parseImageGenerationsResult. When the body has no `data` array but DOES
     * carry `choices`, a proxy silently rerouted us to chat completions — throw
     * a route-missing error so auto-mode falls back instead of caching the
     * wrong endpoint.
     */
internal fun OpenAIProvider.parseImageGenerationsResult(json: JSONObject): LLMResponse {
        val dataArray = json.optJSONArray("data")
        if (dataArray == null) {
            if (json.has("choices")) {
                throw LLMError.ProviderError(
                    "[404] /images/generations not supported (got chat completions response)",
                )
            }
            return LLMResponse("", "end_turn", null, emptyList())
        }

        val attachments = mutableListOf<LLMMediaAttachment>()
        val revisedPrompts = mutableListOf<String>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val hintMime = item.safeOptString("mime_type", "").ifEmpty { null } // xAI extension
            val b64 = item.safeOptString("b64_json", "")
            if (b64.isNotEmpty()) {
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[ModelUseRoute] images/generations b64 decode failed: ${e.message}",
                    )
                    continue
                }
                val mime = hintMime ?: detectImageMime(bytes)
                attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, bytes))
            } else {
                val urlStr = item.safeOptString("url", "")
                if (urlStr.isNotEmpty()) {
                    try {
                        val dlReq = Request.Builder().url(urlStr).get().build()
                        val dlResp = client.newCall(dlReq).execute()
                        val dlBytes = dlResp.body?.bytes()
                        val ctMime = dlResp.header("Content-Type")
                        dlResp.close()
                        if (dlBytes != null && dlBytes.isNotEmpty()) {
                            val mime = hintMime ?: ctMime ?: detectImageMime(dlBytes)
                            attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, dlBytes))
                        }
                    } catch (e: Exception) {
                        com.openminis.app.logging.AppLogger.warning(
                            "OpenAIProvider",
                            "[ModelUseRoute] failed to download image from $urlStr: ${e.message}",
                        )
                    }
                }
            }
            val revised = item.safeOptString("revised_prompt", "")
            if (revised.isNotEmpty()) revisedPrompts.add(revised)
        }

        val text = revisedPrompts.joinToString("\n")
        return LLMResponse(text, "end_turn", null, attachments)
    }

