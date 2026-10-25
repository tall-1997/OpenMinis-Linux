package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.inferredMaxOutputTokens
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import com.openminis.app.provider.api.ChatRequest
import com.openminis.app.provider.api.ModelProvider
import com.openminis.app.provider.api.ProviderCapabilities
import com.openminis.app.provider.api.StreamEvent
import com.openminis.app.util.SecretMasking
import org.json.JSONObject

interface LLMProvider : ModelProvider {
    val name: String
    var model: LLMModel

    override val id: String get() = name
    override val capabilities: ProviderCapabilities
        get() = ProviderCapabilities(
            streaming = true,
            images = true,
            tools = true,
            thinking = model.supportsReasoning != false,
            models = false,
        )

    override suspend fun chat(request: ChatRequest, systemPrompt: String?): LLMResponse =
        sendMessage(
            request.messages,
            systemPrompt ?: request.systemPrompt,
            request.maxTokens,
            request.temperature,
            request.imageParts,
            request.tools,
            request.thinkingLevel,
        )

    override fun streamChat(request: ChatRequest): Flow<StreamEvent> =
        streamMessage(
            request.messages,
            request.systemPrompt,
            request.maxTokens,
            request.temperature,
            request.imageParts,
            request.tools,
            request.thinkingLevel,
        )

    override suspend fun models(): List<LLMModel> = listOf(model)

    /**
     * [T-android-nonstream-deadline] Wall-clock ceiling for NON-streaming calls
     * ([sendMessage] callers: title gen, compaction, oneShotAsk, vision group,
     * quick test, model-use, group suggest). Streaming turns are exempt — their
     * budget is the per-attempt read timeout plus the retry/fallback ladder.
     * Timeout surfaces as [LLMError.TransientError] so existing retry/fallback
     * classification applies unchanged.
     */
    val nonStreamDeadlineMs: Long get() = 900_000L

    /** Host+credential+model fingerprint for [ProviderKeyGate]. Blank skips the gate. */
    val callGateKey: String get() = ""

    /**
     * [T-llm-error-401-model-scope] [callGateKey] minus its model segment: the
     * host + credential fingerprint alone. Lets an error mapper ask
     * [CredentialAcceptance] "was this credential accepted by this host?" even
     * though the call that failed was for one specific model.
     *
     * Derived by default so no provider has to opt in; providers that override
     * [callGateKey] get this for free.
     */
    val credentialGateKey: String
        get() = ProviderKeyGate.credentialScopeOf(callGateKey)

    /**
     * Effective max output tokens ceiling for the given model.
     * Priority: model.maxOutputTokens > models.dev (normalized id) >
     * family heuristic > provider-level default.
     * Used as the upper bound in dynamicMaxTokens().
     */
    fun effectiveMaxOutputTokens(model: LLMModel): Int {
        model.maxOutputTokens?.takeIf { it > 0 }?.let { return it }
        // Catalog lookup with normalized ids so a relay spelling like
        // `z-ai/glm-5.2` still picks up models.dev's output cap instead of
        // falling through to the 16k provider default.
        ModelsDevApi.enrichModel(model).maxOutputTokens?.takeIf { it > 0 }?.let { return it }
        return inferredMaxOutputTokens(model.id, model.displayName) ?: defaultMaxOutputTokens
    }

    /** Provider-level fallback when model.maxOutputTokens is unknown. */
    val defaultMaxOutputTokens: Int get() = 16_384

    /**
     * [T-android-tool-splits-reply-fix] True when the streamed assistant text
     * is one monolithic `content` string per response (OpenAI Chat
     * Completions): text deltas carry NO positional relationship to
     * tool_calls deltas — a non-streaming materialisation is always
     * {content, tool_calls} with content first — so ALL text deltas of one
     * streamed response belong to a single text block that precedes the tool
     * blocks. Some endpoints (qwen) flush trailing content chunks after
     * tool_calls deltas purely as a chunking artifact; reconstructing those
     * chronologically fabricates an order the wire format cannot express.
     * False for formats with genuinely ordered output blocks (Responses API
     * output items, Anthropic content blocks), where arrival order IS the
     * semantic block order.
     */
    val streamTextIsMonolithic: Boolean get() = false

