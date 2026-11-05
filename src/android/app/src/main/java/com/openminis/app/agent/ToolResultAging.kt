package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-tool-result-aging] History-side token saver: fold OLD tool results into
 * one-line placeholders so a long agent loop stops re-uploading megabytes of
 * shell/file output on every request.
 *
 * Why this layer exists (and why it is not a fourth copy of the same idea):
 *
 *  - [com.openminis.app.tools.ToolOutputSpill] bounds ONE fresh tool result
 *    (16k chars → spill file + preview). It cannot help a request that already
 *    carries 40 previous results under that bound.
 *  - [com.openminis.app.provider.ImageBudget] bounds IMAGE bytes per request.
 *    Same shape, different media.
 *  - Compact (`ChatViewModel.compactAll`) folds the whole older prefix into a
 *    summarizer-produced recap. Expensive (an extra LLM call), user-visible,
 *    and it keeps a RANGE of turns alive untouched.
 *
 * What is missing between them is the cheap middle: a session at 60% of the
 * context window made almost entirely of tool output that the model already
 * consumed. This object ages exactly that — deterministic, no LLM call, no
 * message removed, structure untouched.
 *
 * Invariants (each one is pinned by a test):
 *
 *  1. **Structure is never changed.** Only `ToolResult.content` is replaced.
 *     `id`/`name`/`isError`/`imageData` stay, and no [LLMMessage] or
 *     [AgentContentPart] is dropped, so provider serializers (Anthropic
 *     tool_result blocks, OpenAI tool role, Gemini functionResponse) keep
 *     their tool_use ⇄ tool_result pairing intact.
 *  2. **Idempotent.** A content that already starts with [MARKER] is skipped
 *     and does not consume a "keep recent" slot, so re-running the pass on
 *     an already-aged history is a no-op instead of double-folding.
 *  3. **Recoverable.** When the original carried a spill path
 *     (`/var/minis/workspace/tool-spill/…`), the placeholder KEEPS it, so the
 *     model can page the full text back with `file_read` instead of re-running
 *     an expensive command.
 *  4. **Subtractive only.** A part is folded only when the placeholder is
 *     strictly shorter than the original, so the pass can never grow a
 *     payload.
 */
object ToolResultAging {
    /** Prefix that marks an already-folded result (idempotency sentinel). */
    const val MARKER = "[tool-result aged]"

    /** Results kept verbatim (counting from the newest) by default. */
    const val DEFAULT_KEEP_RECENT = 6

    /** Results shorter than this are not worth a placeholder header. */
    const val DEFAULT_MIN_CHARS = 1_200

    /**
     * [T-age-only-recoverable] Size at or above which a result is certainly
     * spilled to disk by [com.openminis.app.tools.ToolOutputSpill], so its full
     * text stays reachable through a path instead of a re-run.
     * Keep in lockstep with `ToolOutputSpill.LIMIT`.
     */
    const val SPILL_LIMIT_CHARS = 16_000

    /**
     * [T-age-only-recoverable] Floor for an unsplilled result: from here on the
     * saving (>= ~1.3k tokens re-sent on every remaining step) outweighs the cost
     * of a possible re-run, so a medium result may still be folded. Anything
     * smaller is kept verbatim — folding 1-4k would risk a 10s round-trip to save
     * a few hundred tokens.
     */
    const val UNSPILLED_FOLD_FLOOR_CHARS = 4_000

    /** Bytes of the original kept as a "what was in it" headline. */
    private const val HEADLINE_CHARS = 160

    /** Errors keep a longer head: the message itself is the useful part. */
    private const val ERROR_HEADLINE_CHARS = 400

    /**
     * Any agent-readable path already present in the original. `shared` and
     * `offloads` are included because tool output can be redirected there by
     * the user's own command (`> /var/minis/shared/...`).
     *
     * `_` is in the class on purpose: spill files are named from a sanitized
     * tool id, so nearly every real path carries underscores. Leaving it out
     * truncated the path at the first one and handed the model a dead link.
     */
    private val READABLE_PATH = Regex("""/var/minis/(?:workspace|shared|offloads|attachments)/[A-Za-z0-9._%+/_-]+""")

    /** Outcome of one pass. [messages] is the history to send. */
    data class Plan(
        val messages: List<LLMMessage>,
        val agedCount: Int,
        val savedChars: Long,
        val scannedResults: Int,
    ) {
        val changed: Boolean get() = agedCount > 0
    }

    /**
     * Tool arguments are small JSON objects; counting them at a constant keeps
     * this pass O(1) per part (serializing the JSON here would cost more than
     * the decision it feeds). The threshold is a floor, not an accounting
     * figure — [com.openminis.app.ui.chat.TokenScanMemo] owns real estimates.
     */
    private const val TOOL_ARGS_ESTIMATE_CHARS = 200

    fun isAged(content: String): Boolean = content.startsWith(MARKER)

