package com.openminis.app.ui.chat

/**
 * [T-working-memory-ledger] Host-maintained per-session ledger of tool work,
 * rendered ONLY when the postAnchor tail budget evicts rounds that were never
 * summarized (evict-then-deliver, same contract as [NoticeLedgerStore]).
 *
 * WHY: the tail budget drops unsummarized rounds (compact never fired for
 * them — the trimmed request sits under the threshold), and walkBack returns
 * an empty preAnchor in agent-dense sessions. The model then sees a stale
 * summary + a thin tail and repeats finished work. This ledger is the host's
 * mechanical one-line-per-round record of exactly what was dropped, delivered
 * once (never per-turn — per-turn injection was the repeated-announcement bug
 * that #315/#316/#330 removed), and then carried by history like every other
 * committed notice.
 *
 * Folding: consecutive entries with the same (tool, argsDigest, resultHead)
 * collapse into one "×N" line, so a repeat loop is visible as a count instead
 * of N lines (self-evident repetition, not a nudge).
 *
 * Not prompt guidance, not a summary: factual one-line records. No model
 * cost, no latency, survives compaction because it is re-injected on demand.
 */
internal class WorkingMemoryLedger {

    data class Entry(
        val turn: Int,
        val tool: String,
        /**
         * Opaque identity used ONLY for folding (same dispatch = same work).
         * Never render this to the model: a SHA prefix cannot tell it which
         * file or command was touched, so a hash-only handover reads as
         * "something happened" and the model repeats the work.
         */
        val argsDigest: String,
        /**
         * Human-readable argument summary (path / command / query), produced
         * by [WorkingMemoryArgs]. This is the field that makes the handover
         * actionable.
         */
        val argsSummary: String,
        val resultHead: String,
    )

    private val entries = ArrayList<Entry>()

    /**
     * [T-working-memory-newest-first] Per-entry delivery flags. A cursor
     * cannot express "the newest 40 were delivered" once more entries are
     * appended (the delivered region stops being the tail), and the newest
     * evicted rounds are exactly the ones the next step must not redo, so
     * delivery takes the newest undelivered entries first.
     */
    private val delivered = ArrayList<Boolean>()

    @Synchronized
    fun append(
        turn: Int,
        tool: String,
        argsDigest: String,
        argsSummary: String,
        resultHead: String,
    ) {
        entries.add(
            Entry(
                turn = turn,
                tool = tool,
                argsDigest = argsDigest,
                argsSummary = com.openminis.app.util.TextClip.safeTake(
                    argsSummary,
                    ARGS_SUMMARY_CHARS,
                ),
                // [T-working-memory-frame-safe] The line is committed inside
                // a <system-reminder> frame; raw tool output can contain a
                // closing tag or newlines that would end the frame early and
                // leak subsequent lines into user-visible previews.
                resultHead = com.openminis.app.util.TextClip.safeTake(
                    WorkingMemoryArgs.oneLine(resultHead),
                    RESULT_HEAD_CHARS,
                ),
            ),
        )
        delivered.add(false)
        if (entries.size > MAX_ENTRIES) {
            // Fold the oldest run of entries into one marker line: drop the
            // first (MAX_ENTRIES / 2) entries but keep their count visible.
            val drop = MAX_ENTRIES / 2
            val folded = entries.subList(0, drop)
            // [T-working-memory-fold-sample] Folding undelivered records must
            // not reduce them to a bare count: keep a short sample of what
            // the oldest rounds were about, so the handover still names work.
            val sample = folded.asReversed().take(FOLD_SAMPLE_ENTRIES)
                .joinToString(", ") {
                    val label = it.argsSummary.ifBlank { "no-args" }
                    "${it.tool}($label)"
                }
            val sampleLabel = com.openminis.app.util.TextClip.safeTake(
                WorkingMemoryArgs.oneLine("sample: $sample"),
                ARGS_SUMMARY_CHARS,
            )
            val foldLine = Entry(
                turn = folded.first().turn,
                tool = FOLD_TOOL,
                argsDigest = "rounds ${folded.first().turn}–${folded.last().turn}",
                argsSummary = if (sample.isBlank()) {
                    "rounds ${folded.first().turn}–${folded.last().turn}"
                } else {
                    sampleLabel
                },
                // Honest wording: compact may never have run, so pointing at a
                // summary that does not cover these rounds is a false lead.
                resultHead = "${folded.size} earlier tool rounds folded (records beyond this point)",
            )
            entries.subList(0, drop).clear()
            delivered.subList(0, drop).clear()
            entries.add(0, foldLine)
            delivered.add(0, false)
        }
    }