    /**
     * [T-android-thinking-level-arch] PUBLIC entry — this is what every caller
     * (agent loop, fallback, quick test, model-use, …) invokes. It is NOT
     * overridden by providers: the default implementation clamps the requested
     * thinking level to the current [model]'s ceiling ONCE, then delegates to
     * [sendMessageClamped]. Making the clamp structural (rather than a call each
     * impl must remember) means no code path can send an over-range level — a
     * fallback that swapped [model] mid-flight clamps to the NEW model's limit
     * automatically. Mirrors iOS AgentProvider.streamAgentMessage → …Clamped.
     */
    suspend fun sendMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): LLMResponse {
        val level = clampThinkingLevel(thinkingLevel)
        val instanceId = SamplingIdentity.of(this)
        val wire = SamplingPolicy.wire(instanceId, model.id, temperature, level.isEnabled)
        return try {
            sendMessageOnce(messages, systemPrompt, maxTokens, wire, imageParts, tools, level)
        } catch (e: Throwable) {
            if (wire != null && SamplingPolicy.isTemperatureRejection(e)) {
                SamplingPolicy.deny(instanceId, model.id)
                sendMessageOnce(messages, systemPrompt, maxTokens, null, imageParts, tools, level)
            } else {
                throw e
            }
        }
    }

    private suspend fun sendMessageOnce(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        level: ThinkingLevel,
    ): LLMResponse {
        try {
            return withTimeout(nonStreamDeadlineMs) {
                ProviderKeyGate.withPermit(callGateKey) {
                    sendMessageClamped(
                        messages, systemPrompt, maxTokens, temperature, imageParts, tools, level,
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw LLMError.TransientError(
                "non-stream call exceeded ${nonStreamDeadlineMs / 1000}s deadline",
            )
        }
    }

    /** See [sendMessage] — the clamped, provider-implemented counterpart. */
    fun streamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        systemStablePrefixLen: Int = -1,
    ): Flow<LLMStreamChunk> {
        val level = clampThinkingLevel(thinkingLevel)
        val instanceId = SamplingIdentity.of(this)
        val first = SamplingPolicy.wire(instanceId, model.id, temperature, level.isEnabled)
        // channelFlow: [ProviderKeyGate.withPermit] uses withContext(HeldKeys),
        // which is a different coroutine than the collector. Regular `flow { emit }`
        // forbids that (IllegalStateException: Flow invariant is violated).
        return channelFlow {
            var temp = first
            var stripped = false
            while (true) {
                var emitted = false
                try {
                    val inner = streamMessageClamped(
                        messages, systemPrompt, maxTokens, temp, imageParts, tools, level,
                        systemStablePrefixLen,
                    )
                    val key = callGateKey
                    if (key.isBlank()) {
                        inner.collect {
                            emitted = true
                            send(it)
                        }
                    } else {
                        ProviderKeyGate.withPermit(key) {
                            inner.collect {
                                emitted = true
                                send(it)
                            }
                        }
                    }
                    break
                } catch (e: Throwable) {
                    // A 400 that arrives before any token is a sampling rejection,
                    // not a transient network retry. Strip the field once and
                    // remember the pair. Do not rewrite the value to 1.
                    if (!stripped && !emitted && temp != null && SamplingPolicy.isTemperatureRejection(e)) {
                        stripped = true
                        SamplingPolicy.deny(instanceId, model.id)
                        temp = null
                        continue
                    }
                    throw e
                }
            }
        }
    }

    /**
     * Generate a video clip from a text prompt. Default throws; OpenAI-compatible
     * providers implement the Videos API / relay `/video/generations` shapes.
     */
    suspend fun generateVideo(prompt: String): LLMResponse {
        throw LLMError.ProviderError("This provider does not support video generation")
    }

    /** Generate an image from a text prompt when the provider supports it. */
    suspend fun generateImage(
        prompt: String,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse {
        if (prompt.isBlank()) throw LLMError.ProviderError("Image prompt is empty")
        return sendMessage(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, prompt.trim())),
            systemPrompt = null,
            maxTokens = 1024,
        )
    }

    /**
     * [T-android-thinking-level-arch] Provider implementations override THIS
     * (not [sendMessage]). The `thinkingLevel` received here has already been
     * clamped to the model's ceiling by [sendMessage] — implementations must NOT
     * re-clamp. Mirrors iOS `streamAgentMessageClamped`.
     */
    suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse

    /** See [sendMessageClamped]. */
    fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        systemStablePrefixLen: Int = -1,
    ): Flow<LLMStreamChunk>

    /**
     * [T-android-thinking-level-arch] Cap a requested thinking level to the
     * current [model]'s ceiling by rank. Used by the [sendMessage]/[streamMessage]
     * default entries above; the catalog ceiling is resolved off the live
     * `model`.
     */
    fun clampThinkingLevel(level: ThinkingLevel): ThinkingLevel {
        val ceiling = model.catalogMaxThinkingLevel
        return if (level.rank > ceiling.rank) ceiling else level
    }
}

