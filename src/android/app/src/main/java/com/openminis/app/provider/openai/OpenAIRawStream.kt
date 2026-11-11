package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.FullBodyJson
import com.openminis.app.provider.NonFiniteJson
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.safeOptString
import com.openminis.app.provider.ssePayloads
import com.openminis.app.provider.cancelOnCancellation
import com.openminis.app.provider.StreamTailDrain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Response
import java.io.IOException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte
 * budget: response HEADERS must arrive within this window. Does NOT
 * bound the SSE body — a flowing stream stays unlimited.
 *
 * [T-android-ttfb-upload-split / #188] This window now starts at
 * `requestBodyEnd` (upload complete), NOT at call start — a large
 * multimodal body over a slow proxy could burn the whole budget just
 * uploading, so a healthy-but-slow server looked like a dead
 * connection. See [STREAM_UPLOAD_CAP_MS] for the upload-phase bound.
 *
 * Raised 30s -> 120s (user report, TG soyo): complex agent turns,
 * locally-hosted large models, and slow relay endpoints can legitimately
 * take well over 30s to emit the first response header, and the old
 * budget cancelled those healthy requests as false timeouts. readTimeout
 * is 600s, so 120s stays comfortably inside it while still catching a
 * genuinely dead connection.
 */
private const val STREAM_TTFB_TIMEOUT_MS = 120_000L

/**
 * [T-android-ttfb-upload-split / #188] Overall ceiling for the UPLOAD
 * phase (call start → requestBodyEnd). Keeps the watchdog effective if
 * the body upload itself wedges (writeTimeout is 30s per write op, but a
 * trickling proxy can dribble bytes forever without tripping it). Chosen
 * generous so a legitimately large body over a slow link isn't cut off:
 * the writeTimeout(30s) already bounds a fully-stalled socket; this only
 * catches the slow-but-never-idle case. Once upload completes the tighter
 * [STREAM_TTFB_TIMEOUT_MS] takes over.
 */
private const val STREAM_UPLOAD_CAP_MS = 120_000L

internal fun OpenAIProvider.rawStreamMessage(
        messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
        temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        stream: Boolean,
        // Per-call headers (e.g. x-opencode-session) merged LAST over the
        // instance defaults, so a conversation id rides the shared cached
        // provider without leaking between sessions.
        extraHeaders: Map<String, String> = emptyMap(),
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = if (isCodexImageModel) {
            // [T-gpt-image2-codex-backend-route-android] gpt-image-2 on an
            // OpenAI OAuth (Codex) instance goes through the Codex backend
            // image_generation tool (chatgpt.com/backend-api/codex/responses),
            // NOT the public Images API — that token lacks
            // api.model.images.request and the API 401s with "Missing scopes"
            // (codex_oauth_image_generation_summary.md §11.1; iOS 2dd35a14).
            // Route selected purely by isCodexImageModel — codexAccountId is
            // optional (its absence doesn't 401; the absence check was the iOS
            // fall-through bug). Android has no Images-API path at all, so an
            // OAuth gpt-image-2 request can never reach /v1/images/generations.
            //
            // Special image-generation body — not Chat Completions, not the
            // normal Responses tool shape.
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] gpt-image-2 → route=codex-backend " +
                    "url=chatgpt.com/backend-api/codex/responses isOAuth=$isOAuth " +
                    "hasAccountId=${codexAccountId != null}",
            )
            buildCodexImageBody(messages)
        } else if (usesChatCompletionsAPI) {
            buildRequestBody(messages, systemPrompt, maxTokens, stream = stream, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        } else {
            buildResponsesAPIBody(
                messages, systemPrompt, maxTokens, stream = stream,
                imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel,
                temperature = temperature,
            )
        }
        // T302: serialize the request body exactly once. Pre-T302 we called
        // body.toString() three times per request (debug log + OAuth byte
        // build + non-OAuth RequestBody), each materialising a fresh 30+ MB
        // string for long agent loops with heavy tool outputs. Stacked, that
        // pushed memory-tight devices (HONOR PTP-AN00) past the OOM line.
        // [T-android-mem-probe-trust] Bracket the serialisation itself. The
        // 2026-08-15 field log recorded `bodyLen=2342987` on the request before
        // a process death but nothing about its memory cost, so "did building
        // this body kill us?" could not be answered from the log. We measure
        // around the call and report the realised length, so an OOM thrown here
        // now arrives with an attributed stack instead of anonymously.
        val memBefore = com.openminis.app.diagnostics.MemorySnapshot.capture()
        val serStartNs = System.nanoTime()
        val bodyStr = try {
            body.toString()
        } catch (t: Throwable) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", -1, "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = t,
            )
            throw t
        }
        if (bodyStr.length >= com.openminis.app.diagnostics.LargeAllocProbe.NOTABLE_BYTES) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", bodyStr.length.toLong(),
                "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = null,
            )
        }
        // Per-call headers (x-opencode-session) merge LAST, per call.
        val request = extraHeaders.entries.fold(buildRequest(bodyStr)) { acc, (n, v) ->
            acc.newBuilder().header(n, v).build()
        }
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }
        val startTime = System.currentTimeMillis()

        // T321: request-side diagnostic log. Header *keys* + Authorization
        // presence (no token values), and a body summary (counts only — never
        // the message text/images/tool-result bytes).
        run {
            val authPresent = request.headers["Authorization"] != null
            val msgsLen = body.optJSONArray("messages")?.length()
                ?: body.optJSONArray("input")?.length() ?: 0
            val toolsLen = body.optJSONArray("tools")?.length() ?: 0
            val temp = if (body.has("temperature")) body.optDouble("temperature") else null
            val maxTok = body.optInt("max_completion_tokens", body.optInt("max_tokens", -1))
            val hasSystem = body.has("instructions") ||
                (body.optJSONArray("messages")?.let { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.optJSONObject(i)?.optString("role") == "system") { found = true; break }
                    }
                    found
                } ?: false)
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] → REQ url=${request.url} model=${model.id} stream=${body.optBoolean("stream", false)} " +
                    "headerKeys=${request.headers.names()} authPresent=$authPresent " +
                    "messages=$msgsLen tools=$toolsLen temp=$temp maxTokens=$maxTok hasSystem=$hasSystem " +
                    "useResponsesAPI=${!usesChatCompletionsAPI} bodyLen=${bodyStr.length}"
            )
        }

        // [T-android-ttfb-upload-split / #188] Attach per-call state the trace
        // listener fills in (upload-done timestamp + physical connection) so the
        // watchdog below can (a) start the TTFB clock only after upload and
        // (b) evict THIS ONE connection on timeout.
        val watchState = CallWatchState()
        val call = client.newCall(request.newBuilder().tag(CallWatchState::class.java, watchState).build())
        // Cancel at cancellation onset: completion waits for blocking reads.
        val stopHook = call.cancelOnCancellation(
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job],
        )
        try {
        // [T-android-stale-conn-retry-hang] Time-to-first-byte watchdog: a
        // request written into a dead pooled h2 tunnel produces NO further
        // events until the 600s read timeout — the UI showed "thinking" forever.
        // [T-android-ttfb-upload-split / #188] The window is split in two so
        // slow-but-healthy uploads aren't mistaken for a dead connection:
        //   1. UPLOAD phase (call start → requestBodyEnd): loose
        //      STREAM_UPLOAD_CAP_MS (writeTimeout(30s) catches a full stall).
        //   2. TTFB phase (requestBodyEnd → headers): tight
        //      STREAM_TTFB_TIMEOUT_MS, measured FROM upload completion.
        // On timeout: cancel + evict JUST this connection (no evictAll) so the
        // auto-retry gets a fresh one. After headers, a flowing SSE stream has
        // NO total-duration limit, as before.
        val ttfbTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val headersArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        val callStartNanos = System.nanoTime()
        val ttfbWatchdog = launch {
            val pollMs = 250L
            var timedOutPhase: String? = null
            while (!headersArrived.get()) {
                val uploadDoneAt = watchState.uploadDoneAtNanos.get()
                val nowNanos = System.nanoTime()
                if (uploadDoneAt == 0L) {
                    // Still uploading (or connecting) — loose upload-phase cap.
                    val elapsedMs = (nowNanos - callStartNanos) / 1_000_000L
                    if (elapsedMs >= STREAM_UPLOAD_CAP_MS) { timedOutPhase = "upload"; break }
                } else {
                    // Upload finished — tight TTFB budget measured from that point.
                    val sinceUploadMs = (nowNanos - uploadDoneAt) / 1_000_000L
                    if (sinceUploadMs >= STREAM_TTFB_TIMEOUT_MS) { timedOutPhase = "ttfb"; break }
                }
                delay(pollMs)
            }
            if (timedOutPhase != null && !headersArrived.get()) { // [T-android-ttfb-race] re-checked here
                ttfbTimedOut.set(true)
                val budgetS = if (timedOutPhase == "ttfb") STREAM_TTFB_TIMEOUT_MS / 1000 else STREAM_UPLOAD_CAP_MS / 1000
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-android-ttfb-upload-split] no response headers ($timedOutPhase phase, ${budgetS}s) — cancelling call + evicting connection (stale pooled connection?)",
                )
                call.cancel()
                // Targeted eviction: close ONLY this call's physical connection so
                // the retry can't reuse it. Never evictAll — concurrent sessions
                // may hold healthy connections in the shared pool.
                try {
                    watchState.connection.get()?.socket()?.close()
                } catch (_: Throwable) {
                    // Best-effort; call.cancel() already unblocks execute().
                }
            }
        }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (ttfbTimedOut.get()) {
                throw LLMError.TransientError(
                    "no response from server (${STREAM_TTFB_TIMEOUT_MS / 1000}s TTFB) — check network/proxy",
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            ttfbWatchdog.cancel()
        }
        // [port-upstream-1.16][T-quota-codex-subscription] Codex replies, errors
        // included: a 429 still reports usage.
        if (isOAuth && !forceChatCompletions) {
            com.openminis.app.quota.QuotaObservationSink.recordCodexResponse(quotaInstanceId, response.headers)
        }
        // T321: response-side diagnostic log (status + select header values).
        run {
            val rh = response.headers
            val ct = rh["content-type"] ?: ""
            val rid = rh["x-request-id"] ?: rh["openai-request-id"] ?: ""
            val openAiHdrs = rh.names().filter { it.lowercase().startsWith("openai-") }
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] ← RSP status=${response.code} content-type=$ct x-request-id=$rid " +
                    "headerKeys=${rh.names()} openAiHeaders=${openAiHdrs.associateWith { rh[it] ?: "" }}"
            )
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            // T321: full error body. [T-llm-error-secret-mask] masked at every
            // site now, and the FULL body is DEBUG-only: AppLogger writes into
            // filesDir/logs/*.log and CONTRIBUTING.md asks users to attach those
            // logs to GitHub issues, so an unmasked/unbounded upstream echo was a
            // credential spill path. Release builds keep a bounded masked excerpt
            // so "why did the relay refuse" is still triageable from a report.
            val maskedErrorBody = maskSecrets(errorBody)
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] ← HTTP ${response.code} error body: " +
                    (if (com.openminis.app.BuildConfig.DEBUG) maskedErrorBody else maskedErrorBody.take(500))
            )
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // not just to avoid the (already-truncated) retention cost, but to
            // dodge constructing the Entry / headerMap copies that go with it.
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "openai",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = System.currentTimeMillis() - startTime,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            throw mapHttpError(response.code, errorBody, response.header("Retry-After"))
        }
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "openai",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = System.currentTimeMillis() - startTime,
                    responseStatusCode = response.code,
                )
            )
        }

        // Some compatible gateways ignore `stream=true` and return regular
        // Chat Completions JSON. Parse it instead of feeding JSON to the SSE
        // parser and misclassifying the response as silently empty.
        //
        // [T-stream-content-type-sniff] The Content-Type header alone cannot
        // make that call. Relays fronted by a proxy that hides or rewrites the
        // header answer `application/json` while streaming perfectly good SSE
        // (`data: {…}` lines) — measured 6/6 on one public gateway. Trusting the
        // header sent that body into JSONObject(), whose tokenizer reads the
        // bare word `data` and dies with "Value data of type java.lang.String
        // cannot be converted to JSONObject", which escaped the agent loop as a
        // raw JSONException. So: sniff the body, and only take the JSON path
        // when the header AND the bytes both say JSON.
        //
        // The peeked bytes are pushed back, so the SSE path below still streams
        // incrementally instead of buffering the whole response.
        val responseContentType = response.header("Content-Type").orEmpty().lowercase()
        val pushback = response.body?.byteStream()
            ?.let { java.io.PushbackInputStream(it, SNIFF_BYTES) }
        val bodyLooksLikeSse = pushback?.let { looksLikeSse(peekPrefix(it)) } ?: false
        if (usesChatCompletionsAPI &&
            !responseContentType.contains("text/event-stream") &&
            !bodyLooksLikeSse
        ) {
            try {
                val raw = pushback?.reader()?.readText().orEmpty()
                // [T-nonstream-json-default] Parse moves to Dispatchers.Default;
                // FullBodyJson also wraps the raw JSONException (defense shared
                // with the Anthropic/Gemini non-streaming paths).
                val json = FullBodyJson.parse(
                    raw,
                    "chat-completions body (Content-Type: ${responseContentType.ifBlank { "absent" }})",
                )
                send(LLMStreamChunk.Started)
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                val message = choice?.optJSONObject("message")
                val text = message?.let { m -> m.safeOptString("content") }.orEmpty() // [T-json-null-string]
                if (text.isNotEmpty()) send(LLMStreamChunk.Text(text))
                json.optJSONObject("usage")?.let {
                    send(LLMStreamChunk.Usage(parseChatCompletionsUsage(it)))
                }
                send(LLMStreamChunk.Finished(choice?.let { c -> c.safeOptString("finish_reason") }))
            } finally {
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        if (pushback == null) {
            // [T-android-stream-resource] Close the opened response or it leaks.
            runCatching { response.close() }
            throw LLMError.DecodingError(
                IllegalStateException("streaming response had no body"),
            )
        }
        val reader = BufferedReader(InputStreamReader(pushback))

        // [T-codex-gpt-image2-oauth-android] gpt-image-2: the Codex backend
        // streams the image as a base64 blob (PNG / JPEG / WebP) inside the SSE
        // `image_generation_call` output item; there's no incremental text/tool
        // stream to parse. handleCodexImageStream parses the SSE line-by-line,
        // pulls the image (or a structured failure), emits a MediaAttachment
        // chunk, and finishes — bypassing the chat/tool SSE state machine below.
        if (isCodexImageModel) {
            try {
                handleCodexImageStream(reader) { chunk -> trySend(chunk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cancel("Image stream error", mapError(e))
            } finally {
                reader.close()
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        // Chat Completions: tool calls are streamed as deltas keyed by index.
        data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastSentArgsLen: Int = 0)
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        // Responses API: function_call items are keyed by their item_id (fc_…). We store
        // the call_id separately because the agent loop needs the call_id to correlate
        // tool results, but the next request must echo back the item_id verbatim — so we
        // emit a combined "callId|fcId" identifier that splitResponsesAPIIds() unpacks.
        data class ResponsesToolCallAccumulator(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastSentArgsLen: Int = 0)
        val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
        // One-shot info log the first time the Responses API streams a reasoning
        // delta — confirms the Thinking pipeline is wired up.
        var sawReasoningDelta = false
        // Opaque reasoning_content blob echoed back verbatim on the next turn:
        // DeepSeek V4 emits `reasoning_content: ""` legitimately and the empty
        // string must round-trip — fabricating placeholder text causes
        // in-context learning (see T249 / T257 history).
        val reasoningAccum = StringBuilder()
        var sawReasoningField = false
        // [T-android-think-prefix-stream] One parser per streaming turn, all
        // models: no `<think>` prefix passes through verbatim (no vendor
        // allowlist; MiniMax M3 relied on the mid-stream contains fallback).
        val thinkParser = ThinkPrefixStreamParser()

        // [GH#113] Some local / compatibility gateways stream a serialized tool
        // call through `delta.content` instead of `delta.tool_calls`. The
        // sniffer converts an envelope that names an ADVERTISED tool into a
        // real tool call and lets every other `{…}` through untouched; see
        // ContentToolCallSniffer for the false-positive guards.
        val contentSniffer = ContentToolCallSniffer(tools.mapTo(HashSet()) { it.name })
        var inlineToolCallSeq = 0
        suspend fun emitSniffed(emissions: List<ContentToolCallSniffer.Emission>) {
            for (em in emissions) {
                when (em) {
                    is ContentToolCallSniffer.Emission.Text ->
                        if (em.text.isNotEmpty()) send(LLMStreamChunk.Text(em.text))
                    is ContentToolCallSniffer.Emission.Call -> {
                        val id = "inline-tool-${++inlineToolCallSeq}"
                        if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) {
                            android.util.Log.d(
                                "ToolChain[Provider]",
                                "→ content-embedded tool call recovered name=${em.name}",
                            )
                        }
                        send(LLMStreamChunk.ToolUseStart(id, em.name))
                        send(LLMStreamChunk.ToolInputDelta(id, em.args.toString()))
                        send(LLMStreamChunk.ToolCallComplete(id, em.name, em.args))
                    }
                }
            }
        }

        // T321 counters + [T-android-stream-pace] first/last event clock —
        // streamMs in the tail tally separates a relay that buffered-then-burst
        // (long streamMs after the gateway finished) from client-side lag.
        var sseEventCount = 0
        var contentLen = 0
        var firstEventAtMs = 0L
        var lastEventAtMs = 0L
        // [T-android-incomplete-keep-partial] a text delta carried real content.
        var sawNonBlankText = false
        var reasoningLen = 0
        var toolCallEventCount = 0
        var sawFinishReason = false
        var sawUsageBlock = false
        // [T-android-responses-missing-finished] hoisted so the stream tail can
        // emit Finished when the relay closes without a `data: [DONE]`.
        var finishReason: String? = null
        // True once the [DONE] branch has emitted Finished.
        var sentFinished = false
        val drain = StreamTailDrain(this, { call.cancel() })

        try {
            send(LLMStreamChunk.Started)

            // Branch streaming parser based on API format
            val isResponsesAPI = !usesChatCompletionsAPI

            // Shared SSE assembler: an event ends at a blank line and may
            // carry multiple `data:` lines. It also tolerates `data:{...}`
            // with no space and preserves `[DONE]` verbatim.
            for (payload in ssePayloads(reader, drain)) {
                if (payload == "[DONE]") {
                    // [T-android-think-prefix-stream] Flush whatever the parser
                    // still holds (a cross-chunk tag tail, or an unterminated
                    // <think>). Idempotent, so the finish_reason path may also
                    // call it. Withheld trailing whitespace is dropped by design.
                    thinkParser.finishTurn().let { fin ->
                        if (fin.thinking.isNotEmpty()) {
                            reasoningAccum.append(fin.thinking)
                            send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                        }
                        if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                    }
                    // [GH#113] A sniffer buffer still open at [DONE] was not a
                    // tool call after all — release it as text before finishing.
                    emitSniffed(contentSniffer.finish())
                    // [T-android-think-prefix-stream] Persist reasoning captured
                    // EITHER from the `reasoning_content` field or from a
                    // `<think>` prefix. Gating solely on sawReasoningField would
                    // stream think-tag reasoning live and then drop it — the
                    // thinking bubble would vanish on session reload.
                    if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                        send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                    }
                    send(LLMStreamChunk.Finished(finishReason))
                    sentFinished = true
                    break
                }

                val event = try { JSONObject(payload) } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] SSE JSON parse failed: ${e.message} payload=${maskSecrets(payload.take(300))}"
                    )
                    continue
                }
                if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) {
                    android.util.Log.d(
                        "ToolChain[Provider]",
                        "RAW SSE: ${com.openminis.app.text.BoundedText.clampSsePayload(payload)}",
                    )
                }
                sseEventCount++
                val nowMs = System.currentTimeMillis()
                if (firstEventAtMs == 0L) firstEventAtMs = nowMs
                lastEventAtMs = nowMs

                // T321: per-event delta-field summary. Only counts/lengths,
                // never the actual delta text — keeps log volume bounded.
                run {
                    val ev = event
                    val delta = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val type = ev.optString("type", "")
                    if (delta != null) {
                        val cLen = delta.optString("content", "").length
                        val rcLen = delta.optString("reasoning_content", "").length
                        val rLen = delta.optString("reasoning", "").length
                        val tcLen = delta.optJSONArray("tool_calls")?.length() ?: 0
                        val role = delta.optString("role", "")
                        if ((cLen + rcLen + rLen + tcLen > 0 || delta.has("role")) &&
                            android.util.Log.isLoggable("OpenAIProvider", android.util.Log.VERBOSE)
                        ) {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE delta: contentLen=$cLen rcLen=$rcLen rLen=$rLen toolCalls=$tcLen role='$role'"
                            )
                        }
                        contentLen += cLen
                        reasoningLen += rcLen + rLen
                        if (tcLen > 0) toolCallEventCount += tcLen
                    } else if (type.isNotEmpty()) {
                        // Responses API event-typed diagnostics
                        val dLen = ev.optString("delta", "").length
                        if (type.contains("delta") || type == "response.completed" || type == "response.output_item.added" || type == "response.output_item.done") {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE responses type=$type deltaLen=$dLen"
                            )
                        }
                        if (type == "response.output_text.delta") {
                            contentLen += dLen
                            // [T-android-incomplete-keep-partial] contentLen alone
                            // can't distinguish " " from real text, and the
                            // response.incomplete handler needs that difference to
                            // decide between keeping a truncated answer and failing
                            // the turn. Track it here, where the delta text is in
                            // hand, rather than buffering the whole response.
                            if (!sawNonBlankText && ev.optString("delta", "").isNotBlank()) {
                                sawNonBlankText = true
                            }
                        }
                        if (type.startsWith("response.reasoning_")) reasoningLen += dLen
                    }
                }

                if (isResponsesAPI) {
                    // Responses API SSE parsing
                    val type = event.optString("type", "")
                    when {
                        // Reasoning text deltas — both event variants the API emits.
                        // For Codex OAuth the actual content is encrypted (echoed via
                        // include=reasoning.encrypted_content), so the .delta value
                        // is typically empty; for non-Codex Responses (forceResponsesAPI
                        // or custom base) it streams plaintext we can render.
                        // Mirrors iOS OpenAIAgentProvider.swift:374-382.
                        type == "response.reasoning_text.delta" ||
                            type == "response.reasoning_summary_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                if (!sawReasoningDelta) {
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Responses API: first reasoning delta arrived (type=$type) — streaming Thinking content"
                                    )
                                    sawReasoningDelta = true
                                }
                                send(LLMStreamChunk.ThinkingDelta(delta))
                            }
                        }
                        type == "response.output_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) send(LLMStreamChunk.Text(delta))
                        }
                        // function_call item announced — capture call_id + name, start accumulator.
                        type == "response.output_item.added" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val callId = item.optString("call_id", "")
                                val name = item.optString("name", "")
                                if (itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()) {
                                    responsesToolCalls[itemId] = ResponsesToolCallAccumulator(callId = callId, name = name)
                                    val combined = combineResponsesAPIIds(callId, itemId)
                                    if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses) id=$combined name=$name")
                                    send(LLMStreamChunk.ToolUseStart(combined, name))
                                    responsesToolCalls[itemId]?.started = true
                                }
                            }
                        }
                        type == "response.function_call_arguments.delta" -> {
                            val itemId = event.optString("item_id", "")
                            val delta = event.optString("delta", "")
                            val acc = responsesToolCalls[itemId]
                            if (acc != null && delta.isNotEmpty()) {
                                acc.args.append(delta)
                                val n = acc.args.length
                                if (com.openminis.app.text.BoundedText.shouldCommitLengthStride(n, acc.lastSentArgsLen)) {
                                    acc.lastSentArgsLen = n
                                    val combined = combineResponsesAPIIds(acc.callId, itemId)
                                    send(LLMStreamChunk.ToolInputDelta(combined, acc.args.toString()))
                                }
                            } else if (acc == null) {
                                // Pre-T107 this branch silently dropped the entire tool call
                                // because no accumulator was set up — leaving the model with
                                // no real tool channel and provoking <tool_call>{...} text
                                // hallucinations. Keep a warn so any future regression here
                                // surfaces in the daily log instead of a silent failure.
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API: function_call_arguments.delta for unknown item_id=$itemId — dropping"
                                )
                            }
                        }
                        // The accumulator is finalized at response.output_item.done, when the
                        // arguments stream has flushed. The completed item carries `arguments`
                        // as a JSON string — we prefer that authoritative value over our own
                        // streamed buffer in case the API ever emits a corrected payload.
                        type == "response.output_item.done" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                // [GH#83/#146] Some relays start at
                                // output_item.done (the added event never
                                // arrived). Dropping the call here left the
                                // model with no tool channel. Reconstruct the
                                // accumulator from the completed item.
                                val acc = responsesToolCalls.remove(itemId)
                                    ?: ResponsesToolCallAccumulator(
                                        callId = item.optString("call_id", "").ifBlank { itemId },
                                        name = item.optString("name", ""),
                                    )
                                if (acc.name.isBlank()) {
                                    com.openminis.app.logging.AppLogger.warning(
                                        "OpenAIProvider",
                                        "Responses output_item.done function_call without a name " +
                                            "id=$itemId — dropping",
                                    )
                                    continue
                                }
                                // [GH#83] `arguments` is present but EMPTY on
                                // some Codex/Responses relays even though the
                                // deltas carried the real payload; taking the
                                // empty authoritative value produced the
                                // "all tool calls are {}" report. Prefer the
                                // authoritative value only when non-blank.
                                val authoritativeArgs = item.optString("arguments", "")
                                val argsStr = authoritativeArgs.ifBlank { acc.args.toString() }
                                val parsed = NonFiniteJson.parseOrNull(argsStr)
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                // [GH#83/#146] A reconstructed accumulator has
                                // no ToolUseStart yet; emit one so the agent
                                // loop creates the UI tool block it later
                                // finalizes (otherwise the tool runs with no
                                // card and the user sees a silent turn).
                                if (!acc.started) {
                                    acc.started = true
                                    send(LLMStreamChunk.ToolUseStart(combined, acc.name))
                                }
                                if (parsed == null && argsStr.isNotBlank()) {
                                    // [GH#61/#83/#146] The authoritative
                                    // `arguments` string did not parse. Keep
                                    // the bytes for ToolJsonRepair and flag
                                    // the completion instead of sending `{}`.
                                    send(LLMStreamChunk.ToolInputDelta(combined, argsStr))
                                    com.openminis.app.logging.AppLogger.warning(
                                        "OpenAIProvider",
                                        "Responses output_item.done carried invalid tool args id=$combined " +
                                            "name=${acc.name} (${argsStr.length} chars) — carrying raw tail for repair",
                                    )
                                }
                                val args = parsed ?: JSONObject()
                                if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=${acc.name} args=${args.toString().take(300)}")
                                send(LLMStreamChunk.ToolCallComplete(
                                    combined, acc.name, args,
                                    rawArgs = argsStr.takeIf { parsed == null && it.isNotBlank() },
                                ))
                            } else if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) android.util.Log.d("ToolChain[Provider]", "output_item.done type=$itemType id=${item.optString("id", "")} — acknowledged") // [T-responses-unknown-item] Acknowledge only, NEVER re-send item.content: for message/reasoning items `content` is a JSON ARRAY, so optString would dump raw JSON into the visible stream, and the text already streamed via response.output_text.delta.
                        }
                        type == "response.failed" -> {
                            // [T-responses-terminal-events] Explicit terminal
                            // handling instead of the generic fallthrough: pull
                            // the structured error off the response object so
                            // the thrown LLMError carries the real reason (and
                            // so retry/fallback classification can act on it).
                            // Official shape: response.status == "failed",
                            // response.error = {code, message}. Mirrors iOS 637cd890.
                            val resp = event.optJSONObject("response")
                            val err = resp?.optJSONObject("error")
                            val code = err?.optString("code")?.takeIf { it.isNotEmpty() } ?: "unknown"
                            val message = err?.optString("message")?.takeIf { it.isNotEmpty() }
                                ?: "response.failed with no error detail"
                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.failed — code=$code message=$message"
                            )
                            if (code == "server_error" || code == "rate_limit_exceeded") {
                                // Transient family: retry on the same model
                                // rather than falling back through the group.
                                // [T-llm-error-secret-mask] `message` is
                                // upstream-controlled text inside LLMError.message.
                                throw LLMError.TransientError("[$code] ${maskSecrets(message)}")
                            }
                            throw LLMError.ProviderError("[$code] ${maskSecrets(message)}")
                        }
                        type == "response.incomplete" -> {
                            // [T-responses-terminal-events] The server ended the
                            // response early; incomplete_details.reason is
                            // "max_output_tokens" or "content_filter".
                            val reason = event.optJSONObject("response")
                                ?.optJSONObject("incomplete_details")
                                ?.optString("reason")?.takeIf { it.isNotEmpty() }
                                ?: "unknown"

                            // [T-android-incomplete-keep-partial] Only fail the
                            // turn when there is genuinely nothing to show.
                            //
                            // The old code threw unconditionally, and the comment
                            // above it ("Partial output has already been streamed
                            // — surface WHY") described an intent the code did not
                            // implement: throwing here discards the streamed text,
                            // so a truncated-but-useful answer was reported to the
                            // user as a hard failure with nothing rendered.
                            //
                            // Reported against a Responses-format relay proxying
                            // Claude: the model spent its whole budget in the
                            // reasoning phase and emitted one space of visible
                            // text, then `response.incomplete
                            // reason=max_output_tokens` with
                            // `usage.output_tokens=0`. Raising the setting could
                            // not help (the budget went to reasoning, and the
                            // relay reports output_tokens=0 regardless), so every
                            // retry failed the same way and the turn was lost.
                            //
                            // Truncation is a normal terminal condition, not an
                            // error: Chat Completions already models it as
                            // `finish_reason=length` and ends the stream
                            // normally. Treating the Responses spelling the same
                            // way keeps the two API flavours consistent and lets
                            // the agent loop persist what did arrive.
                            //
                            // `contentLen` counts text deltas actually forwarded
                            // downstream, so it is the honest test for "does the
                            // user have something to read". Whitespace-only output
                            // (the reported case) counts as nothing, since a bubble
                            // containing one space is indistinguishable from a bug.
                            val hasUsableOutput = sawNonBlankText
                            if (hasUsableOutput) {
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API response.incomplete — reason=$reason; " +
                                        "keeping ${contentLen}ch of partial output (finish_reason=length)"
                                )
                                // Same terminal shape Chat Completions uses for a
                                // budget-truncated answer, so downstream code needs
                                // no new branch: the loop stops, the text persists,
                                // and the UI can mark it truncated.
                                drain.terminal()
                                finishReason = "length"
                                sawFinishReason = true
                                break
                            }

                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.incomplete — reason=$reason (no usable output)"
                            )
                            throw LLMError.ProviderError(
                                "Response ended incomplete (reason: $reason)" +
                                    if (reason == "max_output_tokens") {
                                        // The old text told the user to raise Max
                                        // Output Tokens. When reasoning consumed
                                        // the budget that advice is actively
                                        // misleading — this user tried 128k, 32k
                                        // and 16k, all identical — so name the
                                        // real lever too.
                                        " — the model used its entire output budget before producing a reply" +
                                            " (often the thinking phase on a reasoning model). Try turning off or" +
                                            " lowering Deep Thinking, shortening the request, or raising the model's" +
                                            " Max Output Tokens."
                                    } else ""
                            )
                        }
                        type == "response.completed" -> {
                            drain.terminal()
                            val resp = event.optJSONObject("response")
                            val status = resp?.optString("status", "")
                            // When the model emitted tool calls the API returns status=completed
                            // with no stop_reason; surface "tool_use" so the agent loop knows to
                            // dispatch the calls instead of treating the turn as final.
                            val sawToolCalls = responsesToolCalls.isNotEmpty() ||
                                (resp?.optJSONArray("output")?.let { out ->
                                    var found = false
                                    for (i in 0 until out.length()) {
                                        if (out.optJSONObject(i)?.optString("type") == "function_call") { found = true; break }
                                    }
                                    found
                                } ?: false)
                            finishReason = when {
                                sawToolCalls -> "tool_use"
                                status == "completed" -> "stop"
                                else -> status
                            }
                            if (!sawFinishReason) {
                                sawFinishReason = true
                                // [T-codex-fast-mode] The response object inside
                                // response.completed echoes the EFFECTIVE
                                // service_tier — "priority" here is definitive
                                // proof Fast Mode was honored; "default"/absent
                                // means requested-but-downgraded (OpenAI silently
                                // downgrades ineligible accounts). Mirrors iOS
                                // 63a71146.
                                val serviceTier = resp?.optString("service_tier", "")
                                    ?.takeIf { it.isNotEmpty() } ?: "n/a"
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses finish_reason=$finishReason status=$status service_tier=$serviceTier contentLen=$contentLen reasoningLen=$reasoningLen toolCallAccumulators=${responsesToolCalls.size}"
                                )
                            }
                            resp?.optJSONObject("usage")?.let { usage ->
                                sawUsageBlock = true
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses usage block: $usage"
                                )
                                send(LLMStreamChunk.Usage(parseResponsesAPIUsage(usage)))
                            }
                        }
                        type == "response.output_text.done" -> {
                            // Text output complete, no action needed
                        }
                    }
                } else {
                    // Chat Completions API SSE parsing
                    // Check for inline error (OpenRouter sends error inside SSE with empty choices)
                    val inlineError = event.optJSONObject("error")
                    if (inlineError != null) {
                        val code = inlineError.optInt("code", 0)
                        val msg = inlineError.optString("message", "Unknown SSE error")
                        val err = mapHttpError(code, event.toString())
                        throw err
                    }
                    val choices = event.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")

                        // Reasoning / thinking content (DeepSeek, Kimi, etc.)
                        delta?.let { d ->
                            // Track presence of either field — even an empty string
                            // counts so we can round-trip DeepSeek V4's `reasoning_content: ""`.
                            val hasRcKey = d.has("reasoning_content")
                            val hasReasoningKey = d.has("reasoning")
                            if (hasRcKey || hasReasoningKey) {
                                sawReasoningField = true
                            }
                            val rc = d.safeOptString("reasoning_content", "")
                                .ifEmpty { d.safeOptString("reasoning", "") }
                            if (rc.isNotEmpty()) {
                                reasoningAccum.append(rc)
                                if (!sawReasoningDelta) {
                                    sawReasoningDelta = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Chat Completions: first reasoning_content delta arrived on ${model.id} — streaming Thinking content"
                                    )
                                }
                                send(LLMStreamChunk.ThinkingDelta(rc))
                            }
                        }

                        // [T-android-think-prefix-stream] Text content. Models that
                        // embed reasoning as a `<think>…</think>` PREFIX of
                        // `content` (MiniMax M3, some Qwen/DeepSeek deployments)
                        // are split by ThinkPrefixStreamParser, which replaced the
                        // old extractThinkTags scanner. That scanner searched for
                        // `<think>` at ANY offset, so a reply merely explaining the
                        // tag had its prose swallowed into the thinking bubble, and
                        // it passed M3's post-`</think>` "\n\n" straight through so
                        // every such body began with a blank line.
                        delta?.safeOptString("content", "")?.let { text ->
                            if (text.isNotEmpty()) {
                                val out = thinkParser.feed(text)
                                if (out.thinking.isNotEmpty()) {
                                    reasoningAccum.append(out.thinking)
                                    send(LLMStreamChunk.ThinkingDelta(out.thinking))
                                }
                                // [GH#113] Route the visible text through the
                                // sniffer: a call envelope goes to the tool lane,
                                // ordinary prose streams exactly as before.
                                if (out.visible.isNotEmpty()) emitSniffed(contentSniffer.feed(out.visible))
                            }
                        }

                        // Tool calls (parallel: keyed by index)
                        val toolCalls = delta?.optJSONArray("tool_calls")
                        if (toolCalls != null) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val idx = tc.optInt("index", 0)
                                val acc = toolCallAccumulators.getOrPut(idx) { ToolCallAccumulator() }

                                // [T-toolcall-missing-id] Derive only while no id was
                                // captured and start has not fired — later deltas of
                                // the same call carry no `id`; re-deriving clobbered
                                // the real first-delta id (tool-card input loss).
                                acc.id = resolveToolCallId(acc.id, tc.safeOptString("id", ""), idx, acc.started)
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.safeOptString("name", "").let { if (it.isNotEmpty()) acc.name = it }
                                    fn.safeOptString("arguments", "").let { if (it.isNotEmpty()) acc.args.append(it) }
                                }

                                // Emit start exactly once per tool call
                                if (!acc.started && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                                    acc.started = true
                                    if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=${acc.id} name=${acc.name}")
                                    send(LLMStreamChunk.ToolUseStart(acc.id, acc.name))
                                }
                                // Emit input delta
                                if (acc.id.isNotEmpty() && acc.args.isNotEmpty()) {
                                    val n = acc.args.length
                                    if (com.openminis.app.text.BoundedText.shouldCommitLengthStride(n, acc.lastSentArgsLen)) {
                                        acc.lastSentArgsLen = n
                                        if (com.openminis.app.text.BoundedText.shouldLogLengthStride(n)) {
                                            if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=${acc.id} accumulated=${n}chars")
                                        }
                                        send(LLMStreamChunk.ToolInputDelta(acc.id, acc.args.toString()))
                                    }
                                }
                            }
                        }

                        // Finish reason
                        choice.safeOptString("finish_reason", "").let {
                            if (it.isNotEmpty()) {
                                drain.terminal()
                                finishReason = it
                                if (!sawFinishReason) {
                                    sawFinishReason = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "[T321] finish_reason=$it contentLen=$contentLen reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount accumulators=${toolCallAccumulators.size}"
                                    )
                                }
                            }
                        }
                    }

                    event.optJSONObject("usage")?.let { usage ->
                        sawUsageBlock = true
                        com.openminis.app.logging.AppLogger.info(
                            "OpenAIProvider",
                            "[T321] usage block: $usage"
                        )
                        send(LLMStreamChunk.Usage(parseChatCompletionsUsage(usage)))
                    }
                }
            }

            // [T-android-think-prefix-stream] Stream-end flush, for streams that
            // end without a `[DONE]` sentinel. finishTurn() is idempotent, so
            // running after the [DONE] path already flushed is a no-op.
            thinkParser.finishTurn().let { fin ->
                if (fin.thinking.isNotEmpty()) {
                    reasoningAccum.append(fin.thinking)
                    send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                }
                if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
            }
            // [GH#113] No `[DONE]` sentinel: release any half-received call
            // envelope as text (idempotent after the [DONE] path).
            emitSniffed(contentSniffer.finish())

            // Emit ToolCallComplete for all accumulated tool calls
            for ((_, acc) in toolCallAccumulators) {
                if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                    val rawArgs = acc.args.toString()
                    val parsed = NonFiniteJson.parseOrNull(rawArgs)
                    if (parsed == null && rawArgs.isNotBlank()) {
                        // [GH#61/#83/#146] Do not silently substitute `{}`.
                        // Park the exact tail in the agent loop's ring (a
                        // ToolInputDelta the stride gate may have skipped
                        // would be stale) and carry it on the chunk so
                        // ToolJsonRepair can try to close a truncated JSON,
                        // and preflight can report "parameter stream
                        // incomplete" when repair cannot.
                        send(LLMStreamChunk.ToolInputDelta(acc.id, rawArgs))
                        com.openminis.app.logging.AppLogger.warning(
                            "OpenAIProvider",
                            "tool args for id=${acc.id} name=${acc.name} are not valid JSON " +
                                "(${rawArgs.length} chars) — carrying raw tail for repair",
                        )
                    }
                    val args = parsed ?: JSONObject()
                    if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
                    send(LLMStreamChunk.ToolCallComplete(
                        acc.id, acc.name, args,
                        rawArgs = rawArgs.takeIf { parsed == null && it.isNotBlank() },
                    ))
                }
            }
            // Drain Responses-API tool accumulators that didn't get an output_item.done
            // before the stream closed. Without this, mid-tool-call truncation (server
            // closes connection while function_call_arguments is still streaming) leaves
            // ChatViewModel.toolCalls empty: the agent loop sees no tool calls, exits,
            // and the UI hangs with the tool thumbnail spinning while the stop button
            // disappears (T247 root cause; same path hit by T237 DeepSeek truncation).
            for ((itemId, acc) in responsesToolCalls) {
                if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
                    val rawArgs = acc.args.toString()
                    val parsed = NonFiniteJson.parseOrNull(rawArgs)
                    val combined = combineResponsesAPIIds(acc.callId, itemId)
                    if (parsed == null && rawArgs.isNotBlank()) {
                        // [GH#61/#83/#146] Same contract as the Chat
                        // Completions tail: park the exact tail and carry it,
                        // never hand the executor a silent `{}`.
                        send(LLMStreamChunk.ToolInputDelta(combined, rawArgs))
                        com.openminis.app.logging.AppLogger.warning(
                            "OpenAIProvider",
                            "Responses stream ended with invalid tool args id=$combined " +
                                "name=${acc.name} (${rawArgs.length} chars) — carrying raw tail for repair",
                        )
                    }
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
                    )
                    send(LLMStreamChunk.ToolCallComplete(
                        combined, acc.name, parsed ?: JSONObject(),
                        rawArgs = rawArgs.takeIf { parsed == null && it.isNotBlank() },
                    ))
                }
            }
            responsesToolCalls.clear()

            // T321: stream ended — final tally + warning if we never saw a
            // finish_reason. [T-android-responses-missing-finished] Chat
            // Completions sends [DONE]; the Responses API and many relays just
            // close the socket, so Finished is emitted from this tail (gated on
            // sawFinishReason: reaching here WITHOUT one is a genuine
            // truncation and must fall through to the warning below).
            // truncation, and must keep falling through to the warning below so
            // the interrupted-reply UI still fires for real drops.
            if (sawFinishReason && !sentFinished) {
                // Mirror the [DONE] branch's ordering: flush the think-tag
                // parser and reasoning blob before the terminal chunk, or a
                // trailing <think> tail would be dropped and reasoning would
                // vanish on reload.
                thinkParser.finishTurn().let { fin ->
                    if (fin.thinking.isNotEmpty()) {
                        reasoningAccum.append(fin.thinking)
                        send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                    }
                    if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                }
                if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                    send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                }
                send(LLMStreamChunk.Finished(finishReason))
                sentFinished = true
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream ended without [DONE] — emitted Finished(finishReason=$finishReason) from tail"
                )
            }

            if (!sawFinishReason) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T321] stream ended WITHOUT finish_reason: events=$sseEventCount " +
                        "contentLen=$contentLen reasoningLen=$reasoningLen " +
                        "toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock model=${model.id}"
                )
            } else {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                        "reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock " +
                        "streamMs=${if (firstEventAtMs == 0L) 0 else lastEventAtMs - firstEventAtMs}"
                )
            }
        } catch (e: Exception) {
            // [T-android-stop-cancels-socket] Stop is a normal exit — rethrow,
            // don't log it as "[T321] stream parse exception" noise.
            if (e is kotlinx.coroutines.CancellationException) throw e
            // T321: never silently swallow — log message + top-3 stack frames.
            val frames = e.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] stream parse exception: ${e.javaClass.simpleName}: ${e.message} @ $frames " +
                    "(events=$sseEventCount contentLen=$contentLen reasoningLen=$reasoningLen)"
            )
            cancel("Stream error", mapError(e))
        } finally {
            drain.close()
            runCatching { reader.close() }
            runCatching { response.close() }
        }
        channel.close()
        // T171: when the coroutine is cancelled (user tapped stop), the
        // reader loop above is suspended inside the OkHttp source — only
        // call.cancel() will tear the socket down promptly. response.close()
        // is also explicit so connection-pool leaks are impossible if cancel
        // races with the finally block.
        awaitClose {
            try { call.cancel() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
        }
        } finally {
            stopHook?.dispose()
            call.cancel()
        }
    }
