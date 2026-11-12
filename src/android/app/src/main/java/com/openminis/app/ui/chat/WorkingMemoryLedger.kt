package com.openminis.app.ui.chat

import java.util.concurrent.ConcurrentHashMap

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
        val argsDigest: String,
        val resultHead: String,
    )

    private val entries = ArrayList<Entry>()
    private var delivered = 0

    @Synchronized
    fun append(turn: Int, tool: String, argsDigest: String, resultHead: String) {
        entries.add(Entry(turn, tool, argsDigest, resultHead.take(RESULT_HEAD_CHARS)))
        if (entries.size > MAX_ENTRIES) {
            // Fold the oldest run of entries into one marker line: drop the
            // first (MAX_ENTRIES / 2) entries but keep their count visible.
            val drop = MAX_ENTRIES / 2
            val folded = entries.subList(0, drop)
            val foldLine = Entry(
                turn = folded.first().turn,
                tool = FOLD_TOOL,
                argsDigest = "rounds ${folded.first().turn}–${folded.last().turn}",
                resultHead = "${folded.size} earlier tool rounds folded (see summary)",
            )
            entries.subList(0, drop).clear()
            entries.add(0, foldLine)
            delivered = maxOf(0, delivered - drop + 1)
        }
    }

    /**
     * One-line-per-entry rendering of entries not yet delivered to this
     * session's transcript, or null when everything delivered is still
     * visible (nothing new to hand over).
     */
    @Synchronized
    fun renderUndelivered(): String? {
        if (delivered >= entries.size) return null
        val pending = entries.subList(delivered, entries.size)
        if (pending.isEmpty()) return null
        val lines = ArrayList<String>(pending.size)
        var i = 0
        while (i < pending.size) {
            val e = pending[i]
            var run = 1
            while (i + run < pending.size &&
                pending[i + run].tool == e.tool &&
                pending[i + run].argsDigest == e.argsDigest &&
                pending[i + run].resultHead == e.resultHead
            ) run++
            lines += if (run == 1) {
                "turn ${e.turn}: ${e.tool}(${e.argsDigest}) → ${e.resultHead}"
            } else {
                "turns ${e.turn}–${pending[i + run - 1].turn}: ${e.tool}(${e.argsDigest}) → same result ×$run"
            }
            i += run
        }
        delivered = entries.size
        val body = lines.joinToString("\n")
        return "[working memory — one-line record of tool rounds no longer in context]\n$body"
    }

    @Synchronized
    fun size(): Int = entries.size

    internal companion object {
        internal const val RESULT_HEAD_CHARS = 120
        internal const val MAX_ENTRIES = 600
        internal const val FOLD_TOOL = "earlier-rounds"
    }
}

/**
 * Per-session ledger holder. Process-scoped on purpose: the ledger's job is
 * to outlive context trimming WITHIN the process (the evict-then-deliver
 * trigger is the tail budget, which only fires in-process). A process death
 * also loses the transcript's in-memory tail, so re-delivering across a
 * restart would double-deliver against a history that was reloaded from DB.
 */
internal object WorkingMemoryStore {

    private val ledgers = ConcurrentHashMap<String, WorkingMemoryLedger>()

    fun ledgerFor(sessionId: String): WorkingMemoryLedger =
        ledgers.getOrPut(sessionId) { WorkingMemoryLedger() }

    fun append(sessionId: String, turn: Int, tool: String, argsDigest: String, resultHead: String) {
        ledgerFor(sessionId).append(turn, tool, argsDigest, resultHead)
    }

    fun renderUndelivered(sessionId: String): String? =
        ledgerFor(sessionId).renderUndelivered()

    /** Test / session-swap seam. */
    fun reset(sessionId: String? = null) {
        if (sessionId == null) ledgers.clear() else ledgers.remove(sessionId)
    }
}
