package com.openminis.app.ui.chat

import com.openminis.app.util.SecretMasking
import com.openminis.app.util.TextClip

private fun safeHandoverText(text: String, limit: Int): String =
    TextClip.safeTake(WorkingMemoryArgs.oneLine(SecretMasking.mask(text)), limit)

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

    /** [T-working-memory-persist] Indices marked by the last render, so a
     * failed transcript commit can put them back instead of losing them. */
    private var lastDeliveredIndices: List<Int> = emptyList()

    @Synchronized
    fun append(
        turn: Int,
        tool: String,
        argsDigest: String,
        argsSummary: String,
        resultHead: String,
        /**
         * [T-harness-config-wiring] The cap is policy, not a constant: a
         * per-model override must be able to change how many rounds the
         * handover retains. It used to be read from
         * `HarnessConfig.current()`, which always returns the shipped default,
         * so the per-model value could never take effect.
         */
        maxEntries: Int = com.openminis.app.agent.HarnessConfig.current().ledgerMaxEntries,
    ): Boolean {
        entries.add(
            Entry(
                turn = turn,
                tool = tool,
                argsDigest = argsDigest,
                argsSummary = safeHandoverText(argsSummary, ARGS_SUMMARY_CHARS),
                // Mask before clipping so a credential crossing the preview boundary is still recognized.
                resultHead = safeHandoverText(resultHead, RESULT_HEAD_CHARS),
            ),
        )
        delivered.add(false)
        // [T-harness-config-wiring] The cap arrives from the caller's resolved
        // per-model config; the shipped default (600) is byte-identical to
        // MAX_ENTRIES when no override is stored.
        var folded = false
        if (entries.size > maxEntries) {
            folded = true
            // Fold the oldest run of entries into one marker line: drop the
            // first (maxEntries / 2) entries but keep their count visible.
            val drop = maxEntries / 2
            val oldest = entries.subList(0, drop)
            // [T-working-memory-fold-sample] Folding undelivered records must
            // not reduce them to a bare count: keep a short sample of what
            // the oldest rounds were about, so the handover still names work.
            val sample = oldest.asReversed().take(FOLD_SAMPLE_ENTRIES)
                .joinToString(", ") {
                    val label = it.argsSummary.ifBlank { "no-args" }
                    "${it.tool}($label)"
                }
            val sampleLabel = com.openminis.app.util.TextClip.safeTake(
                WorkingMemoryArgs.oneLine("sample: $sample"),
                ARGS_SUMMARY_CHARS,
            )
            val foldLine = Entry(
                turn = oldest.first().turn,
                tool = FOLD_TOOL,
                argsDigest = "rounds ${oldest.first().turn}–${oldest.last().turn}",
                argsSummary = if (sample.isBlank()) {
                    "rounds ${oldest.first().turn}–${oldest.last().turn}"
                } else {
                    sampleLabel
                },
                // Honest wording: compact may never have run, so pointing at a
                // summary that does not cover these rounds is a false lead.
                resultHead = "${oldest.size} earlier tool rounds folded (records beyond this point)",
            )
            entries.subList(0, drop).clear()
            delivered.subList(0, drop).clear()
            entries.add(0, foldLine)
            delivered.add(0, false)
            // A fold shifts every index; an undo token from before it can no
            // longer address the right records.
            lastDeliveredIndices = emptyList()
        }
        return folded
    }

    /** Newest entry, for append-only persistence. */
    @Synchronized
    fun lastEntry(): Entry? = entries.lastOrNull()

    /** Snapshot for compaction/rewrite of the durable file. */
    @Synchronized
    fun snapshot(undeliveredOnly: Boolean): List<Entry> =
        entries.indices
            .filter { !undeliveredOnly || !delivered[it] }
            .map { entries[it] }

    /** Restore from the durable file; every restored record is undelivered. */
    @Synchronized
    fun restore(restored: List<Entry>) {
        entries.clear()
        delivered.clear()
        val tail = restored.takeLast(MAX_ENTRIES)
        for (e in tail) {
            entries.add(
                Entry(
                    turn = e.turn,
                    tool = e.tool,
                    argsDigest = e.argsDigest,
                    argsSummary = safeHandoverText(e.argsSummary, ARGS_SUMMARY_CHARS),
                    resultHead = safeHandoverText(e.resultHead, RESULT_HEAD_CHARS),
                ),
            )
            delivered.add(false)
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
        lastDeliveredIndices = chosen
        val body = lines.joinToString("\n")
        val remaining = undeliveredIndices.size - chosen.size
        val head = if (remaining > 0) "($remaining older tool rounds pending — delivered on later evictions)\n" else ""
        return "[working memory — one-line record of tool rounds no longer in context]\n$head$body"
    }

    @Synchronized
    fun size(): Int = entries.size

    /** [T-working-memory-persist] Undo the last render when its note failed
     * to reach the transcript; the next turn retries instead of losing it. */
    @Synchronized
    fun undoLastDelivery() {
        for (idx in lastDeliveredIndices) {
            if (idx in delivered.indices) delivered[idx] = false
        }
        lastDeliveredIndices = emptyList()
    }

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
 * Per-session ledger holder with a DURABLE handover log.
 *
 * [T-working-memory-persist] The ledger used to be process-scoped: a process
 * kill between the tail-budget eviction and the next hydration silently lost
 * every undelivered round. The log is now append-only JSONL per session,
 * rewritten on fold/delivery, exactly like [NoticeLedgerStore]'s per-session
 * file. Delivered records are removed on delivery, so a restart never
 * re-announces work the transcript already carries.
 *
 * IO is best-effort: a failed write must never affect the tool result.
 */
internal object WorkingMemoryStore {

    /** [T-working-memory-store-bound] Access-ordered LRU of live ledgers. */
    private val ledgers = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Slot>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Slot>,
            ): Boolean = size > WorkingMemoryLedger.MAX_LIVE_LEDGERS
        },
    )

    private data class Slot(
        val ledger: WorkingMemoryLedger,
        var filesDir: java.io.File?,
    )

    private const val DIR = "working-memory"

    private fun dir(filesDir: java.io.File): java.io.File = java.io.File(filesDir, DIR)

    private fun file(filesDir: java.io.File, sessionId: String): java.io.File =
        java.io.File(dir(filesDir), "${safeSession(sessionId)}.jsonl")

    private fun safeSession(sessionId: String): String =
        sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    internal fun ledgerFor(sessionId: String, filesDir: java.io.File? = null): WorkingMemoryLedger {
        synchronized(ledgers) {
            val existing = ledgers[sessionId]
            if (existing != null) {
                val same = existing.filesDir == null || filesDir == null ||
                    existing.filesDir?.absolutePath == filesDir.absolutePath
                if (same) {
                    if (existing.filesDir == null && filesDir != null) {
                        // First durable call after an in-memory-only access:
                        // load the log when nothing was recorded yet.
                        if (existing.ledger.size() == 0 && !existing.ledger.hasUndelivered()) {
                            val loaded = load(filesDir, sessionId)
                            ledgers[sessionId] = Slot(loaded, filesDir)
                            return loaded
                        }
                        existing.filesDir = filesDir
                    }
                    return existing.ledger
                }
            }
            val loaded = if (filesDir != null) load(filesDir, sessionId) else WorkingMemoryLedger()
            ledgers[sessionId] = Slot(loaded, filesDir)
            return loaded
        }
    }

    private fun load(filesDir: java.io.File, sessionId: String): WorkingMemoryLedger {
        val f = file(filesDir, sessionId)
        if (!f.isFile) return WorkingMemoryLedger()
        val restored = ArrayList<WorkingMemoryLedger.Entry>()
        runCatching {
            f.useLines { lines ->
                for (line in lines) {
                    if (restored.size >= WorkingMemoryLedger.MAX_ENTRIES * 2) break
                    if (line.isBlank()) continue
                    val o = runCatching { org.json.JSONObject(line) }.getOrNull() ?: continue
                    val tool = o.optString("tool", "")
                    val digest = o.optString("argsDigest", "")
                    if (tool.isBlank() && digest.isBlank()) continue
                    restored += WorkingMemoryLedger.Entry(
                        turn = o.optInt("turn", 0),
                        tool = tool,
                        argsDigest = digest,
                        argsSummary = o.optString("argsSummary", ""),
                        resultHead = o.optString("resultHead", ""),
                    )
                }
            }
        }
        return WorkingMemoryLedger().also { it.restore(restored) }
    }

    private fun toLine(e: WorkingMemoryLedger.Entry): String =
        org.json.JSONObject()
            .put("turn", e.turn)
            .put("tool", e.tool)
            .put("argsDigest", e.argsDigest)
            .put("argsSummary", e.argsSummary)
            .put("resultHead", e.resultHead)
            .toString()

    private fun persistAll(filesDir: java.io.File, sessionId: String, entries: List<WorkingMemoryLedger.Entry>) {
        runCatching {
            val target = file(filesDir, sessionId)
            if (entries.isEmpty()) {
                target.delete()
                return@runCatching
            }
            dir(filesDir).mkdirs()
            val body = buildString {
                for (e in entries) append(toLine(e)).append('\n')
            }
            val tmp = java.io.File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                target.writeText(body, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun appendLine(filesDir: java.io.File, sessionId: String, e: WorkingMemoryLedger.Entry) {
        runCatching {
            dir(filesDir).mkdirs()
            file(filesDir, sessionId).appendText(toLine(e) + "\n", Charsets.UTF_8)
        }
    }

    /** Test seam: how many live per-session ledgers the store retains. */
    internal fun liveLedgerCountForTest(): Int = synchronized(ledgers) { ledgers.size }

    /** [T-working-memory-persist] Warm the ledger on the IO dispatcher so the
     * request-assembly `hasUndelivered` check does not read disk on Main. */
    fun preload(sessionId: String, filesDir: java.io.File?) {
        if (filesDir != null) ledgerFor(sessionId, filesDir)
    }

    @Synchronized
    fun append(
        sessionId: String,
        turn: Int,
        tool: String,
        argsDigest: String,
        argsSummary: String,
        resultHead: String,
        filesDir: java.io.File? = null,
        maxEntries: Int = com.openminis.app.agent.HarnessConfig.current().ledgerMaxEntries,
    ) {
        val ledger = ledgerFor(sessionId, filesDir)
        val folded = ledger.append(turn, tool, argsDigest, argsSummary, resultHead, maxEntries)
        if (filesDir != null) {
            if (folded) {
                // Only undelivered records belong in the durable handover;
                // old delivered records must not replay after a restart.
                persistAll(filesDir, sessionId, ledger.snapshot(undeliveredOnly = true))
            } else {
                ledger.lastEntry()?.let { appendLine(filesDir, sessionId, it) }
            }
        }
    }

    @Synchronized
    fun renderUndelivered(sessionId: String, filesDir: java.io.File? = null): String? {
        val ledger = ledgerFor(sessionId, filesDir)
        // Do NOT rewrite the durable log here: the caller must persist the
        // delivered removal only AFTER the transcript commit succeeds. A crash
        // between render and commit then retries (duplicate-risk) instead of
        // silently losing the handover.
        return ledger.renderUndelivered()
    }

    /** [T-working-memory-persist] The note reached the transcript: drop the
     * delivered records from the durable log so a restart cannot repeat them. */
    @Synchronized
    fun ackDelivered(sessionId: String, filesDir: java.io.File? = null) {
        if (filesDir == null) return
        val ledger = ledgerFor(sessionId, filesDir)
        persistAll(filesDir, sessionId, ledger.snapshot(undeliveredOnly = true))
    }

    /** [T-working-memory-persist] The commit failed: make the same records
     * deliverable again (in-memory state only; the log was never rewritten). */
    @Synchronized
    fun undoDelivery(sessionId: String, filesDir: java.io.File? = null) {
        ledgerFor(sessionId, filesDir).undoLastDelivery()
    }

    /** True when the ledger has lines not yet delivered to the transcript. */
    @Synchronized
    fun hasUndelivered(sessionId: String, filesDir: java.io.File? = null): Boolean =
        ledgerFor(sessionId, filesDir).hasUndelivered()

    /** Test / session-swap seam. Does not delete the durable log. */
    fun reset(sessionId: String? = null) {
        synchronized(ledgers) {
            if (sessionId == null) ledgers.clear() else ledgers.remove(sessionId)
        }
    }

    /** Test seam: durable log path. */
    internal fun fileForTest(filesDir: java.io.File, sessionId: String): java.io.File =
        file(filesDir, sessionId)

    /** `/clear` (or a deleted session): drop both memory and the durable log. */
    fun delete(filesDir: java.io.File?, sessionId: String) {
        synchronized(ledgers) { ledgers.remove(sessionId) }
        if (filesDir != null) runCatching { file(filesDir, sessionId).delete() }
    }
}
