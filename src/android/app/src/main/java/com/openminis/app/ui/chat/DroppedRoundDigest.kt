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
     * [T-digest-bounded-scan] Hard bound on RAW messages inspected per render.
     * Text-bearing candidates are counted separately: a run of 100 tool-result
     * carriers must not hide an older user instruction behind the scan budget.
     */
    const val MAX_SCAN_MESSAGES = 512

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
        // [T-digest-bounded-scan] Walk from the NEWEST dropped message and
        // stop as soon as the line/char caps are full, with a hard scan
        // bound. This runs on every request while a drop persists; scanning
        // all 3,000 dropped rounds to keep 24 lines was pure waste. Older
        // messages beyond the scan are reported as a count, not read.
        // [T-harness-config] The three caps are policy, not constants: a
        // per-session override must be able to change how much of the dropped
        // tail the digest may carry. Defaults are byte-identical to the
        // documented constants above.
        val config = com.openminis.app.agent.HarnessConfig.current()
        val kept = ArrayDeque<String>()
        var used = 0
        var scanned = 0
        var i = dropped.lastIndex
        while (i >= 0 && kept.size < config.digestMaxLines && scanned < config.digestScanMessages) {
            scanned++
            val line = lineFor(dropped[i])
            if (line != null) {
                if (kept.isNotEmpty() && used + line.length + 1 > config.digestMaxChars) break
                kept.addFirst(line)
                used += line.length + 1
            }
            i--
        }
        if (kept.isEmpty()) {
            // No text in the scanned window. If older messages remain, say so
            // instead of returning null (which hid the only remaining trace of
            // the drop); if the whole list was scanned, there is no text at all.
            return if (i >= 0) {
                header + "\n- (+${i + 1} older dropped messages)"
            } else {
                null
            }
        }
        val omitted = i + 1
        return buildString {
            append(header)
            for (line in kept) append('\n').append("- ").append(line)
            if (omitted > 0) append("\n- (+").append(omitted).append(" older dropped messages)")
        }
    }

    /** One digest line for a text-bearing message, or null to skip it. */
    private fun lineFor(msg: LLMMessage): String? {
        return when (msg.role) {
            LLMMessage.Role.USER -> {
                if (msg.contentParts.any { it is AgentContentPart.ToolResult }) return null
                if (HarnessNoteMarkers.isUserHarnessNote(msg)) return null
                val text = textOf(msg)
                if (text.isBlank()) null else "user: " + clip(text, USER_CHARS)
            }
            LLMMessage.Role.ASSISTANT -> {
                val text = textOf(msg)
                if (text.isBlank()) null else "assistant: " + clip(text, ASSISTANT_CHARS)
            }
            else -> null
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
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.length <= limit) return flat
        var cut = flat.take(limit)
        if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return "$cut…"
    }

    /** Hoisted: clip() runs up to MAX_SCAN_MESSAGES times per render. */
    private val WHITESPACE = Regex("\\s+")
}
