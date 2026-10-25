package com.openminis.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keyword-triggered lore entries. A persona file is still the standing voice;
 * a world-book entry is injected only when a recent message matches one of
 * its keywords (or its regex). Constant entries are the explicit opt-in for
 * "always include this", so a long setting file no longer rides every turn.
 */
object WorldBook {
    private const val FILE_NAME = "world-book.json"
    private const val MAX_INJECT_CHARS = 4_000

    data class Entry(
        val id: String,
        val name: String,
        val content: String,
        val keywords: List<String>,
        val enabled: Boolean = true,
        val constant: Boolean = false,
        val useRegex: Boolean = false,
    )

    fun file(context: Context): File =
        File(context.filesDir, "minis-global/memory/$FILE_NAME")

    fun load(context: Context): List<Entry> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val content = o.optString("content").trim()
                    if (content.isEmpty()) continue
                    val keys = o.optJSONArray("keywords")?.let { a ->
                        buildList {
                            for (k in 0 until a.length()) {
                                a.optString(k).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                            }
                        }
                    }.orEmpty()
                    add(
                        Entry(
                            id = o.optString("id").ifBlank { "wb-$i" },
                            name = o.optString("name").ifBlank { "entry-$i" },
                            content = content,
                            keywords = keys,
                            enabled = o.optBoolean("enabled", true),
                            constant = o.optBoolean("constant", false),
                            useRegex = o.optBoolean("useRegex", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("content", e.content)
                    .put("keywords", JSONArray(e.keywords))
                    .put("enabled", e.enabled)
                    .put("constant", e.constant)
                    .put("useRegex", e.useRegex),
            )
        }
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(arr.toString())
    }

    /**
     * @param recentText the latest user turn plus a short tail of the chat.
     *   Matching only the latest turn would miss a setting the user named
     *   one message earlier and then referred to as "that".
     */
    fun injection(context: Context, recentText: String): String {
        val hits = load(context).filter { it.enabled && matches(it, recentText) }
        if (hits.isEmpty()) return ""
        val body = buildString {
            append("World book (injected only because a keyword matched this turn, or the entry is marked constant; do not invent entries that are not listed):\n")
            var used = 0
            for (e in hits) {
                val block = "- ${e.name}: ${e.content.trim()}\n"
                if (used + block.length > MAX_INJECT_CHARS) break
                append(block)
                used += block.length
            }
        }
        return "\n\n$body"
    }

    internal fun matches(entry: Entry, text: String): Boolean {
        if (entry.constant) return true
        if (entry.keywords.isEmpty() || text.isBlank()) return false
        val hay = text.lowercase()
        return entry.keywords.any { key ->
            val needle = key.trim()
            if (needle.isEmpty()) return@any false
            if (entry.useRegex) {
                runCatching { Regex(needle, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)
            } else {
                hay.contains(needle.lowercase())
            }
        }
    }
}
