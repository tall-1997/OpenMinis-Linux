package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-android-progressive-trim] Pure projection that shrinks an OUTGOING
 * history payload progressively as context pressure rises, BEFORE
 * compaction becomes necessary.
 *
 * Why: compaction (compactAll) is the last resort — it spends an LLM call,
 * drops verbatim detail, and greys out the transcript. Between compactions
 * the payload grows unbounded even though most of its bulk is content the
 * model no longer needs verbatim:
 *
 *   1. OLD `reasoningContent` — replayed verbatim by every provider path
 *      (OpenAI `reasoning_content` echo, Anthropic compat-proxy thinking
 *      blocks). Reasoning older than the last few turns is pure token cost.
 *   2. LARGE old tool outputs — a 50 KB shell log from 60 messages ago is
 *      still sent in full on every subsequent turn.
 *
 * Progressive trimming removes exactly that bulk, in tiers keyed off the
 * untrimmed estimate vs. the context window:
 *
 *   - < 50% of window  → Level.NONE (no trimming)
 *   - ≥ 50%            → STRIP_OLD_THINKING
 *   - ≥ 70%            → + STUB_LARGE_TOOL_OUTPUTS (offload-backed)
 *   - ≥ 85%            → + MERGE_OLD_STEPS (collapse old tool iterations)
 *
 * [T-wave-e-progressive-reclaim, port upstream 1.16 ContextTrimPlanner] The
 * ladder is ordered cheapest-first: reasoning echo (pure token cost) → big
 * tool outputs (offloaded to the session workspace, stub says the model can
 * `file_read` the full text back) → old consecutive tool iterations merged
 * into one index block each (upstream L4 "collapse to index"). The compact
 * threshold (ContextPolicy, default 90%) sits ABOVE the trim tiers, so
 * trimming gets a chance to pull the payload back under the compact line
 * first. Levels are computed from the UNTRIMMED estimate — monotone in
 * history growth — so the projection cannot oscillate between turns (a
 * usage-based level would: trim → smaller usage → level drops → untrimmed
 * again → usage rises …).
 *
 * Safety invariants (all provider-agnostic):
 *   - Never removes a message or breaks a tool_use/tool_result pair —
 *     stubbing keeps the ToolResult part with its id, so pairing logic
 *     (ToolPairing / dropOrphanedToolParts / ResponsesToolPairing) is
 *     unaffected. MERGE_OLD_STEPS is the one tier that DROPS messages, and
 *     it only ever drops a whole iteration (assistant + its tool-result
 *     carriers together), replacing them with an index block — no orphaned
 *     tool_use survives.
 *   - Never touches anything inside the CURRENT round (after the last
 *     user message) or the last [PROTECTED_RECENT_MESSAGES] messages.
 *   - Never touches user content or assistant text (except the index block
 *     appended to the index host message) — only reasoning, oversized tool
 *     outputs and whole old iterations.
 *   - Stripping `reasoningContent` degrades to `null`, which every echo
 *     path already handles as "reasoning never captured" (OpenAI sends
 *     `""` field-presence placeholder; Anthropic compat proxies accept an
 *     empty thinking block) — the exact shape of pre-thinking-era history.
 *
 * Pure apart from [Offloader], which the caller backs with real disk writes
 * (ToolOutputSpill's session workspace). Unit-testable without Robolectric.
 * The level decision [levelFor] is likewise pure.
 */
internal object ProgressiveTrim {
    /** At/above this fraction of the window, strip old thinking. */
    internal const val LEVEL1_WINDOW_FRACTION: Double = 0.50

    /** At/above this fraction, additionally stub large old tool outputs. */
    internal const val LEVEL2_WINDOW_FRACTION: Double = 0.70

    /** At/above this fraction, additionally merge old tool iterations. */
    internal const val LEVEL3_WINDOW_FRACTION: Double = 0.85

    /** Messages at the tail are never trimmed, regardless of level. */
    internal const val PROTECTED_RECENT_MESSAGES: Int = 40

    /**
     * Assistant turns whose reasoningContent is always kept verbatim.
     * Recent reasoning carries continuity ("what was I just doing");
     * older reasoning is noise. The current round is protected separately
     * (last-user-message rule), so this only governs completed rounds.
     */
    internal const val PROTECTED_RECENT_ASSISTANT_MESSAGES: Int = 8

    /** ToolResult content longer than this (chars) is stubbed at L2. */
    internal const val TOOL_RESULT_STUB_THRESHOLD_CHARS: Int = 2000

    /** Head of a stubbed tool result kept verbatim, for recognition. */
    internal const val TOOL_RESULT_STUB_HEAD_CHARS: Int = 160

    /**
     * [T-wave-e-progressive-reclaim] Newest tool iterations MERGE_OLD_STEPS
     * never touches (upstream `keepRecentIterations`): the last few
     * iterations carry the task's working state.
     */
    internal const val MERGE_KEEP_RECENT_ITERATIONS: Int = 6

    /** [T-wave-e-progressive-reclaim] Index block caps the listed steps (upstream INDEX_MAX_LINES). */
    internal const val MERGE_INDEX_MAX_LINES: Int = 60

    /**
     * Writes [content] to durable storage and returns the guest path the
     * model can `file_read`, or null when it could not (the stub then says
     * the text is gone instead of pointing at a nonexistent file).
     */
    fun interface Offloader {
        fun offload(content: String, toolId: String, toolName: String): String?
    }

    /** A no-disk offloader: stubs never claim a retrievable path. */
    internal val NO_OFFLOAD = Offloader { _, _, _ -> null }

    enum class Level {
        NONE,
        STRIP_OLD_THINKING,
        STUB_LARGE_TOOL_OUTPUTS,
        MERGE_OLD_STEPS,
    }

    /**
     * Decide the trim level from the UNTRIMMED token estimate and the
     * model's context window. Null/zero window or non-positive estimate →
     * NONE (no basis for a ratio; the compact guard still protects the
     * ceiling).
     */
    fun levelFor(untrimmedTokens: Int, windowTokens: Int?): Level {
        if (windowTokens == null || windowTokens <= 0 || untrimmedTokens <= 0) {
            return Level.NONE
        }
        val ratio = untrimmedTokens.toDouble() / windowTokens.toDouble()
        return when {
            // [T-no-merge-old-steps] MERGE_OLD_STEPS is never selected. It is
            // the one tier that DROPS whole tool iterations and replaces them
            // with an index block, and the transcript is rebuilt from the same
            // history: the user's tool cards disappeared from finished rounds
            // and the reasoning between them read as never having happened.
            // Levels 1-2 keep every message and every tool id, so pairing and
            // the rendered cards survive; only bulk is reduced.
            ratio >= LEVEL2_WINDOW_FRACTION -> Level.STUB_LARGE_TOOL_OUTPUTS
            ratio >= LEVEL1_WINDOW_FRACTION -> Level.STRIP_OLD_THINKING
            else -> Level.NONE
        }
    }

    /**
     * Apply [level] to [messages]. Returns the SAME list instance when
     * nothing changes (callers can identity-compare to skip downstream
     * work); otherwise a new list with copies of only the affected
     * messages.
     */
    fun trim(
        messages: List<LLMMessage>,
        level: Level,
        offloader: Offloader = NO_OFFLOAD,
    ): List<LLMMessage> {
        if (level == Level.NONE) return messages
        if (messages.size <= PROTECTED_RECENT_MESSAGES) return messages

        // The current round (everything after the last user message) is
        // never trimmed: its tool results and reasoning are actively in
        // play, and Anthropic requires the thinking preceding a tool_use
        // in the CURRENT turn sequence to be preserved.
        val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.Role.USER }

        // Indices of the last N assistant messages — protected reasoning
        // zone (recent reasoning aids continuity).
        val protectedAssistant = HashSet<Int>(PROTECTED_RECENT_ASSISTANT_MESSAGES)
        var seen = 0
        for (i in messages.indices.reversed()) {
            if (messages[i].role == LLMMessage.Role.ASSISTANT) {
                protectedAssistant.add(i)
                seen += 1
                if (seen >= PROTECTED_RECENT_ASSISTANT_MESSAGES) break
            }
        }

        val firstTrimmableIdx = messages.size - PROTECTED_RECENT_MESSAGES
        var changed = false
        val out = ArrayList<LLMMessage>(messages.size)
        for ((i, msg) in messages.withIndex()) {
            val inProtectedTail = i >= firstTrimmableIdx
            val inCurrentRound = lastUserIdx >= 0 && i >= lastUserIdx
            if (inProtectedTail || inCurrentRound) {
                out.add(msg)
                continue
            }
            var working = msg
            // L1+: strip old reasoning.
            if (working.role == LLMMessage.Role.ASSISTANT &&
                working.reasoningContent != null &&
                i !in protectedAssistant
            ) {
                working = working.copy(reasoningContent = null)
                changed = true
            }
            // L2: offload oversized tool outputs. Bytes (imageData) are NOT
            // trimmed here: ImageBudget already elides old images at the
            // provider boundary with a re-fetchable path placeholder;
            // double-handling would race its budget math.
            if (level >= Level.STUB_LARGE_TOOL_OUTPUTS && working.contentParts.isNotEmpty()) {
                var partsChanged = false
                val stubbedParts = working.contentParts.map { part ->
                    if (part is AgentContentPart.ToolResult &&
                        part.content.length > TOOL_RESULT_STUB_THRESHOLD_CHARS
                    ) {
                        partsChanged = true
                        part.copy(content = offloadStub(part, offloader))
                    } else {
                        part
                    }
                }
                if (partsChanged) {
                    working = working.copy(contentParts = stubbedParts)
                    changed = true
                }
            }
            out.add(working)
        }
        if (level < Level.MERGE_OLD_STEPS) return if (changed) out else messages

        // [T-wave-e-progressive-reclaim] L3: merge old consecutive tool
        // iterations into an index block (upstream ContextTrimPlanner L4).
        // Input here is the L1/L2-processed list; iteration boundaries are
        // unaffected by both (stubbing keeps part ids).
        val merged = mergeOldSteps(out, lastUserIdx, firstTrimmableIdx)
        return merged ?: if (changed) out else messages
    }

    // ── L2: offload-backed stub ─────────────────────────────────────────

    private fun offloadStub(part: AgentContentPart.ToolResult, offloader: Offloader): String {
        val content = part.content
        val path = offloader.offload(content, part.id, part.name)
        val head = content.take(TOOL_RESULT_STUB_HEAD_CHARS)
        return if (!path.isNullOrEmpty()) {
            buildString {
                append("[tool output offloaded to save context — ").append(content.length)
                append(" chars. Full output saved to ").append(path)
                append(" — use file_read to retrieve it if needed.]\n")
                append(head)
            }
        } else {
            // No disk to offload to: tell the truth (the full text is gone),
            // the same contract ToolOutputSpill.cappedPreview keeps.
            buildString {
                append("[tool output trimmed for context: ").append(content.length)
                append(" chars total, first ").append(TOOL_RESULT_STUB_HEAD_CHARS)
                append(" kept]\n")
                append(head)
            }
        }
    }

    // ── L3: merge old tool iterations ───────────────────────────────────

    /**
     * One iteration: an assistant message plus the user-role messages that
     * carry ITS tool results (upstream ContextTrimPlanner.Iteration). A
     * carrier that also holds text (a compaction summary appended after the
     * results) is [Iteration.pinned] — it can be shrunk elsewhere but never
     * dropped here.
     */
    internal data class Iteration(
        val assistantIdx: Int,
        val carrierIdx: List<Int>,
        val pinned: Boolean,
        /** True when at least one carrier holds a ToolResult for this iteration's ToolUse ids. */
        val paired: Boolean,
    )

    internal fun iterations(messages: List<LLMMessage>): List<Iteration> {
        val out = mutableListOf<Iteration>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m.role != LLMMessage.Role.ASSISTANT) { i++; continue }
            val useIds = m.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (useIds.isEmpty()) { i++; continue }
            val ids = useIds.mapTo(HashSet()) { it.id }
            val carriers = mutableListOf<Int>()
            var pinned = false
            var paired = false
            var j = i + 1
            while (j < messages.size) {
                val c = messages[j]
                if (c.role != LLMMessage.Role.USER) break
                val results = c.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                if (results.isEmpty() || results.none { it.id in ids }) break
                carriers += j
                paired = true
                if (c.contentParts.any { it !is AgentContentPart.ToolResult }) pinned = true
                j++
            }
            out += Iteration(i, carriers, pinned, paired)
            i = maxOf(i + 1, j)
        }
        return out
    }

    /**
     * Collapse the oldest iterations (beyond [MERGE_KEEP_RECENT_ITERATIONS],
     * and only ones fully inside the trimmable prefix / outside the current
     * round / unpinned) into an index block appended to the last plain user
     * message before the collapsed range — upstream's "index host". Returns
     * null when nothing was collapsible (caller keeps the input).
     */
    internal fun mergeOldSteps(
        messages: List<LLMMessage>,
        lastUserIdx: Int,
        firstTrimmableIdx: Int,
    ): List<LLMMessage>? {
        val its = iterations(messages)
        if (its.isEmpty()) return null
        val keepFrom = (its.size - MERGE_KEEP_RECENT_ITERATIONS).coerceAtLeast(0)
        val collapsible = its.take(keepFrom).filter { it.paired && !it.pinned }
            .filter { it.assistantIdx < firstTrimmableIdx }
            .filter { it.carrierIdx.all { c -> c < firstTrimmableIdx } }
            .filter { lastUserIdx < 0 || it.assistantIdx < lastUserIdx }
        if (collapsible.isEmpty()) return null
        val dropIdx = collapsible.flatMapTo(HashSet()) { listOf(it.assistantIdx) + it.carrierIdx }
        val firstDropped = collapsible.minOf { it.assistantIdx }
        // The index host: the last plain user message BEFORE the collapsed
        // range that survives (not itself dropped). None → prepend a
        // synthetic user turn (upstream does the same).
        var hostAt = -1
        for (i in 0 until firstDropped) {
            if (i in dropIdx) continue
            if (messages[i].role == LLMMessage.Role.USER) hostAt = i
        }
        val index = indexText(messages, collapsible)
        val out = ArrayList<LLMMessage>(messages.size - dropIdx.size)
        var indexInserted = false
        for ((i, msg) in messages.withIndex()) {
            if (i in dropIdx) continue
            out.add(msg)
            if (i == hostAt) {
                out.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = index,
                        contentParts = listOf(AgentContentPart.Text(index)),
                    ),
                )
                indexInserted = true
            }
        }
        if (!indexInserted) {
            out.add(
                0,
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = index,
                    contentParts = listOf(AgentContentPart.Text(index)),
                ),
            )
        }
        return out
    }

    /**
     * Index of collapsed iterations, newest [MERGE_INDEX_MAX_LINES] listed,
     * the rest counted (upstream ContextTrimPlanner.indexText). The model is
     * told the full records stay in the chat history and big outputs are on
     * disk at the paths shown — do not redo a step listed as ok.
     */
    internal fun indexText(messages: List<LLMMessage>, collapsed: List<Iteration>): String {
        val lines = mutableListOf<String>()
        val counts = linkedMapOf<String, Int>()
        for ((n, it) in collapsed.withIndex()) {
            val asst = messages[it.assistantIdx]
            val results = it.carrierIdx
                .flatMap { c -> messages[c].contentParts.filterIsInstance<AgentContentPart.ToolResult>() }
                .associateBy { it.id }
            for (u in asst.contentParts.filterIsInstance<AgentContentPart.ToolUse>()) {
                counts[u.name] = (counts[u.name] ?: 0) + 1
                val r = results[u.id]
                val status = when { r == null -> "no result"; r.isError -> "error"; else -> "ok" }
                val path = r?.content?.let { c ->
                    Regex("saved to: (\\S+)").find(c)?.groupValues?.getOrNull(1)
                        ?: Regex("saved to (\\S+)").find(c)?.groupValues?.getOrNull(1)
                }
                lines += "#${n + 1} ${u.name} → $status${path?.let { " ($it)" } ?: ""}"
            }
        }
        if (lines.isEmpty()) return ""
        val shown = lines.takeLast(MERGE_INDEX_MAX_LINES)
        val hidden = lines.size - shown.size
        val summary = counts.entries.joinToString(", ") { "${it.key} ${it.value}" }
        return buildString {
            append("[Minis context] ${collapsed.size} earlier step(s) of this task were merged to save context. ")
            append("Their full records stay in the chat history; large outputs are on disk at the paths shown. ")
            append("Do not redo a step listed as ok - use file_read on its output path if you need the details. ")
            append("Tool calls merged: $summary.")
            if (hidden > 0) append(" ($hidden older line(s) not listed)")
            shown.forEach { append("\n"); append(it) }
        }
    }
}