/**
 * [T-android-empty-stream-retry] Detect a silently-truncated stream.
 *
 * When a relay/upstream drops the SSE connection without an error status,
 * the provider flow completes "normally" having emitted no content and no
 * finish reason — the chat just stops mid-air with no error (user report).
 * iOS treats this as a transient error at its stream-collection layer
 * (AIChatViewModel.isEmptyResponse → LLMError.transientError) and
 * auto-retries; this operator is the Android provider-layer equivalent.
 *
 * Empty = no text / thinking / reasoning / tool-call / media chunk AND no
 * finish reason. A stream that finished WITH a stop reason but no content
 * ("stop"/"end_turn") is deliberately let through — the agent loop's
 * empty-after-tool-result reminder path (ChatViewModel) owns that case, and
 * a "length"/"max_tokens" cut is a legitimate empty. Cancellation and
 * thrown errors propagate before the check runs (code after `collect`
 * only executes on normal completion).
 */
fun Flow<LLMStreamChunk>.failOnSilentEmptyCompletion(providerName: String): Flow<LLMStreamChunk> = flow {
    var sawContent = false
    var sawFinishReason = false
    collect { chunk ->
        when (chunk) {
            is LLMStreamChunk.Text -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ThinkingDelta -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ReasoningContent -> if (chunk.content.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ToolUseStart,
            is LLMStreamChunk.ToolInputDelta,
            is LLMStreamChunk.ToolCallComplete,
            is LLMStreamChunk.MediaAttachment -> sawContent = true
            is LLMStreamChunk.Finished -> if (chunk.stopReason != null) sawFinishReason = true
            else -> {}
        }
        emit(chunk)
    }
    if (!sawContent && !sawFinishReason) {
        android.util.Log.w(
            "LLMProvider",
            "$providerName: stream completed with no content and no finish reason — treating as transient upstream failure",
        )
        throw LLMError.TransientError("Server returned an empty response (connection dropped or upstream error)")
    }
}

/** HTTP statuses that are transient upstream trouble by default. */
private val TRANSIENT_HTTP_STATUSES = setOf(500, 502, 503, 504, 529)

/**
 * [T-llm-error-classification] Shared HTTP-error mapping core for the provider
 * error mappers (OpenAI / Gemini / Anthropic). Owns the branches the three
 * copies used to carry verbatim — the [T-llm-error-401-model-scope]
 * credential-acceptance gate on 401/403, the [T-llm-error-403] plan/region
 * denial detail, 429 [HttpRetryAfter.map429] Retry-After handling, the shared
 * transient status set with the 503 permanent-capacity demotion, and the
 * generic JSON message envelope with [SecretMasking].
 *
 * Provider differences are injected as hooks:
 *  - [isContextLimit] / [isContentFiltered] — each provider's keyword dialect
 *    for the [T-llm-error-classification] split; evaluated in that order on
 *    the lowercased envelope message.
 *  - [messageLabel] — the bracketed token of the JSON envelope ([statusCode]
 *    by default; Anthropic labels with the upstream error.type).
 *  - [envelope] — full override for mappers that never parse the body
 *    (Gemini's "Gemini API error …" wording).
 *  - [map413ToContextLength] — [T-llm-error-413]: OpenAI routes 413 to the
 *    actionable ContextLengthExceeded class; the others keep ProviderError.
 *
 * OpenAI's Zen structured refusal ([zenStructuredProviderRefusal]) is checked
 * by its own wrapper BEFORE this core runs, so it outranks every branch here.
 */
internal fun mapHttpErrorShared(
    statusCode: Int,
    body: String,
    retryAfterHeader: String?,
    credentialGateKey: String,
    isContextLimit: (statusCode: Int, lowerMessage: String) -> Boolean,
    isContentFiltered: (statusCode: Int, lowerMessage: String) -> Boolean,
    messageLabel: (statusCode: Int, error: JSONObject?) -> String = { code, _ -> "[$code]" },
    envelope: ((statusCode: Int, body: String) -> String)? = null,
    map413ToContextLength: Boolean = false,
): LLMError {
    if (statusCode == 401 || statusCode == 403) {
        // [T-llm-error-401-model-scope] A gateway fronting an upstream pool
        // answers 401 for failures in the channel IT picked, not for anything
        // wrong with the caller's key. When this same credential was accepted
        // by this same host recently, "Invalid API key" is simply false, and
        // its hint sends the user off to regenerate a key that works.
        //
        // No acceptance evidence falls through to the status-only mappings
        // below, so nothing changes for a credential the app has never seen
        // succeed.
        if (CredentialAcceptance.isAccepted(credentialGateKey)) {
            return LLMError.InvalidApiKey(
                "HTTP $statusCode — the same credential was accepted by this host recently, " +
                    "so the refusal is about this model, not the key",
                credentialAccepted = true,
            )
        }
    }
    if (statusCode == 401) return LLMError.InvalidApiKey()
    if (statusCode == 403) {
        // [T-llm-error-403] 403 means "valid key, but this model or region is
        // not allowed on your plan". Folding it into a bare InvalidApiKey()
        // told users to check an API key that is actually fine.
        return LLMError.InvalidApiKey(
            "HTTP 403 forbidden — the credential was accepted but this model or region is not permitted on the current plan",
        )
    }
    if (statusCode == 429) return HttpRetryAfter.map429(body, retryAfterHeader)

    val message = envelope?.invoke(statusCode, body) ?: run {
        try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val errorMessage = error?.safeOptString("message", "") ?: body
            "${messageLabel(statusCode, error)} ${SecretMasking.mask(errorMessage)}"
        } catch (_: Exception) {
            "HTTP $statusCode: ${SecretMasking.mask(body.take(500))}"
        }
    }

    // [T-llm-error-413] Payload Too Large is the request-size twin of
    // context_length_exceeded — route it to the actionable class (compact /
    // offload hint) instead of a generic, non-retryable ProviderError.
    if (map413ToContextLength && statusCode == 413) return LLMError.ContextLengthExceeded(message)

    val lower = message.lowercase()
    if (isContextLimit(statusCode, lower)) return LLMError.ContextLengthExceeded(message)
    if (isContentFiltered(statusCode, lower)) return LLMError.ContentFiltered(message)

    if (statusCode in TRANSIENT_HTTP_STATUSES) {
        // 503 with permanent failure indicators → ProviderError (trigger group fallback)
        if (statusCode == 503 && HttpRetryAfter.isPermanentCapacityBody(body)) {
            return LLMError.ProviderError(message)
        }
        return LLMError.TransientError(message)
    }
    return LLMError.ProviderError(message)
}