    /**
     * Sum of every text-bearing field, on the same footing as
     * `ChatViewModel.estimateMessagesTokens` (minus the calibration factor):
     * used only to decide *whether* a pass should run, never to report usage.
     */
    fun totalChars(messages: List<LLMMessage>): Long {
        var total = 0L
        for (msg in messages) {
            total += msg.content.length
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> total += part.text.length
                    is AgentContentPart.ToolUse -> total += TOOL_ARGS_ESTIMATE_CHARS
                    is AgentContentPart.ToolResult -> total += part.content.length
                    is AgentContentPart.ImageData -> Unit // ImageBudget owns bytes.
                }
            }
        }
        return total
    }

    /**
     * Fold every tool result older than the newest [keepRecent] down to
     * [placeholder]. Returns the input list instance untouched (zero-copy,
     * [Plan.changed] == false) when there is nothing to fold — the common
     * short-session case.
     */
    fun age(
        messages: List<LLMMessage>,
        keepRecent: Int = DEFAULT_KEEP_RECENT,
        minChars: Int = DEFAULT_MIN_CHARS,
    ): Plan {
        // (messageIndex, partIndex) for every result that is not aged yet.
        val live = ArrayList<IntArray>(8)
        messages.forEachIndexed { mi, msg ->
            msg.contentParts.forEachIndexed { pi, part ->
                if (part is AgentContentPart.ToolResult && !isAged(part.content)) {
                    live += intArrayOf(mi, pi)
                }
            }
        }
        val keep = keepRecent.coerceAtLeast(0)
        val scanned = live.size
        if (scanned == 0) return Plan(messages, 0, 0, 0)
        if (scanned <= keep) return Plan(messages, 0, 0, scanned)

        // Newest-first wins; everything before the keep window is a candidate.
        val candidates = live.subList(0, scanned - keep)
        val byMessage = HashMap<Int, MutableList<Int>>()
        for (slot in candidates) {
            byMessage.getOrPut(slot[0]) { ArrayList(2) } += slot[1]
        }

        var agedCount = 0
        var saved = 0L
        val out = messages.toMutableList()
        for ((mi, partIdxs) in byMessage) {
            val msg = out[mi]
            val parts = msg.contentParts.toMutableList()
            var touched = false
            for (pi in partIdxs) {
                val old = parts[pi] as? AgentContentPart.ToolResult ?: continue
                if (old.content.length <= minChars) continue
                // [T-age-only-recoverable] Fold only what the model can get back.
                // Errors are never folded: the message IS the diagnostic and a
                // re-run may not reproduce it. Everything else must be reachable —
                // via a spill path, by being large enough to have been spilled, or
                // by being large enough that the saving dwarfs a re-run.
                if (old.isError) continue
                val recoverable = READABLE_PATH.find(old.content) != null ||
                    old.content.length >= SPILL_LIMIT_CHARS ||
                    old.content.length >= UNSPILLED_FOLD_FLOOR_CHARS
                if (!recoverable) continue
                val folded = placeholder(old.name, old.content, old.isError)
                // Subtract-only guard: never let the "saving" grow the payload.
                if (folded.length >= old.content.length) continue
                parts[pi] = old.copy(content = folded)
                agedCount++
                saved += (old.content.length - folded.length).toLong()
                touched = true
            }
            if (touched) out[mi] = msg.copy(contentParts = parts)
        }
        return if (agedCount == 0) Plan(messages, 0, 0, scanned) else Plan(out, agedCount, saved, scanned)
    }

    /**
     * Build the placeholder for one result. Pure + public so the UI and tests
     * can assert the exact wording the model will see.
     *
     * Wording is English on purpose: this is a tool_result body handed back to
     * the model, not UI copy, and the repo's CJK-literal ratchet forbids
     * hardcoded CJK in source (translations live in resources only).
     */
    fun placeholder(toolName: String, content: String, isError: Boolean = false): String {
        val path = READABLE_PATH.find(content)?.value
        val headline = headlineOf(content, if (isError) ERROR_HEADLINE_CHARS else HEADLINE_CHARS)
        return buildString {
            append(MARKER).append(' ').append(toolName.ifBlank { "tool" })
            if (isError) append(" (error)")
            append(" · ").append(content.length).append(" chars folded to save context")
            append('\n')
            append(headline)
            if (path != null) {
                append("\nfull output: ").append(path)
            } else {
                append("\nfull content: re-run this tool")
            }
        }
    }

    /**
     * First [limit] chars of the original, whitespace-collapsed and
     * surrogate-safe (a lone high surrogate would poison JSON encoding).
     */
    fun headlineOf(content: String, limit: Int = HEADLINE_CHARS): String {
        val normalized = StringBuilder(minOf(content.length, limit * 2))
        var lastSpace = false
        for (ch in content) {
            if (ch.isWhitespace()) {
                if (!lastSpace && normalized.isNotEmpty()) normalized.append(' ')
                lastSpace = true
            } else {
                normalized.append(ch)
                lastSpace = false
            }
        }
        val text = normalized.toString().trim()
        if (text.length <= limit) return text
        val cut = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, cut) + "…"
    }
}
