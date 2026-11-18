package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject

/**
 * [GH#113] Recover tool calls that a local / OpenAI-compatible gateway streams
 * through `content` instead of `tool_calls`.
 *
 * Some self-hosted and relay endpoints never implement the structured delta:
 * their SSE `delta.content` is the literal serialized call, e.g.
 * `{"name":"shell_execute","arguments":{"command":"ls"}}`. The app rendered
 * that JSON as assistant prose ("tool_call JSON leaked into the reply") and
 * the agent loop saw no tool call, so the turn ended after showing internals.
 *
 * This sniffer only engages at the very START of a response's visible text and
 * only when the text opens like a call envelope. It buffers while the JSON is
 * incomplete, then:
 *  - converts it to a [Emission.Call] when the parsed `name` is one of the
 *    tools actually advertised in this request (so an ordinary JSON answer
 *    such as `{"name":"Alice"}` can never be mistaken for a tool call), or
 *  - releases it as [Emission.Text] unchanged when it is anything else
 *    (including unparseable / oversized input).
 *
 * Truncated input is released by [finish] at stream end, so buffering can never
 * swallow text. Once any text or call has been emitted the sniffer stops
 * engaging — a mid-reply JSON fragment is a code block the user should see,
 * not a protocol artifact.
 */
internal class ContentToolCallSniffer(
    private val toolNames: Set<String>,
    private val maxBufferChars: Int = 16_384,
) {

    sealed class Emission {
        data class Text(val text: String) : Emission()

        /**
         * [GH#61/#83/#146] [rawArgs] is non-null when the recovered call's
         * `arguments`/`parameters` string failed the strict JSON parse. The
         * SSE and non-stream paths park the exact bytes on
         * [LLMStreamChunk.ToolCallComplete.rawArgs] so
         * [com.openminis.app.provider.ToolJsonRepair] can attempt closure
         * repair; this sniffer used to substitute a silent `{}`, which turned
         * every recovered call from such a gateway into a generic
         * "Blocked invalid tool call" rejection. Same contract here: null
         * means "parsed successfully" (including a genuine empty object).
         */
        data class Call(
            val name: String,
            val args: JSONObject,
            val rawArgs: String? = null,
        ) : Emission()
    }

    private val buffer = StringBuilder()
    private var sniffing = false
    private var emittedAnything = false

    /** Feed one visible text delta. Returns emissions in wire order. */
    fun feed(delta: String): List<Emission> {
        if (delta.isEmpty()) return emptyList()
        // No tools advertised on this request (title generation, compaction,
        // minis-model-use, …): a "call" envelope cannot be a real call, so the
        // sniffer stays completely out of the way.
        if (toolNames.isEmpty()) {
            emittedAnything = true
            return listOf(Emission.Text(delta))
        }
        if (emittedAnything && !sniffing) return listOf(Emission.Text(delta))
        if (!sniffing) {
            // Only an opening that could be a call envelope is worth withholding;
            // ordinary prose (and "Sure, here is…") streams straight through.
            val trimmed = delta.trimStart()
            if (!trimmed.startsWith("{")) return releaseText(delta)
            if (!looksLikeEnvelopePrefix(trimmed)) return releaseText(delta)
            sniffing = true
            buffer.append(delta)
            return tryParse()
        }
        buffer.append(delta)
        return tryParse()
    }

    /** Stream end: any withheld fragment is ordinary text after all. */
    fun finish(): List<Emission> {
        if (!sniffing) return emptyList()
        val text = buffer.toString()
        buffer.setLength(0)
        sniffing = false
        if (text.isEmpty()) return emptyList()
        emittedAnything = true
        return listOf(Emission.Text(text))
    }

    private fun tryParse(): List<Emission> {
        if (buffer.length > maxBufferChars) return flushAsText()
        val parsed = runCatching { JSONObject(buffer.toString().trim()) }.getOrNull()
            ?: return emptyList() // still incomplete — keep buffering
        val calls = extractCalls(parsed) ?: return flushAsText()
        buffer.setLength(0)
        sniffing = false
        emittedAnything = true
        return calls.map { Emission.Call(it.name, it.args, it.rawArgs) }
    }

    private fun flushAsText(): List<Emission> {
        val text = buffer.toString()
        buffer.setLength(0)
        sniffing = false
        emittedAnything = true
        return if (text.isEmpty()) emptyList() else listOf(Emission.Text(text))
    }

    private fun releaseText(delta: String): List<Emission> {
        emittedAnything = true
        return listOf(Emission.Text(delta))
    }

    /** `{` + optional whitespace + one of the known envelope keys (partials included). */
    private fun looksLikeEnvelopePrefix(trimmed: String): Boolean {
        val body = trimmed.removePrefix("{").trimStart()
        // A lone `{` may be the first chunk of a split envelope.
        if (body.isEmpty()) return true
        return ENVELOPE_KEYS.any { body.startsWith(it) }
    }

    private companion object {
        /**
         * Prefixes WITHOUT the closing quote: SSE deltas routinely split
         * `{"name":` across chunks, so a full-key match would drop the opener
         * and leak the rest of the JSON as prose.
         */
        val ENVELOPE_KEYS = listOf(
            "\"name",
            "\"tool_calls",
            "\"tool_call",
            "\"function",
            "\"arguments",
            "\"parameters",
        )
    }

    /** A recovered call: name, parsed (or empty) args, and the raw payload on parse failure. */
    private data class CallData(val name: String, val args: JSONObject, val rawArgs: String?)

    /**
     * Extract calls from either the OpenAI `tool_calls` wrapper or
     * a bare `{"name":…,"arguments":…}` object. Returns null when the shape is
     * not a call to an ADVERTISED tool, which makes the caller release the
     * buffered text untouched.
     */
    private fun extractCalls(obj: JSONObject): List<CallData>? {
        if (toolNames.isEmpty()) return null

        obj.optJSONArray("tool_calls")?.let { arr -> return parseToolCallsArray(arr) }
        obj.optJSONObject("tool_call")?.let { single ->
            return parseToolCallObject(single)
        }
        // A bare call object.
        val name = obj.optString("name", "")
        if (name.isEmpty() || name !in toolNames) return null
        val hasArgs = obj.has("arguments") || obj.has("parameters")
        if (!hasArgs) return null
        return listOf(callDataOf(name, obj))
    }

    private fun parseToolCallsArray(arr: JSONArray): List<CallData>? {
        val out = mutableListOf<CallData>()
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function") ?: tc
            val name = fn.optString("name", "")
            if (name.isEmpty() || name !in toolNames) continue
            val hasArgs = fn.has("arguments") || fn.has("parameters")
            if (!hasArgs) continue
            out.add(callDataOf(name, fn))
        }
        return out.ifEmpty { null }
    }

    private fun parseToolCallObject(obj: JSONObject): List<CallData>? {
        val fn = obj.optJSONObject("function") ?: obj
        val name = fn.optString("name", "")
        if (name.isEmpty() || name !in toolNames) return null
        if (!fn.has("arguments") && !fn.has("parameters")) return null
        return listOf(callDataOf(name, fn))
    }

    /**
     * [GH#61/#83/#146] Parse the `arguments`/`parameters` payload exactly the
     * way the SSE and non-stream paths do: a strict-parse failure must never
     * substitute a silent `{}`. Carry the raw string on the [CallData] so the
     * downstream [com.openminis.app.provider.ToolJsonRepair] gets the exact
     * bytes and preflight can either repair or report the precise
     * "parameter stream incomplete" rejection — instead of the generic
     * empty-arguments one that reads as "invalid tool call" to the user.
     */
    private fun callDataOf(name: String, obj: JSONObject): CallData {
        val raw = obj.opt("arguments") ?: obj.opt("parameters")
        return when (raw) {
            is JSONObject -> CallData(name, raw, null)
            is String -> runCatching { JSONObject(raw) }.fold(
                onSuccess = { CallData(name, it, null) },
                onFailure = {
                    CallData(name, JSONObject(), raw.takeIf { s -> s.isNotBlank() })
                },
            )
            else -> CallData(name, JSONObject(), null)
        }
    }
}
