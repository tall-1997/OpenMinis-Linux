package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-token-meter-single-authority] The ONE place that turns inputs into a
 * number a consumer may act on.
 *
 * WHY this exists: before this object, four call sites each combined the same
 * two inputs by hand — the compact gate (`contextTokensForPolicy`), the UI ring
 * (`refreshContextUsage`), the Token Usage sheet, and the pre-send / in-loop
 * notices. The gate and the display had already drifted apart twice in this
 * branch (a budgeted slice measured against an un-budgeted threshold, then a
 * display value shown next to a gate that used a different slice), and both
 * times the observable symptom was the same: the number the user saw disagreed
 * with the decision the harness made.
 *
 * The precedence rule is therefore stated once, here, and every consumer calls
 * it instead of writing `maxOf(...)` for itself:
 *  - **display**: the provider reading wins when there is one (it is the billed
 *    prompt size of the last call, which is what the ring and the Token Usage
 *    sheet have always shown); the estimate is the pre-first-call fallback.
 *  - **pressure**: the LARGER of the two, because a trigger must never let a
 *    stale reading hide an oversized request.
 *
 * Which slice gets priced stays a policy question (the caller picks the list);
 * this object only decides how two numbers combine.
 *
 * Deliberately stateless and pure: no ViewModel, no IO, trivially testable.
 */
internal object TokenMeter {

    /**
     * Character composition of one slice. Used by the ring and by diagnostics;
     * NOT the gate (which uses calibrated token estimation).
     *
     * Counts the same things the historic ring code counted — content parts
     * only. `LLMMessage.content` is a display mirror of the leading text part
     * for most producers, so adding it here would double-count and silently
     * change the number users see.
     */
    data class Breakdown(
        val messageChars: Long = 0L,
        val toolUseChars: Long = 0L,
        val toolResultChars: Long = 0L,
        val imageCount: Int = 0,
    ) {
        val imageChars: Long get() = imageCount.toLong() * ESTIMATED_IMAGE_CHARS
        val totalChars: Long get() = messageChars + toolUseChars + toolResultChars + imageChars
    }

    /**
     * One resolved measurement. [providerTokens] and [estimatedTokens] are kept
     * so a diagnostic can say which input won.
     */
    data class Measurement(
        val totalTokens: Int,
        val providerTokens: Int,
        val estimatedTokens: Int,
        val breakdown: Breakdown = Breakdown(),
    ) {
        /** True when a provider reading exists (display prefers it). */
        val sourceIsProvider: Boolean get() = providerTokens > 0
    }

       /**
     * [T-token-meter-journal-source] Rebuild a measurement from the snapshot
     * the journal recorded for the last assembled request.
     *
     * WHY: the0.2 acceptance item is that the four consumers read ONE
     * number, and the journal is the only place that number is DURABLE - a
     * live measurement is recomputed per request, so a sheet/gate
     * disagreement cannot be checked after the fact. Reading the snapshot
     * back gives diagnostics (and the Token Usage sheet after a reload) the
     * exact figure the gate used.
     *
     * Null when the event predates the fields, so a caller falls back to a
     * live measurement rather than displaying a fabricated zero.
     */
    fun fromJournalSnapshot(event: org.json.JSONObject?): Measurement? {
        if (event == null) return null
        if (!event.has("estTokens") && !event.has("providerTokens")) return null
        val provider = event.optInt("providerTokens",0).coerceAtLeast(0)
        val estimated = event.optInt("estTokens",0).coerceAtLeast(0)
        return Measurement(
            // [T-token-meter-display-compat] Display keeps the provider reading when
            // there is one, exactly as the ring did before this refactor: it is the
            // billed prompt size of the last call, and the estimate (chars/3.5)
            // overshoots on code/ASCII history, so swapping in max() moved a
            // user-visible percentage by ~10%. The GATE keeps max() via pressure().
            totalTokens = if (provider >0) provider else estimated,
            providerTokens = provider,
            estimatedTokens = estimated,
        )
    }

 /** pi-ported empirical weight of one image, in characters. */
    // [T-token-meter-image-weight] One source: ContextTokenEstimator already
    // defines this number, and a second literal is how every duplicated
    // constant in this file's history drifted apart.
    val ESTIMATED_IMAGE_CHARS: Long = com.openminis.app.data.ContextTokenEstimator.ESTIMATED_IMAGE_CHARS.toLong()

    fun breakdown(messages: List<LLMMessage>): Breakdown {
        var messageChars = 0L
        var toolUseChars = 0L
        var toolResultChars = 0L
        var images = 0
        for (msg in messages) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> messageChars += part.text.length
                    is AgentContentPart.ToolUse ->
                        toolUseChars += part.inputJson.length
                    is AgentContentPart.ToolResult -> toolResultChars += part.content.length
                    is AgentContentPart.ImageData -> images += 1
                }
            }
        }
        return Breakdown(messageChars, toolUseChars, toolResultChars, images)
    }

    /**
     * The number to DISPLAY.
     *
     * [T-token-meter-display-compat] The provider reading wins when there is
     * one: it is the billed prompt size of the last completed call, which is
     * what the ring and the Token Usage sheet showed before this refactor,
     * and the character estimate runs ~10% high on code/ASCII history.
     * The GATE is the one that takes the larger of the two - see [pressure].
     */
    fun forDisplay(
        providerTokens: Int,
        estimatedTokens: Int,
        breakdown: Breakdown = Breakdown(),
    ): Measurement {
        val provider = providerTokens.coerceAtLeast(0)
        val estimated = estimatedTokens.coerceAtLeast(0)
        return Measurement(
            totalTokens = if (provider > 0) provider else estimated,
            providerTokens = provider,
            estimatedTokens = estimated,
            breakdown = breakdown,
        )
    }

    /**
     * The number a TRIGGER compares against a threshold: never below either
     * input, so neither a stale provider reading nor an under-estimate can
     * hide an oversized request.
     */
    fun pressure(providerTokens: Int, estimatedTokens: Int): Int =
        maxOf(providerTokens.coerceAtLeast(0), estimatedTokens.coerceAtLeast(0))
}
