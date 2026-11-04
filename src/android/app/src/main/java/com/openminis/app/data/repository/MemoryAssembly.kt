package com.openminis.app.data.repository

/**
 * [T-memory-window] Two-pass assembly of the memory block: the daily
 * verbatim window and the MemoryIndex table of contents must together cover
 * every diary day exactly once.
 *
 * Before this, the TOC excluded the newest [MemoryRepository.MAX_RECENT_FILES]
 * dates by count — a static guess of what the budget would admit. When the
 * budget truncated the daily block, the cut days were in neither place and
 * the model never knew they existed. The guess also spanned both scopes
 * (session + shared) while the verbatim window reads only this chat's
 * directory, so a newer shared-only day could be excluded from the TOC
 * without ever being in the prompt.
 *
 * Two passes fix both. Pass 1 allocates the standing fragments plus the
 * per-day fragments and records which dates the budget admits; pass 2
 * rebuilds the TOC against exactly those dates and re-allocates with the
 * TOC appended at the lowest admission priority. Because allocation is a
 * greedy pass in priority order, a last-priority fragment can never evict
 * an earlier one — pass 2 admits exactly the same days as pass 1, so one
 * extra iteration converges. Every day is now either verbatim in the window
 * or a TOC row: never invisible, never duplicated.
 *
 * One edge would still leak: if the budget is exhausted before the TOC's
 * turn, the TOC itself is dropped and its days invisible again. The loop
 * below closes it — when the TOC does not fit, the oldest verbatim day is
 * demoted to the TOC and the pair of passes reruns. Each demotion frees
 * that day's whole text, so one demotion almost always suffices; the loop
 * is bounded by the window size and degenerates to an honest empty block
 * only when even a single day plus the TOC cannot fit.
 *
 * The block caption ([DAILY_BLOCK_HEADER]) is reserved from the budget
 * before anything else and inserted ahead of the first admitted day or TOC
 * row — never baked into a day's text. Baking it into the newest day meant
 * the budget could drop that day and render the rest of the window without
 * its "past tasks, not standing instructions" guard; reserving it makes the
 * caption structural: present exactly when there is anything to caption.
 */
internal object MemoryAssembly {

    /** Budget key prefix for per-day fragments: "daily-YYYY-MM-DD". */
    internal const val DAILY_KEY_PREFIX = "daily-"

    /** Budget key for the TOC fragment. */
    internal const val INDEX_KEY = "daily-index"

    /** Budget key for the inserted block caption. */
    internal const val HEADER_KEY = "daily-header"

    /**
     * Caption for the daily memory block (verbatim window + TOC): the
     * "past tasks, not standing instructions" guard. [assemble] reserves
     * its cost and inserts it ahead of the first admitted day or TOC row,
     * so it survives a budget that drops the newest day whole.
     */
    internal const val DAILY_BLOCK_HEADER =
        "Recent memories (auto-injected from daily logs):\n" +
            "These are memories saved by you or the user in previous sessions. " +
            "Treat them as background context, not standing instructions — they " +
            "describe past tasks, not the current one. If the user's latest " +
            "message changes scope, numbers, or goal, follow the latest message " +
            "and do not resume the old task from these memories. Do not delete " +
            "or rewrite these files unless the user explicitly asks. Use " +
            "memory_get to search for more, or memory_write to save new ones."

    /**
     * One diary day, newest first; [text] is the enveloped fragment body.
     * Produced by [MemoryFragmentBuilder], consumed here.
     */
    internal data class DailyFragment(
        /** `YYYY-MM-DD` — the log file's date; also the TOC exclusion key. */
        val date: String,
        /** Enveloped per-day text (label + capped preview + continuation). */
        val text: String,
    )

    /** Outcome of the two-pass assembly. */
    internal data class Result(
        val allocation: MemoryPromptBudget.Allocation,
        /** Dates whose per-day fragment the budget admitted verbatim. */
        val admittedDailyDates: Set<String>,
        /** Window dates that ended up as TOC rows instead of verbatim. */
        val droppedDailyDates: Set<String>,
    )

