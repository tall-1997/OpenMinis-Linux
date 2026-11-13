package com.openminis.app.ui.sessions

import com.openminis.app.data.repository.PreviewText

/**
 * [T-hidden-user-note] Text extraction for the session list's search
 * snippets and previews, extracted from SessionListViewModel (the file was
 * over the architecture ceiling after the strip-authority consolidation).
 *
 * The extraction walks a stored `parts_json` blob's text parts:
 * 1. [PreviewText.stripSystemReminders] — the single stripping authority —
 *    removes host reminder blocks AND the legacy #339 no-reply head, so a
 *    bookkeeping note never paints the preview / search line.
 * 2. The persisted `<user-attached-files>` XML inventory is removed too
 *    (model-facing metadata, not visible session content; iOS strips it the
 *    same way in ChatStore.toChatMessage).
 */
internal object SessionListExtractText {

    fun extract(partsJson: String): String {
        return try {
            val arr = org.json.JSONArray(partsJson)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                if (obj.optString("type") != "text") return@mapNotNull null
                val value = obj.optString("value")
                val noAttachments = stripAttachedFilesXmlValue(value)
                PreviewText.stripSystemReminders(noAttachments)
                    .ifEmpty { null }
            }.joinToString("\n")
        } catch (_: Exception) {
            partsJson
        }
    }

    private fun stripAttachedFilesXmlValue(value: String): String {
        if (!value.contains("<user-attached-files>")) return value
        val start = value.indexOf("<user-attached-files>")
        val endTag = "</user-attached-files>"
        val end = value.indexOf(endTag, start)
        return if (end >= 0) {
            value.substring(0, start) + value.substring(end + endTag.length)
        } else {
            value.substring(0, start)
        }.trim()
    }
}
