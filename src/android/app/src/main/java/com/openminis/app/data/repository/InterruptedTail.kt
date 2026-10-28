package com.openminis.app.data.repository

/**
 * [T-android-session-paused-badge-hardkill] The interrupted-tail predicate
 * over a raw `parts_json` string, matching ChatViewModel.loadSession's
 * AgentContentPart-based logic:
 *   - role USER + ALL parts are tool_result (tools ran, next model call never
 *     fired), OR the single synthetic "Continue" reminder text part, OR
 *   - role ASSISTANT + any tool_use part (model asked for tools that never ran)
 * Part type discriminator is the JSON "type" field — the @SerialName values
 * from [com.openminis.app.data.model.ContentPart]: "toolUse" / "toolResult"
 * / "text" (camelCase, NOT snake_case); text payload is the "value" field.
 *
 * Lives outside [ChatRepository] so the repository stays under its
 * architecture-policy line ceiling. The predicate is pure, so the move
 * changes nothing about who it matches.
 */
/**
 * [T-android-rerun-picker-preview] What `messages.preview` holds.
 *
 * The column has two readers with opposite needs. Search matches it with
 * `LIKE` and [isInterruptedTail] scans it for a `"type"` field, so the cell
 * must keep a raw head of `parts_json`. The re-run message picker shows the
 * cell verbatim, so it also needs the plain text a person would recognise —
 * `extractTextPreview`'s output, which the raw head is not: a long user turn
 * opens with a system-reminder block, and an 8KB head of that JSON ends
 * mid-token.
 *
 * Both fit in one cell. The raw head comes first, unchanged and still inside
 * [com.openminis.app.data.body.ResourceLimits.PREVIEW_BYTES], and the human
 * text follows [PREVIEW_SENTINEL]. Readers that scan the raw head are
 * unaffected because the sentinel cannot occur in the JSON they match; the
 * picker splits on it and shows only the tail. Rows written before this
 * change have no sentinel, and the picker falls back to showing the cell as
 * stored.
 */
internal const val PREVIEW_SENTINEL = "\n\u241e"

/**
 * The one line the re-run picker shows. Splits off the human tail
 * [messagePreviewCell] appended; a cell written before the sentinel existed
 * has none, so it shows as stored rather than collapsing to "(empty)".
 */
internal fun displayMessagePreview(stored: String?): String {
    val cell = stored.orEmpty()
    // Rows written before the sentinel existed store only the raw head. Rather
    // than showing that JSON, recompute the text from it — the same function
    // that produced the tail. It returns null only when the head holds no
    // summarisable part, and then the raw head is all there is to show.
    // [T-android-preview-reminder-leak] A tail the old buggy writer stored as
    // blank (reminder-only first text part) is recomputed the same way instead
    // of collapsing to "(empty)" while a real text part sits in the head.
    val human = if (PREVIEW_SENTINEL in cell) {
        val tail = cell.substringAfter(PREVIEW_SENTINEL)
        if (tail.isNotBlank()) {
            tail
        } else {
            ChatRepository.extractTextPreview(cell.substringBefore(PREVIEW_SENTINEL)) ?: tail
        }
    } else {
        ChatRepository.extractTextPreview(cell) ?: cell
    }
    return human.trim().replace(Regex("\\s+"), " ").take(80).ifBlank { "(empty)" }
}

internal fun messagePreviewCell(partsJson: String): String {
    val raw = partsJson.take(com.openminis.app.data.body.ResourceLimits.PREVIEW_BYTES)
    val human = ChatRepository.extractTextPreview(partsJson) ?: return raw
    return raw + PREVIEW_SENTINEL + human
}

internal fun isInterruptedTail(role: String, partsJson: String): Boolean {
    val arr = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return false
    val types = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { types.add(it.optString("type")) }
    }
    return when (role.uppercase()) {
        "USER" -> {
            val allToolResults = types.isNotEmpty() && types.all { it == "toolResult" }
            val isContinueReminder = arr.length() == 1 &&
                arr.optJSONObject(0)?.takeIf { it.optString("type") == "text" }
                    ?.optString("value")
                    ?.contains("The user stopped the previous response") == true
            allToolResults || isContinueReminder
        }
        "ASSISTANT" -> types.any { it == "toolUse" }
        else -> false
    }
}
