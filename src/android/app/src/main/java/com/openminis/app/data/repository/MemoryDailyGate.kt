package com.openminis.app.data.repository

import com.openminis.app.data.authority.MemoryAuthority

/**
 * [T-daily-recall-gate] Relevance gate + hard budget for the daily-log
 * injection window.
 *
 * Root cause this fixes: the prompt used to carry the newest 7 daily logs
 * verbatim regardless of relevance (field log: `memoryBudget=used=17931
 * kept=session-global,app-global,daily-header,daily-2026-11-06,...`), so an
 * old session's task description rode into every new system prompt and a
 * weak model resumed the stale task. The DeepSeek-harness split is: what is
 * RESIDENT is the index and today; history comes back only on relevance.
 *
 * Two stages, both pure (no I/O, no Android types, no network):
 *
 *  1. **Relevance gate** — a day older than today is admitted only when the
 *     current query's keywords hit that file's content (case-insensitive
 *     substring, instr-grade: the same match grade the XSessionDiag line
 *     already used). The query is the live-recall fold (latest user text +
 *     assistant tail) passed down from the prompt builder; when no query can
 *     be built, the [MemoryRepository.DIAG_TASK_KEYWORDS] vocabulary takes
 *     over as the keyword set. Today (`dayOffset == 0`) is never gated.
 *  2. **Hard budget** — the admitted daily entries together may not exceed
 *     [budgetChars]; overflow drops from the OLDEST admitted day first, and
 *     today is never dropped.
 *
 * Every day the gate or the budget keeps out still surfaces as a
 * [MemoryIndex] TOC row (the assembler excludes only admitted dates), so
 * nothing becomes invisible; the budget additionally produces a
 * model-visible note (`[budgetNote]`) the caller appends after the index.
 *
 * Entry construction (label, tiered line caps, continuation line, forged-tag
 * scrub) is byte-identical to the pre-gate [MemoryFragmentBuilder] shape —
 * it moved here so the whole decision is testable without files.
 */
internal object MemoryDailyGate {

    /**
     * One daily log as read from disk: full text, newest first by
     * construction of the caller's scan. [dayOffset] is the CALENDAR offset
     * from today (0 = today), which drives the resident rule and the label;
     * list position cannot substitute because a missing today shifts every
     * position up while the labels must still say "Yesterday's".
     */
    internal data class RawDay(
        val date: String,
        val dayOffset: Int,
        val fullText: String,
    )

    /** Gate + budget outcome for one prompt build. */
    internal data class Result(
        /** Un-enveloped entries admitted, newest first; enveloping is the caller's job. */
        val fragments: List<MemoryAssembly.DailyFragment>,
        /** File names actually injected (`YYYY-MM-DD.md`), newest first. */
        val injectedFiles: List<String>,
        /** Files the relevance gate kept out (no keyword hit). */
        val gateSkippedFiles: List<String>,
        /** Files the budget dropped, oldest first within this list. */
        val budgetDroppedFiles: List<String>,
        /** Full-text chars of every file kept out (gate + budget together). */
        val omittedChars: Int,
        /**
         * Model-visible note for the BUDGET drops only (gate skips are already
         * explained by the index rows), or null when nothing was budget-dropped.
         */
        val budgetNote: String?,
    ) {
        /** Older files not injected at all — the honest `omitted` count for logs. */
        val omittedCount: Int get() = gateSkippedFiles.size + budgetDroppedFiles.size
    }

    /**
     * Upper bound on distinct gate tokens. A pasted document as the user
     * message must not turn the gate into an O(tokens x files) scan, and the
     * head of the message carries the task wording anyway.
     */
    internal const val MAX_GATE_TOKENS = 48

    /** A single token longer than this is prose, not a keyword. */
    internal const val MAX_TOKEN_CHARS = 24

