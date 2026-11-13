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
            // The fold line REPLACES the dropped range at the head. The fold
            // line itself is UNDELIVERED (its count must reach the
            // transcript). If the delivered cursor sat inside the dropped
            // range, the pointer moves to 0 (fold line); if it sat past the
            // range, the surviving entries shifted left by [drop] and the
            // fold line adds +1.
            delivered = if (delivered < drop) 0 else delivered - drop + 1
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
        // Cap the handover: a 400-line wall invites the model to narrate about
        // it instead of working. Deliver from the HEAD of the undelivered
        // range (chronological order — the fold marker and earliest context
        // go first); the remainder stays undelivered for the next eviction.
        val take = minOf(MAX_DELIVERY_LINES, pending.size)
        val delivering = pending.subList(0, take)
        val lines = ArrayList<String>(delivering.size)
        var i = 0
        while (i < delivering.size) {
            val e = delivering[i]
            var run = 1
            while (i + run < delivering.size &&
                delivering[i + run].tool == e.tool &&
                delivering[i + run].argsDigest == e.argsDigest &&
                delivering[i + run].resultHead == e.resultHead
            ) run++
            lines += if (run == 1) {
                "turn ${e.turn}: ${e.tool}(${e.argsDigest}) → ${e.resultHead}"
            } else {
                "turns ${e.turn}–${pending[i + run - 1].turn}: ${e.tool}(${e.argsDigest}) → same result ×$run"
            }
            i += run
        }
        // Advance by what was ACTUALLY delivered (capped), not by everything
        // pending — the rest rides the next eviction.
        delivered += delivering.size
        val body = lines.joinToString("\n")
        val omitted = pending.size - delivering.size
        val head = if (omitted > 0) "($omitted more tool rounds pending — delivered on later evictions)\n" else ""
        return "[working memory — one-line record of tool rounds no longer in context]\n$head$body"
    }

    @Synchronized
    fun size(): Int = entries.size

    /** True when undelivered entries exist (delivered < entries.size). */
    @Synchronized
    fun hasUndelivered(): Boolean = delivered < entries.size

    internal companion object {
        internal const val RESULT_HEAD_CHARS = 120
        internal const val MAX_ENTRIES = 600
        internal const val FOLD_TOOL = "earlier-rounds"

        /** Per-delivery cap: a wall of records invites narration. */
        internal const val MAX_DELIVERY_LINES = 40
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

    /** True when the ledger has lines not yet delivered to the transcript. */
    fun hasUndelivered(sessionId: String): Boolean =
        ledgerFor(sessionId).hasUndelivered()

    /** Test / session-swap seam. */
    fun reset(sessionId: String? = null) {
        if (sessionId == null) ledgers.clear() else ledgers.remove(sessionId)
    }
}
