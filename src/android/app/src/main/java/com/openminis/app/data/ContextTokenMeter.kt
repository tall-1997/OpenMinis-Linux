package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.api.ChatRequest
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap

/**
 * Scale-free context estimate for the assembled request, before provider-specific serialization.
 * Pass the actual projected messages, system prompt and advertised tools when sampling calibration.
 * This is not a tokenizer, a provider usage parser, or a billing ledger.
 */
object ContextTokenMeter {
    const val IMAGE_TOKENS = 1_500

    // Only immutable text is memoized; a numeric value cannot retain its weak key.
    private val textScans = Collections.synchronizedMap(WeakHashMap<String, Int>())

    /** The existing mixed-text ruler, rounded up so short nonempty blocks still cost something. */
    fun rawTextTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return textScans[text] ?: scanTokens(text).also { textScans[text] = it }
    }

    fun rawPartTokens(part: AgentContentPart): Int = Projection().apply { part(part) }.tokens()

    fun rawMessageTokens(message: LLMMessage): Int = Projection().apply { message(message) }.tokens()

    fun rawMessagesTokens(messages: List<LLMMessage>): Int = rawRequestTokens(messages)

    fun rawRequestTokens(request: ChatRequest): Int = rawRequestTokens(
        request.messages, request.systemPrompt, request.tools, request.imageParts,
    )

    fun rawRequestTokens(
        messages: List<LLMMessage>,
        systemPrompt: String? = null,
        tools: List<AgentToolDefinition> = emptyList(),
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
    ): Int = Projection().apply { request(messages, systemPrompt, tools, imageParts) }.tokens()

    fun requestFingerprint(request: ChatRequest): String = requestFingerprint(
        request.messages, request.systemPrompt, request.tools, request.imageParts,
    )

    /**
     * SHA-256 of the ordered context projection. Recomputed on demand: neither histories nor
     * mutable JSON/media are cached. Excludes storage IDs and generation settings; this is not
     * a signature of any provider's eventual HTTP body.
     */
    fun requestFingerprint(
        messages: List<LLMMessage>,
        systemPrompt: String? = null,
        tools: List<AgentToolDefinition> = emptyList(),
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
    ): String {
        val hash = RequestHash()
        Projection(hash).request(messages, systemPrompt, tools, imageParts)
        return hash.finish()
    }

    private fun scanTokens(text: String): Int {
        var ascii = 0L
        var nonAscii = 0L
        for (c in text) if (c.code < 128) ascii++ else nonAscii++
        // ceil(ascii / 4 + nonAscii * 3 / 5), with no intermediate rounding.
        return ((ascii * 5 + nonAscii * 12 + 19) / 20).toInt()
    }

    private class Projection(private val hash: RequestHash? = null) {
        private var total = 0L

        fun tokens(): Int = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        fun request(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            tools: List<AgentToolDefinition>,
            imageParts: List<LLMMessage.ImagePart>,
        ) {
            text(systemPrompt.orEmpty())
            hash?.number(tools.size)
            for (tool in tools) text(schemaJson(tool), memoize = false)
            hash?.number(messages.size)
            val lastUser = if (imageParts.isEmpty()) -1 else messages.indexOfLast { it.role == LLMMessage.Role.USER }
            messages.forEachIndexed { index, message ->
                message(message, if (index == lastUser) imageParts else emptyList())
            }
        }

        fun message(message: LLMMessage, requestImages: List<LLMMessage.ImagePart> = emptyList()) {
            hash?.text(message.role.value)
            // Some providers echo retained assistant reasoning; their wire rules remain separate.
            text(if (message.role == LLMMessage.Role.ASSISTANT) message.reasoningContent.orEmpty() else "")
            hash?.number(message.contentParts.size)
            if (message.contentParts.isNotEmpty()) {
                for (part in message.contentParts) part(part)
                // content and per-message attachments can mirror the structured parts. Explicit
                // request images from the legacy API can still be distinct (e.g. Responses API).
                val extras = if (requestImages.isEmpty()) requestImages else requestImages.filterNot { image ->
                    message.contentParts.any { part ->
                        part is AgentContentPart.ImageData && part.mimeType == image.mimeType &&
                            part.data.contentEquals(image.data)
                    }
                }
                images(extras)
            } else {
                text(message.content)
                images(requestImages.ifEmpty { message.imageParts })
                hash?.number(message.audioParts.size)
                for (audio in message.audioParts) {
                    if (hash != null) {
                        hash.text(audio.format)
                        hash.text(audio.base64Data)
                    } else {
                        // A coarse duration proxy: roughly one token per KiB of encoded audio.
                        // Base64 is media transport, not text the model has to tokenize.
                        total += (audio.base64Data.length.toLong() + 1_023) / 1_024
                    }
                }
            }
        }

        fun part(part: AgentContentPart) {
            when (part) {
                is AgentContentPart.Text -> {
                    hash?.number(1)
                    text(part.text)
                }
                is AgentContentPart.ToolUse -> {
                    hash?.number(2)
                    hash?.text(part.id)
                    hash?.text(part.thoughtSignature)
                    text(part.name)
                    val snapshot = part.inputJsonSnapshot
                    text(snapshot ?: part.inputJson, memoize = snapshot != null)
                }
                is AgentContentPart.ToolResult -> {
                    hash?.number(3)
                    hash?.text(part.id)
                    hash?.text(part.name)
                    hash?.number(if (part.isError) 1 else 0)
                    text(part.content)
                    image(part.imageData, part.imageMimeType)
                }
                is AgentContentPart.ImageData -> {
                    hash?.number(4)
                    image(part.data, part.mimeType, part.noVisionPlaceholder)
                }
            }
        }

        private fun text(value: String, memoize: Boolean = true) {
            if (hash != null) hash.text(value)
            else total += if (memoize) rawTextTokens(value) else scanTokens(value)
        }

        private fun images(images: List<LLMMessage.ImagePart>) {
            hash?.number(images.size)
            for (part in images) image(part.data, part.mimeType, part.noVisionPlaceholder)
        }

        private fun image(data: ByteArray?, mime: String?, placeholder: String? = null) {
            if (hash != null) {
                hash.text(mime)
                hash.text(placeholder)
                hash.bytes(data)
            } else if (data != null && data.isNotEmpty()) {
                total += IMAGE_TOKENS
            }
        }
    }

    /** Only model-visible schema fields, no tool registry metadata or provider envelope. */
    private fun schemaJson(tool: AgentToolDefinition): String = JSONObject().apply {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                for ((key, param) in tool.parameters) put(key, param.toJson())
            })
            if (tool.required.isNotEmpty()) put("required", JSONArray(tool.required))
            tool.propertyOrdering?.let { put("propertyOrdering", JSONArray(it)) }
        })
    }.toString()

    /** Typed fields and length framing prevent concatenation ambiguities across parts. */
    private class RequestHash {
        private val digest = MessageDigest.getInstance("SHA-256")

        fun number(value: Int) {
            for (shift in 24 downTo 0 step 8) digest.update((value ushr shift).toByte())
        }

        fun text(value: String?) = bytes(value?.toByteArray(Charsets.UTF_8))

        fun bytes(value: ByteArray?) {
            number(value?.size ?: -1)
            if (value != null) digest.update(value)
        }

        fun finish(): String = buildString(64) {
            for (byte in digest.digest()) {
                append("0123456789abcdef"[(byte.toInt() and 0xff) ushr 4])
                append("0123456789abcdef"[byte.toInt() and 0xf])
            }
        }
    }
}