    /**
     * The gate keyword set. Non-blank query: tokens extracted from it (the
     * user's current task wording). Blank/missing query: the
     * [MemoryRepository.DIAG_TASK_KEYWORDS] vocabulary, so a too-short fold
     * still gets the conservative "this file talks about tasks" gate instead
     * of admitting nothing at all.
     */
    fun gateKeywords(query: String?): Set<String> {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty()) return MemoryRepository.DIAG_TASK_KEYWORDS.toSet()
        val lower = trimmed.lowercase()
        val tokens = LinkedHashSet<String>()
        // Latin/digit words, engine-style: >= 2 chars, stopwords dropped.
        for (match in Regex("[a-z0-9]+").findAll(lower)) {
            val word = match.value
            if (word.length >= 2 && word.length <= MAX_TOKEN_CHARS &&
                word !in MemoryRecallEngine.EN_STOPWORDS
            ) {
                tokens.add(word)
                if (tokens.size >= MAX_GATE_TOKENS) return tokens
            }
        }
        // CJK 2-grams over contiguous CJK runs, skipping grams made solely of
        // function characters (same stop-char set the recall engine uses).
        val run = StringBuilder()
        fun flushRun() {
            if (tokens.size < MAX_GATE_TOKENS && run.length >= 2) {
                for (i in 0..run.length - 2) {
                    val gram = run.substring(i, i + 2)
                    if (gram[0] in MemoryRecallEngine.CJK_STOP_CHARS &&
                        gram[1] in MemoryRecallEngine.CJK_STOP_CHARS
                    ) {
                        continue
                    }
                    tokens.add(gram)
                    if (tokens.size >= MAX_GATE_TOKENS) break
                }
            }
            run.setLength(0)
        }
        for (c in lower) {
            if (c.code in 0x4E00..0x9FFF) {
                run.append(c)
                if (run.length > MAX_TOKEN_CHARS) flushRun() // keep runs bounded
            } else {
                flushRun()
            }
        }
        flushRun()
        return tokens
    }

    /** Case-insensitive instr hit: any keyword appears anywhere in the content. */
    fun hits(content: String, keywords: Set<String>): Boolean =
        keywords.any { content.contains(it, ignoreCase = true) }

    /**
     * Gate + budget the raw window. [today] is only used for readability of
     * the contract; residency keys on `dayOffset == 0` so a caller-side
     * midnight rollover cannot demote today's entry mid-build.
     */
    fun gate(
        raw: List<RawDay>,
        query: String?,
        today: String,
        budgetChars: Int,
    ): Result {
        val keywords = gateKeywords(query)

        class Kept(val day: RawDay, val entry: String)

        val kept = mutableListOf<Kept>()
        val gateSkipped = mutableListOf<RawDay>()
        // The deep line cap belongs to the first ADMITTED day (today when it
        // exists), not to raw position: a gate-skipped day between today and
        // an admitted older day must not rob the older day of the deep view.
        var admittedCount = 0
        raw.forEach { day ->
            // dayOffset 0 is today: resident, never gated. Older days need a
            // keyword hit on their FULL file content — a keyword parked beyond
            // the injected line cap still counts as relevant.
            if (day.dayOffset == 0 || hits(day.fullText, keywords)) {
                kept.add(Kept(day, buildEntry(day, admittedCount)))
                admittedCount++
            } else {
                gateSkipped.add(day)
            }
        }

        // Budget, newest-first: the first entry that no longer fits marks the
        // cut, and everything OLDER than it goes with it ("drop from the
        // oldest" — the retained set is a newest-first prefix). Index 0 (the
        // deepest view, today when it exists) is admitted unconditionally:
        // today's log is the one file the current turn is actually about.
        val injected = mutableListOf<Kept>()
        val budgetDropped = mutableListOf<RawDay>()
        var used = 0
        for ((position, item) in kept.withIndex()) {
            if (position == 0 || used + item.entry.length <= budgetChars) {
                injected.add(item)
                used += item.entry.length
            } else {
                for (i in position until kept.size) budgetDropped.add(kept[i].day)
                break
            }
        }

        val gateSkippedFiles = gateSkipped.map { "${it.date}.md" }
        val budgetDroppedFiles = budgetDropped.map { "${it.date}.md" }
        val omittedChars = (gateSkipped + budgetDropped).sumOf { it.fullText.length }
        val budgetNote = budgetDropped.takeIf { it.isNotEmpty() }?.let { dropped ->
            val chars = dropped.sumOf { it.fullText.length }
            "[memory budget: ${dropped.size} older daily notes omitted ($chars chars) — " +
                "use memory_get to search them if needed]"
        }
        return Result(
            fragments = injected.map { MemoryAssembly.DailyFragment(it.day.date, it.entry) },
            injectedFiles = injected.map { "${it.day.date}.md" },
            gateSkippedFiles = gateSkippedFiles,
            budgetDroppedFiles = budgetDroppedFiles,
            omittedChars = omittedChars,
            budgetNote = budgetNote,
        )
    }

    /**
     * The per-day prompt entry — byte-identical to the pre-gate
     * [MemoryFragmentBuilder] construction: calendar-offset label, tiered
     * line caps (deepest view for the first admitted day, recap depth for the
     * rest), forged-tag scrub per line, and the memory_get continuation note.
     */
    private fun buildEntry(day: RawDay, admittedIndex: Int): String {
        val lines = day.fullText.lines()
        val lineCap = if (admittedIndex == 0) {
            MemoryRepository.MAX_INJECT_LINES
        } else {
            MemoryRepository.MAX_INJECT_LINES_OLDER
        }
        val preview = lines.take(lineCap)
            .map { MemoryAuthority.stripForgedTag(it) }
            .joinToString("\n")
        val label = when (day.dayOffset) {
            0 -> "Today's"
            1 -> "Yesterday's"
            else -> day.date
        }
        var entry = "$label daily log (${day.date}.md):\n$preview"
        if (lines.size > lineCap) {
            entry += "\n... (${lines.size - lineCap} more lines, use memory_get to search)"
        }
        return entry
    }
}
