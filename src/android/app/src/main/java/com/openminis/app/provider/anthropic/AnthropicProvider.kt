package com.openminis.app.provider.anthropic

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.sanitizeToolId
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.FullBodyJson
import com.openminis.app.provider.executeCancellable
import com.openminis.app.provider.NonFiniteJson
import com.openminis.app.provider.ProviderKeyGate
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.SamplingIdentity
import com.openminis.app.provider.SamplingPolicy
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.mapHttpErrorShared
import com.openminis.app.provider.safeOptString
import com.openminis.app.provider.ssePayloads
import com.openminis.app.provider.cancelOnCancellation
import com.openminis.app.provider.StreamTailDrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion
import com.openminis.app.provider.thinking.ThinkingLadder
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.network.applyAppTrustStore
import com.openminis.app.auth.applyClaudeCliMimicryHeaders

class AnthropicProvider(
    private val apiKey: String,
    override var model: LLMModel = LLMModel.claudeHaiku45,
    private val basePath: String = "https://api.anthropic.com",
    /** Whether this provider uses OAuth credentials (Bearer + beta header). */
    val isOAuth: Boolean = false,
    /**
     * [T-provider-custom-user-agent] Per-provider User-Agent override.
     * null/blank → default UA; non-blank → replaces User-Agent on the chat
     * request. Only set for custom-base Anthropic-compat instances.
     */
    private val customUserAgent: String? = null,
    private val oauthIdentifierPromptProvider: () -> String = {
        com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
    },
    private val forceThinkingEnabled: Boolean = false, // [OpenMinis#311] force thinking
) : LLMProvider {
    override val name = "Anthropic"
    override val forcesThinkingWhenEnabled: Boolean get() = forceThinkingEnabled
    override val callGateKey: String
        get() = ProviderKeyGate.key(basePath, apiKey, model.id)
    override val defaultMaxOutputTokens: Int get() = 64_000

    /**
     * [T-quota-claude-subscription] Instance tag set by
     * ProviderFactory for OAuth instances, so replies' usage headers reach the
     * quota store. Null = not quota-tracked.
     */
    var quotaInstanceId: String? = null

    /**
     * [T-android-enhanced-cache] Enhanced Cache toggle — when true, every
     * `cache_control: ephemeral` breakpoint carries `ttl:"1h"` (1-hour cache
     * TTL) instead of the default 5-minute TTL, and the
     * `extended-cache-ttl-2025-04-11` beta flag is attached to API-key requests
     * (OAuth already carries it). Set per chat turn from
     * `ChatViewModel.enhancedCacheEnabled`; defaults off so existing behaviour
     * is unchanged. Mirrors iOS `RequestBodyPatcher.setExtendedCacheTTL`.
     */
    var enhancedCache: Boolean = false

    /** Build a `cache_control` honoring [enhancedCache]: default 5-minute TTL `{"type":"ephemeral"}`, enhanced adds `"ttl":"1h"`. Every breakpoint routes here. */
    private fun ephemeralCacheControl(): JSONObject =
        JSONObject().put("type", "ephemeral").apply {
            if (enhancedCache) put("ttl", "1h")
        }

    /** Use Bearer auth for custom (non-Anthropic) base URLs or OAuth. */
    private val isCustomEndpoint: Boolean = basePath != "https://api.anthropic.com"

    private val client = OkHttpClient.Builder()
        // [T-doh-resolver-fallback] A custom base URL means an arbitrary host;
        // when the system resolver cannot name it, every provider-level retry
        // re-runs the same failed lookup and the chat just never starts.
        .dns(com.openminis.app.network.DohDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool — see NetworkMonitor.
        // Network-transition eviction must reach provider connections.
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        // [GH#258][GH#287] Custom-base providers may present a private CA.
        .applyAppTrustStore()
        // [GH#188] Fill the per-call TtfbWatchState from requestBodyEnd /
        // connectionAcquired so the watchdog can start its TTFB clock after
        // the upload and evict THIS connection on timeout.
        .eventListener(com.openminis.app.provider.UploadAwareTtfbListener())
        .build()

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
        temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        extraHeaders: Map<String, String>,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, stream = false, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        val request = buildRequest(body.toString(), body)
        // [T-android-stop-cancels-socket] Cancel must close the blocking IO.
        val response = client.newCall(request).executeCancellable()
        if (isOAuth) com.openminis.app.quota.QuotaObservationSink.recordAnthropicResponse(quotaInstanceId, response.headers)
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw mapHttpError(response.code, responseBody, response.header("Retry-After"))
        }

        // [T-nonstream-json-default] Parse moves to Default (read stays on IO); FullBodyJson wraps the raw JSONException.
        val json = FullBodyJson.parse(responseBody, "anthropic non-streaming body")
        parseResponse(json)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
        temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        systemStablePrefixLen: Int,
        // Interface parity; unused (no Anthropic per-conversation header).
        extraHeaders: Map<String, String>,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
        systemStablePrefixLen,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        systemStablePrefixLen: Int = -1,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel, systemStablePrefixLen = systemStablePrefixLen)
        // T302: serialize the body exactly once and pass the string to
        // buildRequest. Pre-T302 body.toString() ran twice per call (once
        // here, once inside buildRequest); each emitted string was tens of
        // MB on long agent loops and stacked under GC pressure.
        val bodyStr = body.toString()
        val request = buildRequest(bodyStr, body)

        val startTime = System.currentTimeMillis()

        // Capture request headers as map
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }

        val watch = com.openminis.app.provider.TtfbWatchState()
        val call = client.newCall(
            request.newBuilder()
                .tag(com.openminis.app.provider.TtfbWatchState::class.java, watch)
                .build(),
        )
        // Install before execute: cancel must unblock the header wait too.
        val cancelOnExit = call.cancelOnCancellation(
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job],
        )
        try {
        val response = com.openminis.app.provider.StreamTtfb.execute(
            call, "Anthropic", watch = watch,
        )
        val durationMs = System.currentTimeMillis() - startTime
        // [T-quota-claude-subscription] Errors included: a 429 still reports usage.
        if (isOAuth) com.openminis.app.quota.QuotaObservationSink.recordAnthropicResponse(quotaInstanceId, response.headers)

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            // T302: skip LLMRequestLog on release builds (see OpenAIProvider —
            // multi-MB bodies retained there are pure OOM risk for zero benefit).
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "anthropic",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = durationMs,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            // [T-llm-error-secret-mask] Logcat feeds filesDir/logs/*.log (
            // files CONTRIBUTING.md asks users to attach), and the upstream body
            // is not ours to trust — mask it like the LLMError message is.
            android.util.Log.e(
                "AnthropicProvider",
                "Stream failed: ${response.code} isOAuth=$isOAuth body=${com.openminis.app.util.SecretMasking.mask(errorBody.take(300))}",
            )
            throw mapHttpError(response.code, errorBody, response.header("Retry-After"))
        }

        // Log successful request (debug builds only — see above)
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "anthropic",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = durationMs,
                    responseStatusCode = response.code,
                )
            )
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        // Track current tool_use block being streamed
        var currentToolId: String? = null
        var currentToolName: String? = null
        val toolInputBuffer = StringBuilder()
        var lastSentToolInputLen = 0
        val drain = StreamTailDrain(this, { call.cancel() })

        try {
            // Shared SSE assembler: multi-line `data:` is joined, `data:{...}`
            // with no space is tolerated, and `[DONE]` is preserved.
            for (payload in ssePayloads(reader, drain)) {
                if (payload == "[DONE]") break

                val event = try { JSONObject(payload) } catch (_: Exception) { continue }
                if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) {
                    android.util.Log.d(
                        "ToolChain[Provider]",
                        "RAW SSE: ${com.openminis.app.text.BoundedText.clampSsePayload(payload)}",
                    )
                }
                val eventType = event.safeOptString("type", "")

                when (eventType) {
                    "message_stop" -> break
                    // [T-anthropic-sse-error-event] `data: {"type":"error", …}` is
                    // how Anthropic (and every compatible relay) reports a mid-stream
                    // failure. Unhandled, the stream "completed" with no content and
                    // no finish reason and failOnSilentEmptyCompletion mis-labelled
                    // it an empty response — wrong retry budget, wrong fallback
                    // trigger. Map it through the SAME mapper the HTTP path uses.
                    "error" -> throw mapStreamErrorEvent(event)
                    "message_start" -> {
                        send(LLMStreamChunk.Started)
                        event.optJSONObject("message")?.optJSONObject("usage")?.let { usage ->
                            send(LLMStreamChunk.Usage(parseAnthropicUsage(usage)))
                        }
                    }
                    "content_block_start" -> {
                        val contentBlock = event.optJSONObject("content_block")
                        if (contentBlock?.safeOptString("type", "") == "tool_use") {
                            currentToolId = contentBlock.safeOptString("id", "")
                            currentToolName = contentBlock.safeOptString("name", "")
                            toolInputBuffer.clear()
                            lastSentToolInputLen = 0
                            android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=$currentToolId name=$currentToolName")
                            send(LLMStreamChunk.ToolUseStart(currentToolId!!, currentToolName!!))
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta") ?: continue
                        when (delta.safeOptString("type", "")) {
                            "text_delta" -> {
                                val text = delta.safeOptString("text", "")
                                if (text.isNotEmpty()) send(LLMStreamChunk.Text(text))
                            }
                            "thinking_delta" -> {
                                val thinking = delta.safeOptString("thinking", "")
                                if (thinking.isNotEmpty()) send(LLMStreamChunk.ThinkingDelta(thinking))
                            }
                            "input_json_delta" -> {
                                val partial = delta.safeOptString("partial_json", "")
                                if (partial.isNotEmpty() && currentToolId != null) {
                                    toolInputBuffer.append(partial)
                                    val n = toolInputBuffer.length
                                    if (com.openminis.app.text.BoundedText.shouldCommitLengthStride(n, lastSentToolInputLen)) {
                                        lastSentToolInputLen = n
                                        if (com.openminis.app.text.BoundedText.shouldLogLengthStride(n)) {
                                            android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=$currentToolId accumulated=${n}chars")
                                        }
                                        send(LLMStreamChunk.ToolInputDelta(currentToolId!!, toolInputBuffer.toString()))
                                    }
                                }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        if (currentToolId != null && currentToolName != null) {
                            val rawArgs = toolInputBuffer.toString()
                            val parsed = NonFiniteJson.parseOrNull(rawArgs)
                            if (parsed == null && rawArgs.isNotBlank()) {
                                // [GH#61/#83/#146] Never substitute a silent
                                // `{}`. The final accumulated buffer may be
                                // newer than the last ToolInputDelta (stride
                                // gate); emit it so ToolJsonRepair sees the
                                // exact truncation tail, and carry it on the
                                // completion for preflight to report.
                                send(LLMStreamChunk.ToolInputDelta(currentToolId!!, rawArgs))
                                android.util.Log.w(
                                    "ToolChain[Provider]",
                                    "→ ToolCallComplete id=$currentToolId name=$currentToolName " +
                                        "invalid argsLen=${rawArgs.length} rawTail=${rawArgs.take(200)}",
                                )
                            }
                            val args = parsed ?: JSONObject()
                            android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=$currentToolId name=$currentToolName args=${args.toString().take(300)}")
                            send(LLMStreamChunk.ToolCallComplete(
                                currentToolId!!, currentToolName!!, args,
                                rawArgs = rawArgs.takeIf { parsed == null && it.isNotBlank() },
                            ))
                            currentToolId = null
                            currentToolName = null
                            toolInputBuffer.clear()
                            lastSentToolInputLen = 0
                        }
                    }
                    "message_delta" -> {
                        event.optJSONObject("usage")?.let { usage ->
                            send(LLMStreamChunk.Usage(parseAnthropicUsage(usage)))
                        }
                        val delta = event.optJSONObject("delta")
                        val stopReason = delta?.safeOptString("stop_reason", "")?.ifEmpty { null }
                        if (stopReason != null) drain.terminal()
                        // [FIX-261018-12] stop_details says why (refusal category).
                        val details = if (stopReason == "refusal") delta?.optJSONObject("stop_details") else null
                        send(LLMStreamChunk.Finished(
                            stopReason,
                            refusalCategory = details?.safeOptString("category", "")?.ifEmpty { null },
                            refusalExplanation = details?.safeOptString("explanation", "")?.ifEmpty { null },
                        ))
                    }
                }
            }

            // [T-anthropic-tool-drain] Relay drops mid-tool_use never send
            // content_block_stop — flush so the agent loop doesn't hang.
            if (currentToolId != null && currentToolName != null) {
                val rawArgs = toolInputBuffer.toString()
                val parsed = NonFiniteJson.parseOrNull(rawArgs)
                if (parsed == null && rawArgs.isNotBlank()) {
                    // [GH#61/#83/#146] Same contract as content_block_stop.
                    send(LLMStreamChunk.ToolInputDelta(currentToolId!!, rawArgs))
                    com.openminis.app.logging.AppLogger.warning(
                        "AnthropicProvider",
                        "tool drain id=$currentToolId name=$currentToolName carried invalid " +
                            "argsLen=${rawArgs.length} — carrying raw tail for repair",
                    )
                }
                send(LLMStreamChunk.ToolCallComplete(
                    currentToolId!!, currentToolName!!, parsed ?: JSONObject(),
                    rawArgs = rawArgs.takeIf { parsed == null && it.isNotBlank() },
                ))
                currentToolId = null
                currentToolName = null
                toolInputBuffer.clear()
            }
        } catch (e: Exception) {
            // [T-android-stop-cancels-socket] Stop is a normal exit — rethrow.
            if (e is kotlinx.coroutines.CancellationException) throw e
            cancel("Stream error", mapError(e))
        } finally {
            drain.close()
            runCatching { reader.close() }
            runCatching { response.close() }
        }
        channel.close()
        awaitClose {
            call.cancel()
            runCatching { response.close() }
        }
        } finally {
            cancelOnExit?.dispose()
            call.cancel()
        }
    }

    /**
     * Build the `system` field as a JSON array of content blocks.
     * Mirrors iOS AnthropicProvider.resolveSystemPrompt — two authentication paths:
     *
     * - **OAuth (isOAuth=true)**: must start with the Claude Code prefix block (uncached)
     *   so Anthropic's server-side OAuth check sees the exact expected prompt.
     * - **API key (isOAuth=false)**: no prefix requirement.
     *
     * Returns null when the prompt is null, or empty on the API-key path
     * (iOS parity — no empty `system` field).
     *
     * Breakpoint placement: [systemStablePrefixLen] is the offset up to which
     * the assembled prompt is byte-stable across turns (published by
     * ChatViewModel.buildSystemPrompt). The cache_control breakpoint goes at
     * the end of THAT block, not at the end of the whole prompt: the tail
     * carries per-turn WorldBook hits, scene-classified learned prefs, FTS
     * recall and the runtime context, so a breakpoint after it rewrote the
     * cached prefix on every turn — paid for on write, never read back.
     * With the split, the stable head (identity, tool docs, memory, skills,
     * MCP) hits across turns and only the small tail is re-ingested.
     *
     * Breakpoint budget: this contributes at most ONE cached system block;
     * with the last tool and the last two user messages that is exactly the
     * four breakpoints Anthropic honors.
     */
    internal fun resolveSystemPrompt(userPrompt: String?, systemStablePrefixLen: Int = -1): JSONArray? {
        if (userPrompt == null) return null
        if (!isOAuth && userPrompt.isEmpty()) return null
        val prefix = if (isOAuth) oauthIdentifierPromptProvider() else ""
        val arr = JSONArray()
        for (block in systemCacheBlocks(userPrompt, systemStablePrefixLen, isOAuth, prefix)) {
            arr.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", block.text)
                    if (block.cached) put("cache_control", ephemeralCacheControl())
                },
            )
        }
        return arr
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        // [T-android-thinking-level-arch] Already clamped to the model ceiling by
        // LLMProvider.streamMessage/sendMessage before reaching here.
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        systemStablePrefixLen: Int = -1,
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model.id)
        body.put("max_tokens", maxTokens)
        body.put("stream", stream)

        SamplingPolicy.wire(
            SamplingIdentity.of(this), model.id, temperature, thinkingLevel.isEnabled,
        )?.let { body.put("temperature", it) }

        // Thinking shape selection lives in ThinkingRuleResolver.anthropicThinkingShape;
        // emission stays here for the companions it does not model (pinned by
        // ThinkingWireGeminiAnthropicSnapshotTest).
        val thinkShape = ThinkingRuleResolver.anthropicThinkingShape(
            model.id,
            if (forceThinkingEnabled) true else model.supportsReasoning, // [OpenMinis#311]
            thinkingLevel,
            maxTokens,
        )
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] provider=anthropic model=${model.id} level=${thinkingLevel.name} " +
                "shape=[${thinkShape.keys.sorted().joinToString(",")}]",
        )
        if (thinkingLevel.isEnabled) {
            if (modelUsesAdaptiveThinking(model.id)) {
                // [T-anthropic-thinking-display] Explicitly request summarized
                // thinking — the SECOND half of the "thinking on but no thoughts"
                // fix (first half: dropping the redact-thinking beta, f24e3f07).
                // "summarized" returns readable text; "omitted" (the default on
                // Opus 4.7+/Fable-tier) leaves the block empty but for a signature.
                // Pin it regardless of the model default.
                body.put("thinking", JSONObject().apply {
                    put("type", "adaptive")
                    put("display", "summarized")
                })
                body.put("output_config", JSONObject().apply {
                    put("effort", thinkingEffort(thinkingLevel))
                })
            } else {
                val budgetTokens = thinkingBudget(maxTokens, thinkingLevel)
                if (budgetTokens > 0) {
                    body.put("thinking", JSONObject().apply {
                        put("type", "enabled")
                        put("budget_tokens", budgetTokens)
                    })
                    if (!modelRejectsTemperature(model.id)) {
                        body.put("temperature", 1)
                    }
                }
            }
        } else {
            // Thinking OFF. [T-claude5-thinking-off] The literal is per model
            // (offThinking): omitting the field is NOT off on Claude 5 — it is
            // adaptive thinking at default effort, so OFF callers (titles, voice
            // correction) used to think at full effort (iOS ef86ed890). 4.6-4.x
            // and Sonnet 5 take `disabled`; <=4.5 are off by default — omit.
            when (offThinking(model.id)) {
                OffThinking.OMIT -> Unit
                OffThinking.DISABLED ->
                    body.put("thinking", JSONObject().put("type", "disabled"))
                OffThinking.BETWEEN_TOOLS ->
                    body.put("thinking", JSONObject().put("type", "between_tools"))
                OffThinking.LOW_EFFORT -> {
                    // Thinking cannot be switched off on this model: ask for as
                    // little as it allows, in the same shape as the enabled path.
                    body.put("thinking", JSONObject().apply {
                        put("type", "adaptive")
                        put("display", "summarized")
                    })
                    body.put("output_config", JSONObject().put("effort", "low"))
                }
            }
        }

        // System prompt blocks — mirrors iOS AnthropicProvider.resolveSystemPrompt.
        val systemArray = resolveSystemPrompt(systemPrompt, systemStablePrefixLen)
        if (systemArray != null) {
            body.put("system", systemArray)
        }

        // Tools: eager_input_streaming on every tool (GA, no beta header) so
        // partial tool input JSON streams — mirrors iOS
        // injectEagerInputStreaming. cache_control goes on the *last* tool,
        // matching the first of Anthropic's four prompt-cache breakpoints.
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for ((i, tool) in tools.withIndex()) {
                val toolJson = tool.toAnthropicJson()
                toolJson.put("eager_input_streaming", true)
                if (i == tools.lastIndex) {
                    toolJson.put("cache_control", ephemeralCacheControl())
                }
                toolsArray.put(toolJson)
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", JSONObject().put("type", "auto"))
        }

        // Build and merge messages
        val rawMessages = buildMessages(messages, imageParts)
        val merged = mergeConsecutiveSameRole(rawMessages)

        // Inject cache_control on last 2 user messages
        injectMessageCacheControl(merged)

        body.put("messages", merged)

        return body
    }

    /**
     * Drop `tool_result` parts whose `tool_use_id` does not match a `tool_use`
     * in the most recent assistant message. Anthropic rejects orphan
     * tool_results with 400 (`unexpected tool_use_id ... no corresponding
     * tool_use block`). Operates on a copy so the caller's stored history
     * (DB rows / in-memory state) is untouched — only the outbound payload
     * is sanitized. Mirrors iOS AnthropicAgentProvider.stripOrphanToolResults.
     */
    private fun stripOrphanToolResults(messages: List<LLMMessage>): List<LLMMessage> {
        var liveToolUseIds: Set<String> = emptySet()
        val result = ArrayList<LLMMessage>(messages.size)
        for ((i, msg) in messages.withIndex()) {
            when (msg.role) {
                LLMMessage.Role.ASSISTANT -> {
                    liveToolUseIds = msg.contentParts
                        .filterIsInstance<AgentContentPart.ToolUse>()
                        .map { it.id }
                        .toSet()
                    result.add(msg)
                }
                LLMMessage.Role.USER -> {
                    val original = msg.contentParts
                    if (original.isEmpty()) {
                        result.add(msg)
                    } else {
                        val kept = original.filter { part ->
                            if (part is AgentContentPart.ToolResult) {
                                liveToolUseIds.contains(part.id)
                            } else true
                        }
                        if (kept.size != original.size) {
                            val dropped = original.size - kept.size
                            android.util.Log.i(
                                "AnthropicProvider",
                                "Stripped $dropped orphan tool_result block(s) from outbound payload (idx=$i)"
                            )
                            result.add(msg.copy(contentParts = kept))
                        } else {
                            result.add(msg)
                        }
                    }
                    // Clear so a later user turn can't match a stale assistant.
                    liveToolUseIds = emptySet()
                }
            }
        }
        // Drop user messages that became empty (only orphan tool_results).
        // An empty content array is itself a 400.
        return result.filter { m ->
            // Keep messages that still have either non-empty contentParts or a
            // non-empty `content` string (string-only messages never had parts
            // to strip in the first place).
            m.contentParts.isNotEmpty() || m.content.isNotEmpty()
        }
    }

    /**
     * [T-android-anthropic-thinking-echo] (issue #70) Whether to echo prior
     * assistant `thinking` content blocks back into the history.
     *
     * Anthropic-compat proxies that speak the Anthropic protocol but reason
     * with interleaved (unsigned) thinking — DeepSeek V4 / GLM-4.5+ / Kimi
     * K2.5+ / Nemotron etc. — reject a multi-turn tool-use history where a
     * previous assistant turn produced thinking but that block isn't passed
     * back: 400 `content[].thinking in the thinking mode must be passed back
     * to the API`. The captured reasoning already lives on
     * `LLMMessage.reasoningContent`; we replay it as a synthesized
     * `{"type":"thinking","thinking":…}` block (no `signature` — we never
     * capture one off the wire).
     *
     * Mirrors iOS AnthropicAgentProvider's `echoReasoning` gate exactly:
     *   echo = supportsReasoning != false        (model may reason)
     *          AND interleavedReasoningField != null  (needs + tolerates
     *              UNSIGNED interleaved echo)
     *          AND !official Anthropic endpoint.
     * The `interleavedReasoningField` gate is load-bearing: the real
     * api.anthropic.com and strict Claude-class proxies verify the
     * `signature` field and reject an unsigned thinking block with
     * `thinking.signature: invalid_request_error`, so we must NOT echo there
     * even when supportsReasoning=true. Independent of the user's thinking
     * toggle — DeepSeek V4 emits thinking even when we don't request it.
     */
    private fun shouldEchoInterleavedThinking(): Boolean {
        val modelMayReason = model.supportsReasoning != false
        val modelRequiresInterleave = model.interleavedReasoningField != null
        return modelMayReason && modelRequiresInterleave && isCustomEndpoint
    }

    /** Build the messages JSONArray from LLMMessage list. */
    private fun buildMessages(
        rawMessages: List<LLMMessage>,
        imageParts: List<LLMMessage.ImagePart>,
    ): JSONArray {
        // Sanitize orphan tool_results before serialization. Operates on a
        // copy — caller's stored history is untouched.
        val messages = stripOrphanToolResults(rawMessages)
        // [T-android-anthropic-thinking-echo] (issue #70) Compute once — the
        // gate depends only on the model + endpoint, not the message.
        val echoThinking = shouldEchoInterleavedThinking()
        val messagesArray = JSONArray()
        for ((index, msg) in messages.withIndex()) {
            val obj = JSONObject()
            obj.put("role", msg.role.value)

            if (msg.contentParts.isNotEmpty()) {
                val contentArray = JSONArray()
                // [T-android-anthropic-thinking-echo] (issue #70) Prepend the
                // synthesized thinking block FIRST — Anthropic requires it to lead
                // the assistant turn. Replay captured reasoning verbatim; fall
                // back to an empty string as a field-presence placeholder for
                // turns whose reasoning was never captured (interrupted stream,
                // pre-thinking history, cross-provider session) — without it the
                // compat proxy still 400s. Mirrors iOS
                // injectThinkingBlocksForCompatProxy (unsigned, no signature).
                if (echoThinking && msg.role == LLMMessage.Role.ASSISTANT) {
                    contentArray.put(JSONObject().apply {
                        put("type", "thinking")
                        put("thinking", msg.reasoningContent ?: "")
                    })
                }
                for (part in msg.contentParts) {
                    when (part) {
                        is AgentContentPart.Text -> {
                            contentArray.put(JSONObject().apply {
                                put("type", "text")
                                put("text", part.text)
                            })
                        }
                        is AgentContentPart.ToolUse -> {
                            // Anthropic requires tool ids to match ^[a-zA-Z0-9_-]+$.
                            // OpenAI Responses can emit ids like `call_abc|fc_def`
                            // (pipe), so echo every id through sanitizeToolId.
                            contentArray.put(JSONObject().apply {
                                put("type", "tool_use")
                                put("id", sanitizeToolId(part.id))
                                put("name", part.name)
                                put("input", part.input)
                            })
                        }
                        is AgentContentPart.ToolResult -> {
                            val resultContent = JSONArray()
                            resultContent.put(JSONObject().apply {
                                put("type", "text")
                                put("text", part.content)
                            })
                            val imageData = part.imageData
                            val imageMimeType = part.imageMimeType
                            if (imageData != null && imageMimeType != null) {
                                // T-imgsize: belt-and-braces — history tool-result
                                // screenshots can be raw 12-megapixel PNGs that
                                // skipped the composer budget; re-encode in-place
                                // so replay doesn't 413.
                                val safeBytes = ImageBudget.compressUnderBudget(imageData)
                                val safeMime = if (safeBytes === imageData) imageMimeType else "image/jpeg"
                                resultContent.put(JSONObject().apply {
                                    put("type", "image")
                                    put("source", JSONObject().apply {
                                        put("type", "base64")
                                        put("media_type", safeMime)
                                        put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                                    })
                                })
                            }
                            contentArray.put(JSONObject().apply {
                                put("type", "tool_result")
                                put("tool_use_id", sanitizeToolId(part.id))
                                put("content", resultContent)
                                if (part.isError) put("is_error", true)
                            })
                        }
                        is AgentContentPart.ImageData -> {
                            // T-imgsize: provider-boundary backstop for history
                            // image bytes that bypassed the composer budget
                            // (restored sessions, retry-after-edit, agent-emitted
                            // ImageData). Composer already capped fresh sends.
                            val safeBytes = ImageBudget.compressUnderBudget(part.data)
                            val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                            contentArray.put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", safeMime)
                                    put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                                })
                            })
                        }
                    }
                }
                obj.put("content", contentArray)
            } else {
                val isLastUser = msg.role == LLMMessage.Role.USER &&
                    index == messages.indexOfLast { it.role == LLMMessage.Role.USER }
                if (isLastUser && imageParts.isNotEmpty()) {
                    val contentArray = JSONArray()
                    for (part in imageParts) {
                        // T-imgsize: backstop for the legacy ImagePart path
                        // (contentParts empty — older shape / session restore).
                        // Composer already runs ImageBudget; this arm does not.
                        val safeBytes = ImageBudget.compressUnderBudget(part.data)
                        val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                        contentArray.put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", safeMime)
                                put("data", Base64.encodeToString(safeBytes, Base64.NO_WRAP))
                            })
                        })
                    }
                    contentArray.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                    obj.put("content", contentArray)
                } else if (echoThinking && msg.role == LLMMessage.Role.ASSISTANT) {
                    // [T-android-anthropic-thinking-echo] (issue #70) Assistant
                    // turn with no structured parts (plain-text reply, or history
                    // rehydrated without contentParts) still carries its thinking
                    // block: materialize the string into an array, thinking first.
                    val contentArray = JSONArray()
                    contentArray.put(JSONObject().apply {
                        put("type", "thinking")
                        put("thinking", msg.reasoningContent ?: "")
                    })
                    if (msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    obj.put("content", contentArray)
                } else {
                    obj.put("content", msg.content)
                }
            }

            messagesArray.put(obj)
        }
        return messagesArray
    }

    /**
     * Merge consecutive messages with the same role.
     * Anthropic API requires alternating user/assistant messages.
     * For assistant messages, reorder: text/image first, then tool_use (Anthropic
     * rejects text appearing after tool_use in the same message).
     */
    private fun mergeConsecutiveSameRole(messages: JSONArray): JSONArray {
        if (messages.length() <= 1) return messages
        val result = JSONArray()
        var i = 0
        while (i < messages.length()) {
            val current = messages.getJSONObject(i)
            val role = current.getString("role")
            // Collect all consecutive messages with same role
            val contentBlocks = mutableListOf<JSONObject>()
            collectContentBlocks(current, contentBlocks)
            var j = i + 1
            while (j < messages.length() && messages.getJSONObject(j).getString("role") == role) {
                collectContentBlocks(messages.getJSONObject(j), contentBlocks)
                j++
            }
            if (j > i + 1 || contentBlocks.isNotEmpty()) {
                // Reorder assistant content: thinking first, then text/image,
                // then tool_use last. Anthropic rejects text before thinking and
                // text after tool_use in one turn; when several assistant messages
                // merge (issue #70) this hoists every synthesized thinking block
                // ahead of the text so the merged turn still leads with thinking.
                if (role == "assistant") {
                    val thinking = contentBlocks.filter { it.optString("type") == "thinking" }
                    val other = contentBlocks.filter {
                        val t = it.optString("type")
                        t != "thinking" && t != "tool_use"
                    }
                    val toolUse = contentBlocks.filter { it.optString("type") == "tool_use" }
                    contentBlocks.clear()
                    contentBlocks.addAll(thinking)
                    contentBlocks.addAll(other)
                    contentBlocks.addAll(toolUse)
                }
                val merged = JSONObject().apply {
                    put("role", role)
                    put("content", JSONArray(contentBlocks.map { it }))
                }
                result.put(merged)
            } else {
                result.put(current)
            }
            i = j
        }
        return result
    }

    /** Extract content blocks from a message into a flat list. */
    private fun collectContentBlocks(msg: JSONObject, out: MutableList<JSONObject>) {
        val content = msg.opt("content")
        when (content) {
            is JSONArray -> {
                for (k in 0 until content.length()) {
                    out.add(content.getJSONObject(k))
                }
            }
            is String -> {
                out.add(JSONObject().apply {
                    put("type", "text")
                    put("text", content)
                })
            }
        }
    }

    /** Inject cache_control on the last 2 user messages' last content block. */
    private fun injectMessageCacheControl(messages: JSONArray) {
        var userCount = 0
        for (i in messages.length() - 1 downTo 0) {
            val msg = messages.getJSONObject(i)
            if (msg.getString("role") != "user") continue
            userCount++
            if (userCount > 2) break
            val content = msg.opt("content")
            if (content is JSONArray && content.length() > 0) {
                val lastBlock = content.getJSONObject(content.length() - 1)
                lastBlock.put("cache_control", ephemeralCacheControl())
            }
        }
    }

    companion object {
        /** One `system` content block, plus whether it carries a cache breakpoint. */
        internal data class SystemBlock(val text: String, val cached: Boolean)

        /**
         * Split an assembled system prompt into cache blocks. Pure, so the
         * breakpoint placement is testable without a provider instance.
         *
         * [stablePrefixLen] is the offset before which the prompt is byte-stable
         * across turns; everything at or after it varies per turn. Values outside
         * `1 until length` mean "no split known" and reproduce the previous
         * single-cached-block shape, so callers that do not track the boundary
         * (title generation, compaction, sub-agents) behave exactly as before.
         */
        internal fun systemCacheBlocks(
            userPrompt: String,
            stablePrefixLen: Int,
            isOAuth: Boolean,
            claudeCodePrefix: String,
        ): List<SystemBlock> {
            val split = stablePrefixLen in 1 until userPrompt.length
            val stable = if (split) userPrompt.substring(0, stablePrefixLen) else userPrompt
            val dynamic = if (split) userPrompt.substring(stablePrefixLen).trimStart('\n') else ""
            val blocks = mutableListOf<SystemBlock>()
            if (isOAuth) {
                // The OAuth gate compares this prefix verbatim: first block, and
                // never carrying a breakpoint of its own.
                blocks += SystemBlock(claudeCodePrefix, cached = false)
                val rest =
                    if (stable.startsWith(claudeCodePrefix)) {
                        stable.removePrefix(claudeCodePrefix).trimStart('\n')
                    } else {
                        stable
                    }
                if (rest.isNotEmpty()) blocks += SystemBlock(rest, cached = true)
            } else if (stable.isNotEmpty()) {
                blocks += SystemBlock(stable, cached = true)
            }
            if (dynamic.isNotEmpty()) blocks += SystemBlock(dynamic, cached = false)
            return blocks
        }
        /**
         * Parse the `-<major>-<minor>` (or `/<major>.<minor>`) version out of a Claude id.
         * [T-anthropic-temp-claude5-android] The minor segment is OPTIONAL and
         * defaults to 0: single-segment ids (claude-fable-5, claude-opus-5,
         * future claude-*-6) parse as (major, 0). Previously they returned
         * null, so modelRejectsTemperature said false and the API answered
         * 400 "temperature is deprecated for this model"; the same null also
         * blinded modelUsesAdaptiveThinking and supportsThinking for the
         * whole 5-series. The optional group is greedy, so a real pair like
         * claude-3-5-sonnet still wins as (3,5) — never (3,0).
         * Returns null for non-Claude ids or ids without any version digits.
         */
        private fun parseClaudeVersion(modelId: String): Pair<Int, Int>? {
            val lower = modelId.lowercase()
            if (!lower.contains("claude")) return null
            val regex = Regex("""[-/]?(\d+)(?:[-.](\d+))?(?:$|[^0-9])""")
            val match = regex.find(lower) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: 0
            return major to minor
        }

        /**
         * Claude models from 4.6 onward reject the `temperature` parameter.
         * Returns true when the model id parses as Claude version >= 4.6.
         */
        fun modelRejectsTemperature(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major > 4 || (major == 4 && minor >= 6)
        }

        /**
         * Claude 4.6 and later use *adaptive* thinking (`thinking.type="adaptive"` plus
         * `output_config.effort = low|medium|high|xhigh|max`) and silently ignore the
         * older `thinking.type="enabled" + budget_tokens` form. Older Claude models still
         * need the legacy budget-based form.
         */
        fun modelUsesAdaptiveThinking(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major > 4 || (major == 4 && minor >= 6)
        }

        /** How "thinking off" goes on the wire; see [Companion.offThinking]. */
        enum class OffThinking { OMIT, DISABLED, BETWEEN_TOOLS, LOW_EFFORT }

        /**
         * [port-upstream-1.16][T-claude5-thinking-off] How "thinking off" is
         * encoded on the wire for this model. Port of iOS
         * `AnthropicProvider.offThinking(for:)` (ef86ed890) — the vendor's
         * per-model contract: `disabled` 400s on Sonnet 5.5 / Opus 5.5+, an
         * absent field means adaptive thinking at DEFAULT effort on Claude 5,
         * and Opus 5+ takes low effort instead of `disabled` (the vendor warns
         * disabled leaks tool calls into text on tool-heavy workloads). An
         * unknown future 5+ model takes LOW_EFFORT too: every adaptive model
         * accepts adaptive thinking, while `disabled` / `between_tools` are
         * per-model and 400 where unsupported.
         */
        fun offThinking(modelId: String): OffThinking {
            val (major, minor) = parseClaudeVersion(modelId) ?: return OffThinking.OMIT
            if (major < 4 || (major == 4 && minor < 6)) return OffThinking.OMIT
            if (major == 4) return OffThinking.DISABLED
            if (major == 5 && modelId.lowercase().contains("sonnet")) {
                if (minor == 0) return OffThinking.DISABLED
                if (minor == 5) return OffThinking.BETWEEN_TOOLS
            }
            return OffThinking.LOW_EFFORT
        }

        /**
         * [T-android-claude-opus48-thinking-toggle] (Sow Sow 38845/38850) True
         * when this Claude model supports extended thinking — i.e. the Deep
         * Thinking toggle should appear for it. Anthropic added extended thinking
         * with Claude 3.7; every 4.x model (opus / sonnet / haiku) supports it.
         * Used to stamp `supportsReasoning = true` on models fetched from
         * `/v1/models` (which returns no capability metadata), so the toggle shows
         * on a direct-Anthropic instance even when models.dev hasn't catalogued
         * the model yet (e.g. brand-new Opus 4.8). The provider already builds the
         * correct `thinking` request for any such model (see
         * modelUsesAdaptiveThinking / the legacy budget path), so this is purely
         * the UI-capability flag those request paths assumed.
         */
        fun supportsThinking(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major > 4 || major == 4 || (major == 3 && minor >= 7)
        }

        /**
         * Calculate thinking budget tokens based on level (legacy <=4.5 protocol).
         *
         * [T-thinking-ladder-shared] Replaces a hardcoded absolute ladder
         * (OFF 0 / LOW 8192 / MEDIUM 32768 / HIGH min(mt,65536) / XHIGH·MAX·ULTRA mt)
         * that collapsed HIGH through ULTRA onto ONE number for every
         * maxTokens <= 65536 — measured BEFORE the change:
         *   maxTokens   8192 → 8191/8191/8191/8191/8191 (LOW..ULTRA, five identical)
         *   maxTokens  16384 → 8192/16383/16383/16383/16383
         *   maxTokens  32768 → 8192/32767/32767/32767/32767
         *   maxTokens  65536 → 8192/32768/65535/65535/65535
         *   maxTokens 131072 → 8192/32768/65536/131071/131071
         * A user dragging High → Ultra changed nothing on the wire.
         *
         * WHY `maxTokens` IS THE CORRECT CAPACITY HERE, unlike the other sites:
         * `budget_tokens` is inherently REQUEST-scoped — Anthropic validates it
         * against the `max_tokens` of the very request that carries it, and a budget
         * taken from a model-wide ceiling would be rejected as `budget_tokens must
         * be less than max_tokens` the moment the two diverge. The Zen lane can
         * scale against `LLMModel.maxOutputTokens` because its knob bounds the whole
         * response; this one cannot, so the request's own budget is the capacity.
         *
         * The OFF -> 0 mapping is exact and preserved (the caller only reaches this
         * for enabled levels, but anthropicThinkingShape and the tests do not, and
         * OFF must never emit a budget).
         */
        fun thinkingBudget(maxTokens: Int, level: ThinkingLevel): Int {
            if (level == ThinkingLevel.OFF) return 0
            // [T-thinking-ladder-shared] Non-positive maxTokens cannot host any
            // budget (Anthropic 400s on budget_tokens >= max_tokens; 0 is the
            // vendor default) — the VALUE is unchanged. `budgetFor` coerces into
            // `[FLOOR, capacity-1]` before its own degenerate branch, so a capacity
            // at or below FLOOR (512) throws: a budget under the floor cannot be
            // expressed, so emit none — the caller's `> 0` gate leaves it off.
            if (maxTokens <= ThinkingLadder.FLOOR) return 0
            val cap = ThinkingLadder.budgetFor(level, maxTokens)
            // Anthropic requires budget_tokens STRICTLY LESS THAN max_tokens; an
            // equal value is a 400. ThinkingLadder already returns a value <
            // maxTokens (top rung is 15/16 of capacity), so this pullback is a
            // belt-and-braces assertion of the API's own invariant — kept so a
            // future ladder change cannot trip a 400.
            val clamped = minOf(cap, maxTokens)
            return if (clamped >= maxTokens) maxTokens - 1 else clamped
        }

        /**
         * Map ThinkingLevel onto Anthropic adaptive thinking effort strings (Claude 4.6+).
         * `xhigh` maps to "max" — both 4.6 and 4.7 advertise "max" universally; 4.7 also
         * lists "xhigh" but we standardize on "max" for cross-version compatibility.
         */
        fun thinkingEffort(level: ThinkingLevel): String = when (level) {
            ThinkingLevel.OFF -> "low"  // unreached: caller checks isEnabled
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            // [T-android-thinking-level-arch] Anthropic tops out at "max"; XHIGH
            // and the new MAX/ULTRA tiers all collapse onto it.
            ThinkingLevel.XHIGH,
            ThinkingLevel.MAX,
            ThinkingLevel.ULTRA -> "max"
        }
    }

    /**
     * T302: takes the pre-serialized body string AND the JSONObject. The
     * string is used directly for the OkHttp RequestBody so we don't pay
     * for a second `body.toString()` (each call is tens of MB on long
     * agent loops). The JSONObject is still needed for header logic that
     * inspects fields like `thinking`. See OpenAIProvider.buildRequest
     * for the same pattern.
     */
    private fun buildRequest(bodyStr: String, body: JSONObject): Request {
        // T192: `basePath` may already end in `/v1` (effectiveBaseURL appends it
        // when `appendV1Suffix=true`); without stripping we'd produce
        // `…/v1/v1/messages` on Anthropic-compatible endpoints (DeepSeek,
        // Bailian). Mirrors iOS `AnthropicProvider.swift::stripV1Suffix`.
        val cleanBase = basePath.trimEnd('/').let {
            if (it.endsWith("/v1")) it.dropLast(3).trimEnd('/') else it
        }
        val builder = Request.Builder()
            .url("$cleanBase/v1/messages")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")

        // OkHttp forbids multiple values for the same header slot via `.header(...)`,
        // so build a combined anthropic-beta string and set it once. OAuth (Claude
        // Code) mimics the real CLI — the backend reads the *combination* of
        // anthropic-beta + UA + X-Stainless-* to decide CLI vs third-party and
        // downgrades the latter. API-key / custom endpoints carry only the betas
        // the request needs: oauth-2025-04-20 / claude-code-20250219 signal
        // Claude-Code-only surface and are rejected on plain API-key auth.
        val betaFlags = mutableListOf<String>()
        if (isOAuth) {
            // [T-anthropic-redact-thinking] Deliberately OMIT
            // "redact-thinking-2026-02-12": present, it redacts `thinking`
            // plaintext (empty thinking + signature only). The CLI adds it only
            // when `showThinkingSummaries` is unset/false (claude-code#31326);
            // omitting is equivalent to true — pure UI visibility. Other betas stay.
            betaFlags.addAll(listOf(
                "claude-code-20250219",
                "oauth-2025-04-20",
                "interleaved-thinking-2025-05-14",
                "prompt-caching-scope-2026-01-05",
                "effort-2025-11-24",
                "context-management-2025-06-27",
                "extended-cache-ttl-2025-04-11",
            ))
        } else if (body.has("thinking")) {
            val isAdaptive = body.optJSONObject("thinking")?.optString("type") == "adaptive"
            if (isAdaptive) {
                betaFlags.add("effort-2025-11-24")
            } else {
                betaFlags.add("interleaved-thinking-2025-05-14")
            }
        }
        // [T-android-enhanced-cache] API-key path only carries the betas the
        // body needs; the 1-hour cache TTL requires this flag. OAuth already
        // lists it above, so guard on !isOAuth to avoid a duplicate token.
        if (enhancedCache && !isOAuth) {
            betaFlags.add("extended-cache-ttl-2025-04-11")
        }
        if (betaFlags.isNotEmpty()) {
            builder.header("anthropic-beta", betaFlags.joinToString(","))
        }

        // [GH#423][GH#301] Stainless / CLI fingerprint headers — only on OAuth.
        // Same definition as the OAuth auth path: one registered client
        // identity, one version source (ClaudeCliVersion), no second literal
        // to forget when the backend's model gate moves.
        if (isOAuth) {
            builder.applyClaudeCliMimicryHeaders()
        }

        if (isOAuth) {
            builder.header("Authorization", "Bearer $apiKey")
        } else if (apiKey.isEmpty()) {
            // [T-empty-key-compat-endpoints] Keyless third-party
            // Anthropic-compatible endpoint: send NO auth header rather than a
            // malformed `Bearer ` / empty x-api-key strict relays reject.
            // Custom-endpoint instances only — the official API is never keyless.
        } else if (isCustomEndpoint) {
            builder.header("Authorization", "Bearer $apiKey")
        } else {
            builder.header("x-api-key", apiKey)
        }

        // [T-provider-custom-user-agent] Applied last so a non-blank override
        // wins over the OAuth claude-cli UA above. null/blank → branded Minis UA
        // on the apiKey path, but on OAuth keep the claude-cli fingerprint: the
        // backend pairs UA + X-Stainless-* and rejects calls whose UA doesn't
        // match the registered client identity.
        builder.applyUserAgentOverride(
            customUserAgent,
            defaultUserAgent = if (isOAuth) null else com.openminis.app.provider.MinisUserAgent.DEFAULT,
        )
        return builder.build()
    }

    private fun parseResponse(json: JSONObject): LLMResponse {
        val content = json.optJSONArray("content")
        val text = buildString {
            if (content != null) {
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.safeOptString("type", "") == "text") {
                        append(block.safeOptString("text", ""))
                    }
                }
            }
        }
        val stopReason = json.safeOptString("stop_reason", "").ifEmpty { null }
        val usage = json.optJSONObject("usage")?.let { parseAnthropicUsage(it) }
        return LLMResponse(text, stopReason, usage)
    }


    /**
     * [T-anthropic-sse-error-event] Turn an SSE `error` event into the same
     * [LLMError] the HTTP path would have produced for the equivalent failure.
     *
     * An SSE error event carries no status code, so the classification input is
     * synthesized: the event's `error.type` maps onto the status Anthropic itself
     * returns for that condition (overloaded_error → 529, authentication_error →
     * 401, rate_limit_error → 429, …), and the ORIGINAL event object is handed to
     * [mapHttpError] as the body. That keeps one mapping source for both paths —
     * message extraction, [T-llm-error-secret-mask] masking, the
     * [T-llm-error-401-model-scope] credential-acceptance gate, the 429
     * Retry-After handling and the [T-llm-error-classification] context-filter /
     * content-filter splits all apply to stream errors exactly as they do to HTTP
     * ones. Unknown / absent types default to 500 (transient upstream trouble),
     * which is what an unclassified mid-stream refusal actually is.
     */
    private fun mapStreamErrorEvent(event: JSONObject): LLMError {
        val error = event.optJSONObject("error")
        val type = error?.safeOptString("type", "").orEmpty()
        val status = when (type) {
            "authentication_error" -> 401
            "permission_error", "billing_error" -> 403
            "rate_limit_error" -> 429
            "invalid_request_error", "not_supported_error" -> 400
            "not_found_error" -> 404
            "overloaded_error" -> 529
            "api_error", "server_error", "external_unauthorized_error" -> 500
            else -> 500
        }
        android.util.Log.w(
            "AnthropicProvider",
            "SSE error event (type=${type.ifEmpty { "unset" }}) → classified as HTTP $status",
        )
        return mapHttpError(status, event.toString())
    }

    internal fun mapHttpError(statusCode: Int, body: String, retryAfterHeader: String? = null): LLMError =
        mapHttpErrorShared(
            statusCode,
            body,
            retryAfterHeader,
            credentialGateKey = credentialGateKey,
            isContextLimit = ::anthropicContextLimit,
            isContentFiltered = ::anthropicContentFiltered,
            // [T-llm-error-secret-mask] The label and message are both
            // UPSTREAM-CONTROLLED and reach LLMError.message, which the chat UI
            // renders. Anthropic labels the envelope with the upstream
            // error.type (its primary error identity) instead of the status.
            messageLabel = { _, error -> "[${error?.safeOptString("type", "") ?: "error"}]" },
        )

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}
