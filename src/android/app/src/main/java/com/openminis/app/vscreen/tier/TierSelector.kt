package com.openminis.app.vscreen.tier

/**
 * [T-android-vscreen-tier] Pure decision over a probe snapshot: which tiers
 * are worth attempting, in priority order, and what an honest total-failure
 * message looks like. No I/O — the whole file runs on the JVM in tests.
 */
object TierSelector {

    /**
     * Attempt order for [sheet]: tiers whose probe says Available, highest
     * priority first. Unavailable tiers are structurally out (skipped
     * silently — their reason is visible in [failureSummary]); Degraded
     * tiers are ALSO skipped here — a fresh failure must not re-pay its
     * 15 s timeout on the very next create; the state machine re-probes
     * them lazily after the backoff window.
     */
    fun planOrder(sheet: Map<VscreenTier, ProbeResult>): List<VscreenTier> =
        VscreenTier.PRIORITY.filter { sheet[it] is ProbeResult.Available }

    /** Highest tier that is currently attemptable, or null. */
    fun highestAvailable(sheet: Map<VscreenTier, ProbeResult>): VscreenTier? =
        planOrder(sheet).firstOrNull()

    /**
     * The agent-facing summary when every tier failed: one honest line per
     * tier with its specific reason, so the model can decide what to tell
     * the user (grant Shizuku / check root / the ROM refused).
     */
    fun failureSummary(sheet: Map<VscreenTier, ProbeResult>): String =
        VscreenTier.PRIORITY.joinToString("; ") { tier ->
            val reason = when (val p = sheet[tier]) {
                is ProbeResult.Available -> "attempted and failed (see earlier note)"
                is ProbeResult.Degraded -> "skipped, degraded: ${p.reason}"
                is ProbeResult.Unavailable -> p.reason
                null -> "not probed"
            }
            "${tier.name.lowercase()}: $reason"
        }

    /**
     * Upgrade hint for the list result: when a live screen runs on a tier
     * below the highest currently-available one, the agent may recreate it
     * to gain the higher tier's capabilities. Never migrates live screens.
     */
    fun upgradeHint(liveTiers: Set<VscreenTier>, sheet: Map<VscreenTier, ProbeResult>): String? {
        val best = highestAvailable(sheet) ?: return null
        if (liveTiers.isEmpty() || liveTiers.all { it.order <= best.order }) return null
        val live = liveTiers.filter { it.order > best.order }.minByOrNull { it.order }
        return "a higher tier (${best.name.lowercase()}) is now available; " +
            "screens still run on ${live?.name?.lowercase()} — recreate one to upgrade"
    }
}
