package com.openminis.app.agent

/**
 * [T-harness-config] One validated place for the numeric policy that used to be
 * scattered `const val`s.
 *
 * WHY this exists: the context/memory budget was expressed as literals in six
 * files — `PostAnchorTail.BUDGET_TOKENS = 60_000`, `WINDOW_FRACTION = 0.35`,
 * `NOTICE_RESERVE_TOKENS = 1_200`, `ContextSaverRuntime.ABSOLUTE_AGE_TOKENS =
 * 24_000`, `DroppedRoundDigest.MAX_SCAN_MESSAGES = 512`,
 * `WorkingMemoryLedger.MAX_ENTRIES = 600`. A 32K-window model and a 200K-window
 * model shared every one of them, and changing a threshold meant a release.
 *
 * Design constraints, in order:
 *
 *  1. **Defaults must be byte-identical to the shipped literals.** The numeric
 *     corridor is asserted by `scripts/ci/check_tool_title_and_budget_contract.py`;
 *     a config layer that moved a number would break request assembly silently.
 *  2. **Validation fails fast, never silently.** A combination that cannot work
 *     (`noticeReserve >= budget`, a zero/negative threshold, a reserve that
 *     leaves no room for the tail) is rejected at construction, the same rule
 *     dsh's compaction plugin applies at load time.
 *  3. **Per-window scaling is a pure function.** Nothing here reads Android
 *     state; callers pass the window they already resolved, so a test can pin
 *     the corridor without a device.
 *
 * Deliberately NOT a plugin tree: the Android app has one process and one
 * composition, so the value of a config layer here is validation + one source
 * of truth, not discovery.
 */
internal data class HarnessConfig(
    /** Hard ceiling for the post-anchor tail, regardless of window size. */
    val postAnchorBudgetTokens: Int = 60_000,
    /** postAnchor share of the window (35% keeps summary+preAnchor+tail in budget). */
    val postAnchorWindowFraction: Double = 0.35,
    /** Tokens held back for the dropped-rounds notice + digest. */
    val noticeReserveTokens: Int = 1_200,
    /** Base notice cost, used to cap the digest text. */
    val noticeBaseTokens: Int = 64,
    /** Absolute floor that makes tool-result aging fire on a huge window. */
    val absoluteAgeTokens: Int = 24_000,
    /** Chars-per-token floor used by the aging gate (not an accounting figure). */
    val agingCharsPerToken: Long = 3L,
    /** Raw messages the dropped-rounds digest may inspect per render. */
    val digestScanMessages: Int = 512,
    /** Lines the digest may emit. */
    val digestMaxLines: Int = 24,
    /** Characters the digest may emit. */
    val digestMaxChars: Int = 3_000,
    /** Ledger entries before the oldest run folds into one sample line. */
    val ledgerMaxEntries: Int = 600,
    /** Tool rounds between drift checks ([DriftGuard] cadence). */
    val driftGuardEveryN: Int = 10,
) {

    /**
     * [T-harness-config] Reject combinations that cannot work.
     *
     * Every branch here corresponds to a failure that would otherwise show up as
     * "compaction did nothing" much later: a reserve larger than the budget
     * leaves a negative tail, a fraction outside (0,1) makes the tail either
     * nothing or the whole window, and a non-positive digest budget silently
     * disables the only record of dropped rounds.
     */
    fun validated(): HarnessConfig {
        require(postAnchorBudgetTokens > 0) {
            "postAnchorBudgetTokens must be positive (was $postAnchorBudgetTokens)"
        }
        require(postAnchorWindowFraction > 0.0 && postAnchorWindowFraction < 1.0) {
            "postAnchorWindowFraction must be in (0,1) (was $postAnchorWindowFraction)"
        }
        require(noticeReserveTokens > 0) {
            "noticeReserveTokens must be positive (was $noticeReserveTokens)"
        }
        require(noticeReserveTokens < postAnchorBudgetTokens) {
            "noticeReserveTokens ($noticeReserveTokens) must be smaller than " +
                "postAnchorBudgetTokens ($postAnchorBudgetTokens), or the tail has no room"
        }
        require(noticeBaseTokens in 1 until noticeReserveTokens) {
            "noticeBaseTokens ($noticeBaseTokens) must be in 1..<noticeReserveTokens"
        }
        require(absoluteAgeTokens > 0) { "absoluteAgeTokens must be positive" }
        require(agingCharsPerToken > 0) { "agingCharsPerToken must be positive" }
        require(digestScanMessages > 0) { "digestScanMessages must be positive" }
        require(digestMaxLines > 0) { "digestMaxLines must be positive" }
        require(digestMaxChars > 0) { "digestMaxChars must be positive" }
        require(ledgerMaxEntries > 1) { "ledgerMaxEntries must exceed 1" }
        require(driftGuardEveryN > 0) { "driftGuardEveryN must be positive (was $driftGuardEveryN)" }
        return this
    }

    /**
     * [T-harness-config] The post-anchor tail budget for a resolved window.
     *
     * Same shape as the shipped `PostAnchorTail.budgetTokensForWindow`, now
     * expressed against this instance so a per-session override changes the
     * number without touching the call sites:
     *  - `null` window → the hard ceiling;
     *  - otherwise the smaller of the ceiling and `window × fraction`.
     */
    fun postAnchorBudgetFor(windowTokens: Int?): Int {
        if (windowTokens == null || windowTokens <= 0) return postAnchorBudgetTokens
        val scaled = (windowTokens * postAnchorWindowFraction).toInt()
        // [T-harness-config-floor] A small-window model scales the ceiling below
        // the reserve, which used to leave a one-token tail: validated() only
        // guarded the CEILING, not the scaled value every caller actually uses.
        return maxOf(minOf(postAnchorBudgetTokens, scaled), noticeReserveTokens +1)
    }

    /** [T-harness-config] Tokens actually available to the kept tail. */
    fun effectiveTailBudget(budgetTokens: Int): Int =
        (budgetTokens - noticeReserveTokens).coerceAtLeast(1)

    companion object {
        /** Shipped defaults; every literal above mirrors a pre-existing constant. */
        val DEFAULT: HarnessConfig = HarnessConfig()

        /**
         * [T-harness-config-no-global] The active configuration is the shipped
         * default, always.
         *
         * WHY there is no mutable override: the first version kept a process-wide
         * `@Volatile override` plus a per-session file store. Nothing ever WROTE
         * the file (no settings entry), so the feature was unreachable, while the
         * global field would have leaked one chat's numbers into the next the
         * moment anything did write it. Per-model scaling already falls out of
         * [postAnchorBudgetFor] taking the window; a user-tunable override needs a
         * settings surface first and must be passed per call, not parked here.
         */
        fun current(): HarnessConfig = DEFAULT
    }
}