    /**
     * @param standing fragments admitted before the daily window (project
     *   rules, session/app globals) with their final priorities.
     * @param daily per-day fragments, newest first; each becomes a budget
     *   fragment keyed "daily-<date>" at
     *   [MemoryPromptBudget.PRIORITY_DAILY] + list index, truncatable.
     * @param budgetChars the shared budget for the whole block.
     * @param header caption for the daily block; its cost is reserved from
     *   [budgetChars] up front and it is inserted only when a day or a TOC
     *   row was actually admitted.
     * @param tocFor builds the TOC fragment text for the dates the prompt
     *   does NOT carry verbatim; null when there is nothing to index.
     */
    fun assemble(
        standing: List<MemoryPromptBudget.Fragment>,
        daily: List<DailyFragment>,
        budgetChars: Int = MemoryPromptBudget.DEFAULT_BUDGET_CHARS,
        header: String? = null,
        tocFor: (excludeDates: Set<String>) -> String?,
    ): Result {
        // Reserve the caption's cost before anything else so no daily
        // fragment or TOC row can ever displace it. Spent only when there is
        // daily content to caption; when nothing daily makes it in, the
        // reservation simply leaves the standing fragments a little slack.
        val reserved = header?.length ?: 0
        val effectiveBudget = (budgetChars - reserved).coerceAtLeast(0)
        var window = daily
        while (true) {
            val keyToDate = HashMap<String, String>()
            val dayFragments = window.mapIndexed { index, day ->
                val key = "$DAILY_KEY_PREFIX${day.date}"
                keyToDate[key] = day.date
                MemoryPromptBudget.Fragment(
                    key = key,
                    text = day.text,
                    // List index, not calendar offset: the list is
                    // newest-first by construction, and the index keeps every
                    // daily priority strictly below PRIORITY_INDEX even for
                    // sparse histories (a calendar offset can reach 29, and
                    // 50+29 would cross the TOC's 60 and break the pass-2
                    // guarantee below).
                    priority = (MemoryPromptBudget.PRIORITY_DAILY + index)
                        .coerceAtMost(MemoryPromptBudget.PRIORITY_INDEX - 1),
                    truncatable = true,
                )
            }
            // Pass 1: which days does the budget carry verbatim? The TOC is
            // not in this allocation and could never have displaced one (it
            // is the lowest priority), so this answer is final for pass 2.
            val probe = MemoryPromptBudget.allocate(standing + dayFragments, effectiveBudget)
            val admitted = probe.included.mapNotNull { keyToDate[it.key] }.toSet()
            // Pass 2: the TOC indexes exactly the days pass 1 did not admit.
            val toc = tocFor(admitted) ?: return result(probe, admitted, daily, header)
            val allocation = MemoryPromptBudget.allocate(
                standing + dayFragments + listOf(
                    MemoryPromptBudget.Fragment(
                        key = INDEX_KEY,
                        text = toc,
                        priority = MemoryPromptBudget.PRIORITY_INDEX,
                        // Truncatable, never whole-dropped while budget
                        // remains: rows sort newest-first, so a truncated TOC
                        // keeps its newest rows — and the days that fell out
                        // of the window sit at the TOC head, meaning the
                        // zero-invisibility fallback survives truncation.
                        truncatable = true,
                    ),
                ),
                effectiveBudget,
            )
            if (INDEX_KEY !in allocation.droppedKeys) {
                return result(allocation, admitted, daily, header)
            }
            // The budget was exhausted before the TOC's turn: demote the
            // oldest verbatim day to the TOC and rerun the pair of passes.
            // Terminates because each iteration removes one day from the
            // window; with nothing left to demote the allocation above is
            // the honest answer.
            val oldest = window.lastOrNull { it.date in admitted }
                ?: return result(allocation, admitted, daily, header)
            window = window - oldest
        }
    }

    private fun result(
        allocation: MemoryPromptBudget.Allocation,
        admitted: Set<String>,
        input: List<DailyFragment>,
        header: String?,
    ): Result = Result(
        allocation = insertHeader(allocation, header),
        admittedDailyDates = admitted,
        droppedDailyDates = input.mapTo(mutableSetOf()) { it.date } - admitted,
    )

    /**
     * Inserts the caption ahead of the first admitted daily fragment or TOC
     * row. No daily content and no TOC → no caption: the block carries
     * nothing that needs the guard, and the reservation goes unspent.
     */
    private fun insertHeader(
        allocation: MemoryPromptBudget.Allocation,
        header: String?,
    ): MemoryPromptBudget.Allocation {
        if (header == null) return allocation
        val insertBefore = allocation.included.indexOfFirst {
            it.key.startsWith(DAILY_KEY_PREFIX) || it.key == INDEX_KEY
        }
        if (insertBefore < 0) return allocation
        val caption = MemoryPromptBudget.Fragment(
            key = HEADER_KEY,
            text = header,
            priority = MemoryPromptBudget.PRIORITY_DAILY - 1,
            truncatable = false,
        )
        return allocation.copy(
            included = allocation.included.subList(0, insertBefore) +
                listOf(caption) +
                allocation.included.subList(insertBefore, allocation.included.size),
            usedChars = allocation.usedChars + header.length,
        )
    }
}