    /**
     * One-line-per-entry rendering of entries not yet delivered to this
     * session's transcript, or null when everything delivered is still
     * visible (nothing new to hand over).
     */
    @Synchronized
    fun renderUndelivered(): String? {
        val undeliveredIndices = entries.indices.filter { !delivered[it] }
        if (undeliveredIndices.isEmpty()) return null
        // Cap the handover: a 400-line wall invites the model to narrate about
        // it instead of working. Deliver the NEWEST entries first — the
        // rounds just evicted sit next to the kept tail and are what the next
        // step must not redo; the remainder rides the next eviction.
        val chosen = undeliveredIndices.takeLast(minOf(MAX_DELIVERY_LINES, undeliveredIndices.size))
        val delivering = chosen.map { entries[it] }
        val lines = ArrayList<String>(delivering.size)
        var i = 0
        while (i < delivering.size) {
            val e = delivering[i]
            val label = e.argsSummary.ifBlank { e.argsDigest }
            var run = 1
            while (i + run < delivering.size &&
                delivering[i + run].tool == e.tool &&
                delivering[i + run].argsDigest == e.argsDigest &&
                delivering[i + run].resultHead == e.resultHead
            ) run++
            lines += if (run == 1) {
                "turn ${e.turn}: ${e.tool}($label) → ${e.resultHead}"
            } else {
                "turns ${e.turn}–${delivering[i + run - 1].turn}: ${e.tool}($label) → same result ×$run"
            }
            i += run
        }
        for (idx in chosen) delivered[idx] = true
        val body = lines.joinToString("\n")
        val remaining = undeliveredIndices.size - chosen.size
        val head = if (remaining > 0) "($remaining older tool rounds pending — delivered on later evictions)\n" else ""
        return "[working memory — one-line record of tool rounds no longer in context]\n$head$body"
    }

    @Synchronized
    fun size(): Int = entries.size

    /** True when undelivered entries exist. */
    @Synchronized
    fun hasUndelivered(): Boolean = delivered.any { !it }

    internal companion object {
        internal const val RESULT_HEAD_CHARS = 120
        internal const val ARGS_SUMMARY_CHARS = 120
        internal const val MAX_ENTRIES = 600
        internal const val FOLD_TOOL = "earlier-rounds"

        /** Per-delivery cap: a wall of records invites narration. */
        internal const val MAX_DELIVERY_LINES = 40

        /** [T-working-memory-fold-sample] Identities kept in a fold marker. */
        internal const val FOLD_SAMPLE_ENTRIES = 3

        /** [T-working-memory-store-bound] Live per-session ledgers kept. */
        internal const val MAX_LIVE_LEDGERS = 128
    }
}

/**
 * Human-readable argument summary for a working-memory record.
 *
 * WHY this exists: the ledger used to render the truncated SHA-256 of the
 * arguments. After an eviction the model saw `file_edit(9f3a1b2c)` and
 * `shell_execute(4d2e8f01)` — it could not tell which file or command the
 * record referred to, so it re-ran the work instead of using the handover.
 * A ledger line is only worth its tokens when it names the thing that was
 * touched.
 *
 * Reuses [resolveToolDetail] (the same path/command/query extraction the tool
 * cards show) so there is ONE definition of "what a tool call was about".
 * Falls back to compact `key=value` pairs for tools the card resolver does not
 * cover (MCP/online tools), and never returns a multi-line string: the note is
 * delivered inside a `<system-reminder>` frame.
 *
 * Pure: org.json + string ops only, pinned by WorkingMemoryLedgerTest.
 */
internal object WorkingMemoryArgs {

    /** Framing tags that must never appear inside a committed host note. */
    private val FRAME_TAG = Regex(
        "</?(?:system-reminder|context-summary)(?:\\s[^>]*)?>",
        RegexOption.IGNORE_CASE,
    )

    private val WHITESPACE = Regex("\\s+")

