package com.openminis.app.ui.chat

import com.openminis.app.data.AssistantTextPartJson
import org.json.JSONArray

/** Reply-only copy and translation, including rows that also carry process text. */
internal object AssistantReplyText {
    private val reminderRegex =
        Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)
    private const val ATTACHED_START = "<user-attached-files>"
    private const val ATTACHED_END = "</user-attached-files>"

    fun joined(blocks: List<AssistantBlock>): String =
        blocks.filter { it.isReplyText && it.content.isNotBlank() }
            .joinToString("\n\n") { it.content }

    /** Keep reminder and attachment markup; replace the visible paragraph. */
    fun replaceVisible(raw: String, replacement: String): String {
        val kept = preservedMarkup(raw)
        return when {
            kept.isEmpty() -> replacement
            replacement.isEmpty() -> kept
            else -> kept + "\n" + replacement
        }
    }

    /** Index of [blockId] among reply blocks matching the visible [old] text, or -1. */
    fun textOccurrence(blocks: List<AssistantBlock>, blockId: String, old: String): Int {
        val needle = visible(old)
        if (needle.isEmpty()) return -1
        var seen = 0
        for (block in blocks) {
            if (!block.isReplyText || visible(block.content) != needle) continue
            if (block.id == blockId) return seen
            seen++
        }
        return -1
    }

    /**
     * Replace the [occurrence]-th visible text part equal to [oldVisible].
     * Comparison uses [visible], so a stored trailing newline still matches
     * the paragraph the user translated. Earlier and later copies stay.
     * Returns the one changed row, or null when that occurrence is absent.
     */
    fun replaceVisibleOccurrence(
        rows: List<Pair<String, String>>,
        oldVisible: String,
        replacement: String,
        occurrence: Int,
    ): Pair<String, String>? {
        val needle = visible(oldVisible)
        if (occurrence < 0 || needle.isEmpty()) return null
        var seen = 0
        for ((id, raw) in rows) {
            val parts = runCatching { JSONArray(raw) }.getOrNull() ?: continue
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") != "text" || AssistantTextPartJson.isProcessText(part)) continue
                if (visible(part.optString("value")) != needle) continue
                if (seen == occurrence) {
                    part.put("value", replaceVisible(part.optString("value"), replacement))
                    return id to parts.toString()
                }
                seen++
            }
        }
        return null
    }

    fun visible(raw: String): String {
        var text = raw
        // Single stripping authority (reminder blocks + legacy #339 no-reply
        // head) — the display pipeline and this echo path must behave
        // identically, so no local copy of the regex lives here.
        text = com.openminis.app.data.repository.PreviewText.stripSystemReminders(text)
        val start = text.indexOf(ATTACHED_START)
        if (start >= 0) {
            val end = text.indexOf(ATTACHED_END, start)
            text = if (end >= 0) {
                text.substring(0, start) + text.substring(end + ATTACHED_END.length)
            } else {
                text.substring(0, start)
            }
        }
        return text.trim()
    }

    /**
     * The last visible reply part becomes [translated]. Earlier reply parts
     * retain only system-reminder / attachment markup. Returns changed rows.
     */
    fun rewriteStoredParts(rows: List<Pair<String, String>>, translated: String): List<Pair<String, String>> {
        val loaded = rows.map { (id, raw) -> id to JSONArray(raw) }
        val hits = mutableListOf<Pair<Int, Int>>()
        loaded.forEachIndexed { row, (_, parts) ->
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") != "text" || AssistantTextPartJson.isProcessText(part)) continue
                if (visible(part.optString("value")).isEmpty()) continue
                hits.add(row to i)
            }
        }
        val lastHit = hits.lastOrNull() ?: return emptyList()
        val dirty = linkedSetOf<Int>()
        for (hit in hits) {
            val part = loaded[hit.first].second.getJSONObject(hit.second)
            val replacement = if (hit == lastHit) translated else ""
            part.put("value", replaceVisible(part.optString("value"), replacement))
            dirty.add(hit.first)
        }
        return dirty.map { loaded[it].first to loaded[it].second.toString() }
    }

    private fun preservedMarkup(raw: String): String {
        val bits = mutableListOf<String>()
        if (raw.contains("<system-reminder>")) {
            reminderRegex.findAll(raw).forEach { bits.add(it.value.trim()) }
        }
        val start = raw.indexOf(ATTACHED_START)
        if (start >= 0) {
            val end = raw.indexOf(ATTACHED_END, start)
            if (end >= 0) bits.add(raw.substring(start, end + ATTACHED_END.length))
        }
        return bits.joinToString("\n")
    }
}
