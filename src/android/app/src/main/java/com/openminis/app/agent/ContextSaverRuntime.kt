package com.openminis.app.agent

import com.openminis.app.data.ContextSaverPrefs
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-tool-result-aging] The wiring half of the history-side token saver:
 * decides *whether* to run [ToolResultAging] on this request, and publishes what
 * it did so the Token Usage sheet can show it.
 *
 * Deliberately an object rather than a ChatViewModel field:
 *
 *  - The decision reads exactly three inputs (mode pref, model context window,
 *    provider-reported usage). Nothing here needs the ViewModel, a repository,
 *    or a CoroutineScope — keeping it out of `ChatViewModel` is what lets the
 *    send path stay a single-line call at the history seam.
 *  - The stats are display-only. Two ViewModels alive at once (a chat + a
 *    sub-agent host) simply overwrite the same "last request" reading, which is
 *    the same granularity the cache-hit-rate row already uses.
 *
 * Gate order is cheapest-first so a short session pays one enum read and one
 * integer compare, and the returned list is the SAME instance in that case —
 * the outgoing JSON is byte-identical to no-feature-at-all, which keeps provider
 * prefix caching intact.
 */
object ContextSaverRuntime {

    /** What the most recent request actually folded. Zeroes = pass skipped. */
    data class Stats(
        val mode: String = ContextSaverPrefs.Mode.BALANCED.id,
        val aged: Int = 0,
        val savedChars: Long = 0L,
        /** Token floor this request had to cross before the pass could run. */
        val triggerTokens: Int = 0,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /**
     * Test seam: pin the level without touching SharedPreferences (unprimed
     * `ContextSaverPrefs.mode()` always answers BALANCED, which would leave the
     * OFF and AGGRESSIVE branches unreachable in a JVM test).
     */
    @Volatile
    internal var modeOverride: ContextSaverPrefs.Mode? = null

    /**
     * Mixed CJK + code runs ~3 chars/token; this is a floor gate, not an
     * accounting figure, so the cheap constant beats re-walking the history
     * with a real estimator. Public: the Token Usage sheet divides
     * [Stats.savedChars] by it, and two literals would drift.
     */
    const val CHARS_PER_TOKEN = 3L

    /**
     * [T-absolute-age-ceiling] Floor that makes aging fire even on a huge window.
     *
     * The window-relative trigger assumes "big request" means "close to the
     * model's ceiling". On a 200K-window model a 48K-token request is 24% of the
     * window, so the trigger never fires — and the request keeps carrying every
     * historical tool result verbatim, forever. Measured on a real session
     * (2026-11-05 log): `prompt_tokens=48,440`, `toolResultParts=138`,
     * first token at +4.5s after headers, ~20 output tokens/s. Nothing was
     * folded because the threshold was window-relative and the window was wide.
     *
     * Per-step prompt size is what prefill latency scales with, so the cap has to
     * be absolute: once the history alone is this large, folding old results pays
     * for itself on every remaining step of the turn. 24k tokens leaves room for
     * the recent results the model is actually working from (BALANCED keeps the
     * newest [com.openminis.app.data.ContextSaverPrefs.Mode.keepRecent] verbatim).
     */
    const val ABSOLUTE_AGE_TOKENS = 24_000

    /**
     * Apply the configured saving level to [history] for one outgoing request.
     *
     * @param windowTokens the model's effective context window (0 = unknown).
     * @param reportedTokens input tokens reported by the previous API call.
     * @return the history to send; the input instance when nothing folded.
     */
    fun age(history: List<LLMMessage>, windowTokens: Int, reportedTokens: Int): List<LLMMessage> {
        val mode = modeOverride ?: ContextSaverPrefs.mode()
        if (mode == ContextSaverPrefs.Mode.OFF) {
            publish(Stats(mode = mode.id))
            return history
        }
        val trigger = ContextSaverPrefs.triggerTokens(mode, windowTokens)
        val floorTokens = (ToolResultAging.totalChars(history) / CHARS_PER_TOKEN).toInt()
        // [T-absolute-age-ceiling] Three ways in: the API reported a big request,
        // the history alone is above the window-relative trigger, or it is above
        // the absolute ceiling (which fires on wide windows where the relative
        // trigger can never be met).
        if (reportedTokens < trigger && floorTokens < trigger && floorTokens < ABSOLUTE_AGE_TOKENS) {
            publish(Stats(mode = mode.id, triggerTokens = trigger))
            return history
        }
        val plan = ToolResultAging.age(history, mode.keepRecent, mode.minChars)
        publish(
            Stats(
                mode = mode.id,
                aged = plan.agedCount,
                savedChars = plan.savedChars,
                triggerTokens = trigger,
            ),
        )
        return if (plan.changed) plan.messages else history
    }

    /** Test seam: reset the published stats and the pinned level between cases. */
    internal fun resetForTest() {
        modeOverride = null
        _stats.value = Stats()
    }

    private fun publish(stats: Stats) {
        // data-class equality: skip the StateFlow write (and any recomposition)
        // when this request folded nothing new — the steady state of a long chat.
        if (_stats.value != stats) _stats.value = stats
    }
}