    /**
     * @return a one-line, frame-safe summary; empty only when [argsJson] is
     *   blank or carries no readable value at all (then the caller may fall
     *   back to the digest).
     */
    fun summary(toolName: String, argsJson: String): String {
        val raw = argsJson.trim()
        if (raw.isEmpty()) return ""
        // [T-working-memory-prose-tools] A memory_write's useful identity is
        // "it wrote an entry", not the entry body: that body can contain
        // user-confided facts or credentials, and this line is persisted.
        if (com.openminis.app.security.ToolAliases.canonical(toolName) == "memory_write") {
            return "memory entry (${raw.length} chars)"
        }
        val parsed = runCatching { org.json.JSONObject(raw) }.getOrNull()
        val detail = if (parsed != null) {
            runCatching { resolveToolDetail(toolName, parsed) }.getOrDefault("")
                .ifBlank { scalarPairs(parsed) }
        } else {
            raw
        }
        // [T-working-memory-secret-mask] This line is PERSISTED into the
        // transcript, so a command carrying an Authorization header/API key
        // must not be duplicated there verbatim. Shape-based masking only;
        // paths and ordinary commands stay readable.
        return oneLine(com.openminis.app.util.SecretMasking.mask(detail))
            .let { com.openminis.app.util.TextClip.safeTake(it, WorkingMemoryLedger.ARGS_SUMMARY_CHARS) }
    }

    /**
     * Generic fallback for tool schemas the card resolver does not know:
     * the first three scalar arguments, `key=value`, each value capped.
     * Long prose fields and the display-only `tool_title` are skipped so the
     * line stays an identity, not a story.
     */
    private fun scalarPairs(json: org.json.JSONObject): String {
        val keys = json.keys()
        val parts = ArrayList<String>(3)
        while (keys.hasNext() && parts.size < MAX_PAIRS) {
            val key = keys.next()
            // Identity only: prose/body fields are not a useful one-line
            // record and may contain user content or secrets.
            if (key in NON_IDENTITY_KEYS) continue
            val value = json.opt(key)
            val text = when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> continue
            }.trim()
            if (text.isEmpty()) continue
            parts += "$key=" + com.openminis.app.util.TextClip.safeTake(oneLine(text), VALUE_CHARS)
        }
        return parts.joinToString(", ")
    }

    internal fun oneLine(text: String): String =
        FRAME_TAG.replace(text, "[tag]")
            .replace(WHITESPACE, " ")
            .trim()

    private const val MAX_PAIRS = 3
    private const val VALUE_CHARS = 60

    /** Keys whose values are prose/payload, not a call identity. */
    private val NON_IDENTITY_KEYS = setOf(
        "tool_title", "tool_input", "content", "text", "body", "prompt",
        "value", "data", "old_string", "new_string",
    )
}

/**
 * Per-session ledger holder. Process-scoped on purpose: the ledger's job is
 * to outlive context trimming WITHIN the process (the evict-then-deliver
 * trigger is the tail budget, which only fires in-process). A process death
 * also loses the transcript's in-memory tail, so re-delivering across a
 * restart would double-deliver against a history that was reloaded from DB.
 */
internal object WorkingMemoryStore {

    /**
     * [T-working-memory-store-bound] Access-ordered LRU. A process that opens
     * many sessions (sub-agent hosts, scheduled chats) must not retain one
     * 600-entry ledger per session forever. Eviction is safe: delivery is
     * best-effort and a fresh ledger simply records new rounds.
     */
    private val ledgers = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, WorkingMemoryLedger>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, WorkingMemoryLedger>,
            ): Boolean = size > WorkingMemoryLedger.MAX_LIVE_LEDGERS
        },
    )

    fun ledgerFor(sessionId: String): WorkingMemoryLedger =
        synchronized(ledgers) {
            ledgers.getOrPut(sessionId) { WorkingMemoryLedger() }
        }

    /** Test seam: how many live per-session ledgers the store retains. */
    internal fun liveLedgerCountForTest(): Int = synchronized(ledgers) { ledgers.size }

    fun append(
        sessionId: String,
        turn: Int,
        tool: String,
        argsDigest: String,
        argsSummary: String,
        resultHead: String,
    ) {
        ledgerFor(sessionId).append(turn, tool, argsDigest, argsSummary, resultHead)
    }

    fun renderUndelivered(sessionId: String): String? =
        ledgerFor(sessionId).renderUndelivered()

    /** True when the ledger has lines not yet delivered to the transcript. */
    fun hasUndelivered(sessionId: String): Boolean =
        ledgerFor(sessionId).hasUndelivered()

    /** Test / session-swap seam. */
    fun reset(sessionId: String? = null) {
        synchronized(ledgers) {
            if (sessionId == null) ledgers.clear() else ledgers.remove(sessionId)
        }
    }
}
