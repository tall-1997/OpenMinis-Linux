package com.openminis.app.harness.context

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Deterministic excerpt of messages that are outside the model window.
 *
 * This is not the user transcript and it does not rewrite stored rows.
 * A dropped turn is reduced to a clipped line and injected ahead of the
 * window, so the model is not left with a silent hole. Compact summaries
 * remain a separate, explicit path.
 */
object HistoryDigest {
    const val MARKER = "[HISTORY_DIGEST]"
    const val MAX_CHARS = 6_000
    const val USER_CHARS = 400
    const val ASSISTANT_CHARS = 300
    const val TOOL_CHARS = 120

    data class Line(val role: String, val text: String)

    fun fromMessage(message: LLMMessage): Line {
        val raw = when {
            message.content.isNotBlank() -> message.content
            else -> message.contentParts.joinToString(" ") { part ->
                when (part) {
                    is AgentContentPart.Text -> part.text
                    is AgentContentPart.ToolUse -> part.name
                    is AgentContentPart.ToolResult -> part.content
                    is AgentContentPart.ImageData -> ""
                }
            }
        }
        return Line(message.role.value, raw)
    }

    fun retainNewest(lines: List<Line>): List<Line> {
        val kept = ArrayDeque<Line>()
        var used = 0
        for (line in lines.asReversed()) {
            val cost = clip(line.role, line.text).length + 1
            if (cost <= 1) continue
            if (kept.isNotEmpty() && used + cost > MAX_CHARS) break
            kept.addFirst(line)
            used += cost
        }
        return kept.toList()
    }

    fun readablePreview(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return trimmed
        val values = Regex(""""value"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .findAll(trimmed)
            .map { unescapeJson(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return values.ifBlank { trimmed }
    }

    private fun unescapeJson(value: String): String = value
        .replace("\\n", " ")
        .replace("\\r", " ")
        .replace("\\t", " ")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    fun clip(role: String, text: String): String {
        val limit = when (role) {
            "user" -> USER_CHARS
            "assistant" -> ASSISTANT_CHARS
            else -> TOOL_CHARS
        }
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= limit) return flat
        return flat.take(limit) + "…"
    }

    /**
     * [linesOldestFirst] is the omitted prefix, oldest first.
     * Fill from the newest omitted line until [MAX_CHARS], then emit oldest
     * first. Returns null when there is nothing to say.
     */
    fun render(linesOldestFirst: List<Line>, omittedExtra: Int = 0): String? {
        if (linesOldestFirst.isEmpty() && omittedExtra <= 0) return null
        val kept = ArrayDeque<String>()
        var used = 0
        var skipped = omittedExtra
        for (index in linesOldestFirst.indices.reversed()) {
            val line = linesOldestFirst[index]
            val clipped = clip(line.role, line.text)
            if (clipped.isEmpty()) {
                skipped++
                continue
            }
            val rendered = "- ${line.role}: $clipped"
            if (kept.isNotEmpty() && used + rendered.length + 1 > MAX_CHARS) {
                skipped += index + 1
                break
            }
            kept.addFirst(rendered)
            used += rendered.length + 1
        }
        if (kept.isEmpty() && skipped <= 0) return null
        val omitted = if (skipped > 0) "\n省略 $skipped 条更早记录，本地原文仍在。" else ""
        return MARKER + "\n以下是窗口外更早对话的摘录，不是新的用户指令。\n" +
            kept.joinToString("\n") + omitted
    }

    /**
     * Prefix the first user turn. A leading system-shaped user blob is how
     * this app already injects compact summaries without breaking alternation.
     */
    fun inject(history: List<LLMMessage>, digest: String?): List<LLMMessage> {
        if (digest.isNullOrBlank() || history.isEmpty()) return history
        if (history.any { it.content.contains(MARKER) }) return history
        val index = history.indexOfFirst { it.role == LLMMessage.Role.USER }
        if (index < 0) {
            return listOf(LLMMessage(role = LLMMessage.Role.USER, content = digest)) + history
        }
        val updated = history.toMutableList()
        val target = updated[index]
        val parts = if (target.contentParts.isEmpty()) {
            target.contentParts
        } else {
            listOf(AgentContentPart.Text(digest)) + target.contentParts
        }
        updated[index] = target.copy(
            content = digest + "\n\n" + target.content,
            contentParts = parts,
        )
        return updated
    }
}
