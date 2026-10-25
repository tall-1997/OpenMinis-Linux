package com.openminis.app.data.display

import com.openminis.app.data.body.ResourceLimits
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a stored message body into JSON the chat bubble can parse.
 *
 * List queries project oversized cells to a stub or an 8KB prefix. That prefix
 * is not valid JSON, and the stub uses a key the bubble does not read, so the
 * turn looks deleted. Hydration reads the real body once, then shrinks it to a
 * complete JSON array. The database row is not rewritten.
 */
internal object DisplayParts {
    const val TEXT_KEEP_CHARS = 128_000
    const val TOOL_KEEP_CHARS = 8_000

    fun needsHydration(bodyRef: String?, bodyBytes: Long, projected: String): Boolean {
        if (!bodyRef.isNullOrBlank()) return true
        return bodyBytes > ResourceLimits.INLINE_BODY_BYTES && projected.length < bodyBytes
    }

    fun shrink(raw: String): String {
        val parts = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return note("这条消息的原文无法完整解析，本地记录仍在。")
        }
        var changed = false
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            when (part.optString("type")) {
                "text" -> changed = normalizeText(part) || changed
                "toolResult" -> changed = shortenField(part, "output") || changed
                "toolUse" -> changed = shortenField(part, "input") || changed
            }
        }
        return if (changed) parts.toString() else raw
    }

    fun note(text: String): String =
        """[{"type":"text","value":${JSONObject.quote(text)}}]"""

    private fun normalizeText(part: JSONObject): Boolean {
        val source = when {
            part.has("value") -> part.optString("value", "")
            part.has("text") -> part.optString("text", "")
            else -> return false
        }
        var changed = false
        if (!part.has("value")) {
            part.put("value", source)
            changed = true
        }
        val shown = part.optString("value", source)
        if (shown.length > TEXT_KEEP_CHARS) {
            part.put("value", shown.take(TEXT_KEEP_CHARS) + "\n…")
            changed = true
        }
        return changed
    }

    private fun shortenField(part: JSONObject, field: String): Boolean {
        val value = part.optJSONObject("value") ?: return false
        val text = value.optString(field, "")
        if (text.length <= TOOL_KEEP_CHARS) return false
        value.put(field, "…\n" + text.takeLast(TOOL_KEEP_CHARS))
        return true
    }
}
