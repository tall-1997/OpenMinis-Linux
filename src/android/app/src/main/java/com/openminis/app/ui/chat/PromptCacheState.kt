package com.openminis.app.ui.chat

import com.openminis.app.provider.ImageBudget

/**
 * [T-prompt-cache-turn-context] Request-side state for prompt-cache stability.
 *
 * Kept in one small object (instead of several long fields on ChatViewModel) so
 * the cache contract is easy to find and the oversized-file ratchet does not
 * grow: the ViewModel only holds `val promptCache = PromptCacheState()`.
 */
internal class PromptCacheState {
    /**
     * The per-turn context fragment (WorldBook, learned prefs, FTS recall,
     * task anchor, runtime date/language/model count, digest, compaction
     * excerpts). Computed once per agent run. It is persisted as a hidden
     * `context` row after the latest user message, so the next turn's rebuilt
     * history is an append-extension of the request that was actually sent
     * (DSH-style); [applyTurnContext] only injects it when persistence failed
     * or the row predates this feature.
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

    /**
     * [T-memory-freeze-freshness] First line of a memory entry written after
     * the system snapshot was frozen. Consumed once by
     * [buildTurnContextFragment] and appended to the turn context (the cached
     * tail), so a mid-session `memory_write` is visible to the model without
     * rewriting the system prompt.
     */
    @Volatile
    var pendingMemoryWriteNote: String? = null

    /**
     * [T-image-elision-sticky] Images already replaced by a text placeholder
     * in this session. The request-budget planner must keep dropping them even
     * if compaction later lowers the total below the cap: un-eliding a
     * historical image rewrites old messages and invalidates the whole prefix.
     */
    private val elidedImages = linkedSetOf<ImageBudget.ImagePartId>()

    @Synchronized
    fun forcedImageDrops(): Set<ImageBudget.ImagePartId> = elidedImages.toSet()

    @Synchronized
    fun rememberElidedImages(ids: Collection<ImageBudget.ImagePartId>) {
        elidedImages.addAll(ids)
    }
}

/** Snapshot behind [PromptCacheState.frozenMemory]; see its KDoc. */
internal data class FrozenMemoryFragments(
    val key: String,
    val global: String?,
    val daily: String?,
)
