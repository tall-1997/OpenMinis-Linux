package com.openminis.app.ui.chat

/**
 * [T-prompt-cache-turn-context] Request-side state for prompt-cache stability.
 *
 * Kept in one small object (instead of two long fields on ChatViewModel) so
 * the cache contract is easy to find and the oversized-file ratchet does not
 * grow: the ViewModel only holds `val promptCache = PromptCacheState()`.
 */
internal class PromptCacheState {
    /**
     * The per-turn context fragment (WorldBook, learned prefs, FTS recall,
     * task anchor, runtime date/language/model count, digest, compaction
     * excerpts). Computed once per agent run and appended to the latest
     * user-text message by [applyTurnContext]; never persisted.
     */
    @Volatile
    var turnContext: String? = null

    /**
     * Session-scoped snapshot of the GLOBAL.md + recent-daily-log fragments
     * injected into the system prompt. `memory_write` mutates today's log, so
     * re-reading it every turn used to rewrite the "stable" system head and
     * invalidate the whole prefix cache mid-session. Keyed by
     * session + date + memory toggle; the model still has `memory_get` for
     * anything written after the snapshot.
     */
    @Volatile
    var frozenMemory: FrozenMemoryFragments? = null
}

/** Snapshot behind [PromptCacheState.frozenMemory]; see its KDoc. */
internal data class FrozenMemoryFragments(
    val key: String,
    val global: String?,
    val daily: String?,
)
