package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMStreamChunk
import java.io.BufferedReader
import org.json.JSONObject

    /**
     * [T-android-codex-image-stream-parse-fix #617] Consume the Codex Responses
     * SSE stream and extract the generated image, emitting it as a
     * MediaAttachment chunk followed by Finished.
     *
     * Structurally aligned with iOS `consumeCodexImageStream` (1225ec0b /
     * 2dd35a14): parse each `data:` SSE line as JSON and pull the base64 from
     * the `image_generation_call` output item's `result` field — NOT a blind
     * regex over the raw body. The previous regex `iVBOR[A-Za-z0-9+/=]{1000,}`
     * only matched PNG base64 (iVBOR is the base64 of the PNG \x89PNG header),
     * so a WebP (UklGR…) or JPEG (/9j/…) image — which gpt-image-2 routinely
     * returns — never matched and the method threw "no image data" even though
     * the Codex backend had returned a full ~8 MB valid image (#615 diagnosis).
     *
     * Failure modes, each a distinct LLMError (mirrors iOS):
     *   - auth (401/403): surfaced earlier by the non-2xx branch → mapHttpError,
     *     never reaches here.
     *   - safety refusal: `image_generation_call` status=failed and/or a refusal
     *     message instead of an image → ProviderError("rejected by safety…").
     *   - no image: stream completed with neither image nor refusal →
     *     ProviderError("No image data…"). Only reported in this genuine case —
     *     not on a successfully-decoded non-PNG image.
     *   - network/interface: read throws (IOException) → caller maps to
     *     NetworkError.
     *
     * Streams line-by-line (no 8 MB StringBuilder + regex backtracking): only
     * the one `result` base64 string is retained, decoded once at the end.
     * Never logs the token (the SSE body carries no Authorization).
     */
internal suspend fun OpenAIProvider.handleCodexImageStream(
        reader: BufferedReader,
        emit: (LLMStreamChunk) -> Unit,
    ) {
        var b64Result: String? = null
        var revisedPrompt: String? = null
        var imageCallFailed = false
        var refusalText: String? = null

        // Pull the base64 result / failure / revised prompt out of one output
        // item. Used both for streamed `response.output_item.done` items and,
        // as a fallback, for every item in the final `response.completed`
        // payload (matches iOS scanItem).
        fun scanItem(item: JSONObject) {
            when (item.optString("type")) {
                "image_generation_call" -> {
                    if (item.optString("status") == "failed") imageCallFailed = true
                    item.optString("result").takeIf { it.isNotEmpty() }?.let { b64Result = it }
                    item.optString("revised_prompt").takeIf { it.isNotEmpty() }?.let { revisedPrompt = it }
                }
                "message", "output_text" -> {
                    // Refusal / explanation text the model emits when it declines.
                    val content = item.optJSONArray("content")
                    if (content != null) {
                        for (i in 0 until content.length()) {
                            val c = content.optJSONObject(i) ?: continue
                            if (c.optString("type").contains("text")) {
                                c.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                            }
                        }
                    } else {
                        item.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                    }
                }
            }
        }

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            // Tolerate both `data: {…}` and `data:{…}` (same as the chat path).
            if (!l.startsWith("data:")) continue
            val payload = l.removePrefix("data:").let { if (it.startsWith(" ")) it.removePrefix(" ") else it }
            if (payload == "[DONE]") break

            val event = try { JSONObject(payload) } catch (e: Exception) { continue }
            when (event.optString("type")) {
                "response.output_item.done" -> {
                    event.optJSONObject("item")?.let { scanItem(it) }
                }
                "response.output_text.done", "response.output_text.delta" -> {
                    event.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                        ?: event.optString("delta").takeIf { it.isNotEmpty() }
                            ?.let { refusalText = (refusalText ?: "") + it }
                }
                "response.completed" -> {
                    if (b64Result == null) {
                        val output = event.optJSONObject("response")?.optJSONArray("output")
                        if (output != null) {
                            for (i in 0 until output.length()) {
                                output.optJSONObject(i)?.let { scanItem(it) }
                            }
                        }
                    }
                }
                "response.failed", "error" -> {
                    val msg = event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotEmpty() }
                        ?: event.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                        ?: "Codex image generation failed"
                    throw LLMError.ProviderError(msg)
                }
            }
        }

        // Success: base64 image extracted. Detect the real format from the
        // decoded bytes (PNG / JPEG / WebP / GIF) instead of assuming PNG.
        val b64 = b64Result
        if (b64 != null) {
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Exception) {
                throw LLMError.ProviderError("Failed to decode generated image: ${e.message}")
            }
            if (bytes.isNotEmpty()) {
                emit(
                    LLMStreamChunk.MediaAttachment(
                        LLMMediaAttachment(
                            type = LLMMediaAttachment.MediaType.IMAGE,
                            mimeType = detectImageMime(bytes),
                            data = bytes,
                        ),
                    ),
                )
                emit(LLMStreamChunk.Finished("end_turn"))
                return
            }
        }

        // Safety refusal: the image call explicitly failed and/or the model
        // returned a refusal message instead of an image.
        if (imageCallFailed || refusalText != null) {
            val reason = refusalText?.trim()
            throw LLMError.ProviderError(
                "Image generation was rejected by the safety system" +
                    (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
            )
        }

        // Stream completed with neither an image nor a refusal.
        throw LLMError.ProviderError("No image data in Codex response")
    }

