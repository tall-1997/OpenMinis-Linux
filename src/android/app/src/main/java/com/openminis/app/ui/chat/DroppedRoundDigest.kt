package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-postanchor-dropped-digest] Deterministic, bounded record of the rounds
 * [PostAnchorTail] evicted from the post-anchor region.
 *
 * WHY: the tail budget drops whole rounds that a compact summary does NOT
 * cover — the summary was written before those rounds existed. The old notice
 * claimed "the summary above covers them", which was false, and the only
 * handover was a tool-call ledger. User instructions and the model's own
 * decisions in those dropped rounds vanished silently; on device the model
 * then re-asked, re-planned, or re-did work it had already done.
 *
 * This digest keeps the two kinds of content that carry intent — real user
 * text and assistant text — as short verbatim excerpts. Tool calls/results are
 * deliberately NOT repeated here: [WorkingMemoryLedger] already records them
 * in a structured, one-line-per-call form, and duplicating them would spend
 * tokens twice.
 *
 * Pure and total: no I/O, no Android types, no LLM call. Bounded by
 * [MAX_CHARS]/[MAX_LINES] so it can never eat the budget it is compensating
 * for. Harness notes (`<system-reminder>` reminders, prior digests) are
 * skipped: replaying an old reminder would resurrect a stale objective.
 */
internal object DroppedRoundDigest {

    const val HEADER =
        "Dropped rounds digest — these rounds are NOT in the compact summary above; " +
            "only these excerpts survive. Do not redo work they show as done; " +
            "the working-memory note records their tool calls."

    /** Hard caps: a digest must stay a few hundred tokens. */
    const val MAX_CHARS = 3_000
    const val MAX_LINES = 24
    const val USER_CHARS = 400
    const val ASSISTANT_CHARS = 240

    /**
     * @return null when the dropped rounds carry no user/assistant text worth
     *   keeping (e.g. pure tool traffic) — the caller then keeps the plain
     *   count notice.
     */
    fun render(
        dropped: List<LLMMessage>,
        header: String = HEADER,
    ): String? {
        if (dropped.isEmpty()) return null
        val lines = ArrayList<String>()
        for (msg in dropped) {
            when (msg.role) {
                LLMMessage.Role.USER -> {
                    if (msg.contentParts.any { it is AgentContentPart.ToolResult }) continue
                    if (HarnessNoteMarkers.isUserHarnessNote(msg)) continue
                    val text = textOf(msg)
                    if (text.isBlank()) continue
                    lines += "user: " + clip(text, USER_CHARS)
                }
                LLMMessage.Role.ASSISTANT -> {
                    val text = textOf(msg)
                    if (text.isBlank()) continue
                    lines += "assistant: " + clip(text, ASSISTANT_CHARS)
                }
                else -> Unit
            }
        }
        if (lines.isEmpty()) return null
        // Keep the NEWEST lines within the caps (the rounds adjacent to the
        // kept tail are the ones the next step depends on), then emit oldest
        // first so the digest reads chronologically.
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (kept.size >= MAX_LINES) break
            if (kept.isNotEmpty() && used + line.length + 1 > MAX_CHARS) break
            kept.addFirst(line)
            used += line.length + 1
        }
        return buildString {
            append(header)
            for (line in kept) append('\n').append("- ").append(line)
            val omitted = lines.size - kept.size
            if (omitted > 0) append("\n- (+").append(omitted).append(" older dropped lines)")
        }
    }

    /** Content or the concatenated Text parts; tool-only shells yield "". */
    private fun textOf(msg: LLMMessage): String {
        if (msg.content.isNotBlank()) return msg.content.trim()
        return msg.contentParts
            .filterIsInstance<AgentContentPart.Text>()
            .joinToString(" ") { it.text }
            .trim()
    }

    private fun clip(text: String, limit: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= limit) return flat
        var cut = flat.take(limit)
        if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return "$cut…"
    }
}
