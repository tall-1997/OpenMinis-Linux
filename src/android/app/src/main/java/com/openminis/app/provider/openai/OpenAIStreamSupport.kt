package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.NonFiniteJson
import com.openminis.app.provider.safeOptString
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Support helpers for [OpenAIRawStream] (OpenAIProvider.rawStreamMessage),
 * extracted to keep that file inside its architecture-policy line ceiling:
 * the T302/[T-android-mem-probe-trust] body serialisation probe, the T321
 * request/response diagnostic logs, the streaming tool-call accumulator
 * types, and the [T-nonstream-toolcalls] emission for gateways that answer
 * non-SSE JSON. Moved verbatim from OpenAIRawStream.kt (function-local
 * blocks and classes became internal top-level declarations taking the
 * pieces they read as parameters); pure move, same bodies and log strings,
 * same [T-...] tags.
 */

/**
 * T302: serialize the request body exactly once. Pre-T302 we called
 * body.toString() three times per request (debug log + OAuth byte
 * build + non-OAuth RequestBody), each materialising a fresh 30+ MB
 * string for long agent loops with heavy tool outputs. Stacked, that
 * pushed memory-tight devices (HONOR PTP-AN00) past the OOM line.
 *
 * [T-android-mem-probe-trust] Bracket the serialisation itself. The
 * 2026-08-15 field log recorded `bodyLen=2342987` on the request before
 * a process death but nothing about its memory cost, so "did building
 * this body kill us?" could not be answered from the log. We measure
 * around the call and report the realised length, so an OOM thrown here
 * now arrives with an attributed stack instead of anonymously.
 */
internal fun serializeRequestBodyWithAllocProbe(
    body: JSONObject,
    modelId: String,
    messageCount: Int,
): String {
    val memBefore = com.openminis.app.diagnostics.MemorySnapshot.capture()
    val serStartNs = System.nanoTime()
    val bodyStr = try {
        body.toString()
    } catch (t: Throwable) {
        com.openminis.app.diagnostics.LargeAllocProbe.report(
            "openai.body.toString", -1, "model=$modelId messages=$messageCount",
            memBefore, serStartNs, failure = t,
        )
        throw t
    }
    if (bodyStr.length >= com.openminis.app.diagnostics.LargeAllocProbe.NOTABLE_BYTES) {
        com.openminis.app.diagnostics.LargeAllocProbe.report(
            "openai.body.toString", bodyStr.length.toLong(),
            "model=$modelId messages=$messageCount",
            memBefore, serStartNs, failure = null,
        )
    }
    return bodyStr
}

/**
 * T321: request-side diagnostic log. Header *keys* + Authorization
 * presence (no token values), and a body summary (counts only — never
 * the message text/images/tool-result bytes).
 */
internal fun logT321Request(
    request: okhttp3.Request,
    body: JSONObject,
    modelId: String,
    usesResponsesAPI: Boolean,
    bodyLen: Int,
) {
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
        "[T321] → REQ url=${request.url} model=$modelId stream=${body.optBoolean("stream", false)} " +
            "headerKeys=${request.headers.names()} authPresent=$authPresent " +
            "messages=$msgsLen tools=$toolsLen temp=$temp maxTokens=$maxTok hasSystem=$hasSystem " +
            "useResponsesAPI=$usesResponsesAPI bodyLen=$bodyLen"
    )
}

/**
 * T321: response-side diagnostic log (status + select header values).
 */
internal fun logT321Response(response: Response) {
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

// Chat Completions: tool calls are streamed as deltas keyed by index.
internal data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastSentArgsLen: Int = 0)

// Responses API: function_call items are keyed by their item_id (fc_…). The
// accumulator stores the call_id separately because the agent loop needs the
// call_id to correlate tool results, but the next request must echo back the
// item_id verbatim — the stream emits a combined "callId|fcId" identifier
// that splitResponsesAPIIds() unpacks.
internal data class ResponsesToolCallAccumulator(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastSentArgsLen: Int = 0)

/**
 * [T-nonstream-toolcalls] A gateway that answered JSON never
 * streams tool_call deltas, but `message.tool_calls` carries
 * them complete — the old parse read only `content`, so every
 * model tool call was silently discarded on non-SSE relays and
 * the turn looked like a plain text reply. Emit the same
 * Start/Complete sequence the SSE path produces.
 */
internal suspend fun emitNonStreamToolCalls(
    calls: JSONArray,
    send: suspend (LLMStreamChunk) -> Unit,
) {
    for (i in 0 until calls.length()) {
        val tc = calls.optJSONObject(i) ?: continue
        val fn = tc.optJSONObject("function") ?: continue
               // [T-nonstream-unique-id] A relay that omits `tool_call.id` used to get
        // `nonstream_call_<index>`, which REPEATS for every response. Downstream
        // identity is per-operation (receipts key conflicts on it, the tool
        // ledger dedupes on it), so a repeated id made the second turn's
        // mutation look like a replay of the first one. Generate a per-response
        // unique id instead of a positional one.
        val id = tc.safeOptString("id", "").ifBlank {
            "nonstream_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16) + "_$i"
        }

        val name = fn.safeOptString("name", "")
        if (name.isEmpty()) continue
        send(LLMStreamChunk.ToolUseStart(id, name))
        val rawArgs = fn.safeOptString("arguments", "")
        val parsed = NonFiniteJson.parseOrNull(rawArgs)
        if (parsed == null && rawArgs.isNotBlank()) {
            // [GH#61/#83/#146] Same contract as the SSE tail:
            // never hand the executor a silent `{}` when the
            // bytes are unparsable — park them for repair.
            send(LLMStreamChunk.ToolInputDelta(id, rawArgs))
        }
        send(LLMStreamChunk.ToolCallComplete(
            id, name, parsed ?: JSONObject(),
            rawArgs = rawArgs.takeIf { parsed == null && it.isNotBlank() },
        ))
    }
}
