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

    /**
     * [T-body-spill-stub] True when [json] is the offload placeholder that
     * [com.openminis.app.data.repository.ChatRepository.storeBody] writes in
     * place of an oversized body.
     *
     * The placeholder is VALID JSON shaped exactly like a normal text message,
     * which is what made the bug invisible: when the blob could not be read,
     * hydration fell back to `messages.parts_json`, got the placeholder, parsed
     * it happily and painted "body stored (N bytes)" as if the model had said
     * it. Anything treating projected parts as content must screen this out
     * first.
     */
    fun isStub(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val parts = try {
            JSONArray(json)
        } catch (_: Exception) {
            return false
        }
        if (parts.length() != 1) return false
        val part = parts.optJSONObject(0) ?: return false
        if (part.optString("type") != "text") return false
        val value = part.optString("value", "")
        return value.startsWith(STUB_PREFIX) || value.startsWith(OMITTED_PREFIX)
    }

    /**
     * [T-body-spill-stub] Display fallback built from the `preview` column.
     *
     * The preview is `<8KB raw head>\n␞<plain text>`; the raw head is usually
     * truncated JSON, so only the tail after the sentinel is safe to show. When
     * a row predates the sentinel the whole cell is the raw head, and the text
     * is recomputed from it. Null when nothing human survived, so the caller
     * keeps its generic note instead of painting an empty bubble.
     */
    fun previewNote(preview: String?, maxChars: Int = PREVIEW_NOTE_CHARS): String? {
        if (preview.isNullOrBlank()) return null
        val sentinel = com.openminis.app.data.repository.PREVIEW_SENTINEL
        val head = preview.substringBefore(sentinel)
        val human = preview.substringAfter(sentinel, "").trim().ifEmpty {
            com.openminis.app.data.repository.ChatRepository.extractTextPreview(head).orEmpty().trim()
        }
        if (human.isBlank()) return null
        val body = if (human.length > maxChars) human.take(maxChars) + "…" else human
        return note(body)
    }

    const val STUB_PREFIX = "body stored ("
    const val OMITTED_PREFIX = "body omitted"

    /** Chars kept from a preview when it stands in for a missing body. */
    const val PREVIEW_NOTE_CHARS = 4_000

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
