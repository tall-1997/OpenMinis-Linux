package com.openminis.app.agent

import com.openminis.app.data.ContextSaverPrefs
import com.openminis.app.data.ContextTokenMeter
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
 *  - The decision uses the mode, model window, reported usage and current history.
 *    Nothing here needs a ViewModel, repository or CoroutineScope.
 *  - The stats are display-only. Two ViewModels alive at once (a chat + a
 *    sub-agent host) simply overwrite the same "last request" reading, which is
 *    the same granularity the cache-hit-rate row already uses.
 *
 * The mode and reported-usage checks run before any history scan. If nothing
 * needs folding, the same list is returned and provider prefix caching is intact.
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

    data class AgeProjection(val messages: List<LLMMessage>, val stats: Stats)

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /**
     * Test seam: pin the level without touching SharedPreferences (unprimed
     * `ContextSaverPrefs.mode()` always answers BALANCED, which would leave the
     * OFF and AGGRESSIVE branches unreachable in a JVM test).
     */
    @Volatile
    internal var modeOverride: ContextSaverPrefs.Mode? = null

    /** Display-only conversion for [Stats.savedChars], separate from request metering. */
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
     * @param publish false for preview-only projections that must not update stats.
     * @param verifiedSpillPaths caller-verified recovery paths; this method performs no I/O.
     * @param keepRecentOverride optional shared recency boundary with other projections.
     * @return the history to send; the input instance when nothing folded.
     */
    fun age(
        history: List<LLMMessage>,
        windowTokens: Int,
        reportedTokens: Int,
        publish: Boolean = true,
        verifiedSpillPaths: Set<String> = emptySet(),
        keepRecentOverride: Int? = null,
    ): List<LLMMessage> {
        val projection = planAge(history, windowTokens, reportedTokens, verifiedSpillPaths, keepRecentOverride)
        if (publish) recordProjection(projection.stats)
        return projection.messages
    }

    /**
     * Whether [planAge] could fold anything for this request — the request
     * projector's gate for spill-path verification, so the disk I/O of path
     * checks runs only when the aging tier will actually project. Mirrors
     * planAge's trigger exactly: window-relative, or the absolute history
     * ceiling, or OFF means never.
     */
    fun willAge(history: List<LLMMessage>, windowTokens: Int, reportedTokens: Int): Boolean {
        val mode = modeOverride ?: ContextSaverPrefs.mode()
        if (mode == ContextSaverPrefs.Mode.OFF) return false
        val trigger = ContextSaverPrefs.triggerTokens(mode, windowTokens)
        if (reportedTokens >= trigger) return true
        return ContextTokenMeter.rawMessagesTokens(history) >= minOf(trigger, ABSOLUTE_AGE_TOKENS)
    }

    /** Project without publishing; call [recordProjection] only when sending the request. */
    fun planAge(
        history: List<LLMMessage>,
        windowTokens: Int,
        reportedTokens: Int,
        verifiedSpillPaths: Set<String> = emptySet(),
        keepRecentOverride: Int? = null,
    ): AgeProjection {
        val mode = modeOverride ?: ContextSaverPrefs.mode()
        if (mode == ContextSaverPrefs.Mode.OFF) {
            return AgeProjection(history, Stats(mode = mode.id))
        }
        val trigger = ContextSaverPrefs.triggerTokens(mode, windowTokens)
        // [T-absolute-age-ceiling] Three ways in: the API reported a big request,
        // the history alone is above the window-relative trigger, or it is above
        // the absolute ceiling (which fires on wide windows where the relative
        // trigger can never be met).
        if (reportedTokens < trigger) {
            val historyTokens = ContextTokenMeter.rawMessagesTokens(history)
            if (historyTokens < minOf(trigger, ABSOLUTE_AGE_TOKENS)) {
                return AgeProjection(history, Stats(mode = mode.id, triggerTokens = trigger))
            }
        }
        val plan = ToolResultAging.age(
            messages = history,
            keepRecent = keepRecentOverride ?: mode.keepRecent,
            minChars = mode.minChars,
            verifiedSpillPaths = verifiedSpillPaths,
        )
        return AgeProjection(
            messages = plan.messages,
            stats = Stats(mode.id, plan.agedCount, plan.savedChars, trigger),
        )
    }

    /** Test seam: reset the published stats and the pinned level between cases. */
    internal fun resetForTest() {
        modeOverride = null
        _stats.value = Stats()
    }

    fun recordProjection(stats: Stats) {
        // data-class equality: skip the StateFlow write (and any recomposition)
        // when this request folded nothing new — the steady state of a long chat.
        if (_stats.value != stats) _stats.value = stats
    }
}
