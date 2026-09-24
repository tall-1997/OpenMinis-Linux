package com.openminis.app.ui.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fold-mode translation is one string for every visible assistant paragraph.
 * Writing that string back must not turn tool rows into a single text part,
 * and must not leave the earlier paragraphs in the database so a reload
 * shows the original text above the translation.
 */
internal object AssistantReplyText {
    private val reminderRegex =
        Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)
    private const val ATTACHED_START = "<user-attached-files>"
    private const val ATTACHED_END = "</user-attached-files>"

    fun joined(blocks: List<AssistantBlock>): String =
        blocks.filter { it.kind == "text" && it.content.isNotBlank() }
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

    /**
     * Index of [blockId] among text blocks whose content equals [old].
     * -1 when that block is not a text block. Used so a later identical
     * paragraph is not written onto the earlier one.
     */
    fun textOccurrence(blocks: List<AssistantBlock>, blockId: String, old: String): Int {
        var seen = 0
        for (block in blocks) {
            if (block.id == blockId && block.kind == "text") return seen
            if (block.kind == "text" && block.content == old) seen++
        }
        return -1
    }

    /**
     * Replace the [occurrence]-th visible text part equal to [oldVisible].
     * Earlier and later copies stay. Returns the one changed row, or null
     * when that occurrence is not in the stored parts.
     */
    fun replaceVisibleOccurrence(
        rows: List<Pair<String, String>>,
        oldVisible: String,
        replacement: String,
        occurrence: Int,
    ): Pair<String, String>? {
        if (occurrence < 0 || oldVisible.isEmpty()) return null
        var seen = 0
        for ((id, raw) in rows) {
            val parts = runCatching { JSONArray(raw) }.getOrNull() ?: continue
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") != "text") continue
                if (visible(part.optString("value")) != oldVisible) continue
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
        if (text.contains("<system-reminder>")) text = reminderRegex.replace(text, "")
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
     * Last visible text part becomes [translated]. Earlier visible text parts
     * keep only system-reminder / attachment markup. Tool and thinking parts
     * are not rewritten. Returns the rows whose JSON changed.
     */
    fun rewriteStoredParts(rows: List<Pair<String, String>>, translated: String): List<Pair<String, String>> {
        val loaded = rows.map { (id, raw) -> id to JSONArray(raw) }
        val hits = mutableListOf<Pair<Int, Int>>()
        loaded.forEachIndexed { row, (_, parts) ->
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") != "text") continue
                if (visible(part.optString("value")).isEmpty()) continue
                hits.add(row to i)
            }
        }
        if (hits.isEmpty()) {
            if (loaded.isEmpty()) return emptyList()
            val last = loaded.lastIndex
            loaded[last].second.put(JSONObject().put("type", "text").put("value", translated))
            return listOf(loaded[last].first to loaded[last].second.toString())
        }
        val lastHit = hits.last()
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
