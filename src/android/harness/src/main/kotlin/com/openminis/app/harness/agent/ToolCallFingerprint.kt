package com.openminis.app.harness.agent

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-p0-1-extraction] 工具调用指纹：参数与结果的稳定哈希。从 ToolLoopDetector
 * 拆出——检测器本体要守 400 行新文件上限，而指纹是自成一体的纯函数簇
 * （键序无关、忽略 tool_title 这类展示键、org.json 结构归一）。
 */
internal object ToolCallFingerprint {

private val HEX = "0123456789abcdef".toCharArray()

    private val ARGS_HASH_IGNORED_KEYS: Set<String> = setOf("tool_title")

    fun argsHash(toolName: String, params: Map<String, Any?>): String {
        // Drop UI/telemetry-only fields the model freely varies — most notably
        // `tool_title`, which models routinely counter-suffix ("Read X #1",
        // "#2", ...). Without this filter every logically identical call hashes
        // unique and the repeat/circuit-breaker strategies all silently fail.
        val filtered = if (params.keys.any { it in ARGS_HASH_IGNORED_KEYS }) {
            params.filterKeys { it !in ARGS_HASH_IGNORED_KEYS }
        } else {
            params
        }
        return sha256("$toolName:${stableJson(filtered)}")
    }

    /**
     * Stable JSON: keys sorted alphabetically at every nesting level so that
     * a Map<"b" → 2, "a" → 1> hashes identically to one inserted "a" → 1, "b" → 2.
     */
    internal fun stableJson(value: Any?): String = buildString { appendStable(value) }

    internal fun StringBuilder.appendStable(value: Any?) {
        when (value) {
            null -> append("null")
            is Map<*, *> -> {
                append('{')
                value.entries
                    .map { it.key?.toString().orEmpty() to it.value }
                    .sortedBy { it.first }
                    .forEachIndexed { i, (k, v) ->
                        if (i > 0) append(',')
                        append(JSONObject.quote(k)); append(':'); appendStable(v)
                    }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    appendStable(v)
                }
                append(']')
            }
            is Array<*> -> appendStable(value.toList())
            is String -> append(JSONObject.quote(value))
            is Number, is Boolean -> append(value.toString())
            is JSONObject -> appendStable(value.toMap())
            is JSONArray -> appendStable(value.toList())
            else -> append(JSONObject.quote(value.toString()))
        }
    }

    internal fun JSONObject.toMap(): Map<String, Any?> {
        val out = HashMap<String, Any?>(length())
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = unwrap(get(k))
        }
        return out
    }

    internal fun JSONArray.toList(): List<Any?> {
        val out = ArrayList<Any?>(length())
        for (i in 0 until length()) out.add(unwrap(get(i)))
        return out
    }

    internal fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONObject -> v.toMap()
        is JSONArray -> v.toList()
        else -> v
    }

    /**
     * Hash only the success/failure-bearing parts of a tool result. We
     * deliberately fold the entire output text in too — the spec calls for
     * stripping noise like timestamps/requestIds, but the platform's tool
     * results don't carry those at this layer (the underlying tools already
     * sanitize them). If a future tool starts leaking volatile fields, prune
     * them here rather than at every call site.
     */
    fun resultHash(result: String?, errorMessage: String?): String {
        val payload = buildString {
            append("err=")
            append(errorMessage ?: "")
            append("out=")
            append(result ?: "")
        }
        return sha256(payload)
    }

    /**
     * Two patterns cover the wording variations seen across providers:
     *   "unknown tool: foobar"          → group 1 = "foobar"
     *   "tool 'foobar' not found"       → group 1 = "foobar"
     * Both case-insensitive; quoting and surrounding whitespace tolerated.
     */
    internal fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
