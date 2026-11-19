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
 *  - **display** and **pressure** both take the LARGER of the two. The provider
 *    reading describes the PREVIOUS request, so right after a large paste the
 *    pending estimate is the honest number; and for a trigger, a stale-but-real
 *    reading must never make an oversized request look small.
 *  - The provider reading is only ever smaller-or-equal in that comparison, so
 *    a first call (no reading) naturally shows the estimate.
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
        /** True when the provider reading is the larger input. */
        val sourceIsProvider: Boolean get() = providerTokens > 0 && providerTokens >= estimatedTokens
    }

    /** pi-ported empirical weight of one image, in characters. */
    const val ESTIMATED_IMAGE_CHARS: Long = 4_800L

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
                        toolUseChars += TokenScanMemo.toolUseInputJson(part.input).length
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
     * [T-token-meter-display-louder] Deliberately the LARGER of the two, not
     * "provider wins". A provider reading describes the PREVIOUS request; right
     * after a large paste or a big tool result the pending request is already
     * bigger than that reading, and showing the stale-smaller number is exactly
     * the "sheet says 40k while the gate is about to compact" symptom this
     * object was created to end. The ring's historic behaviour was
     * `maxOf(provider, estimate)` and stays that way.
     */
    fun forDisplay(
        providerTokens: Int,
        estimatedTokens: Int,
        breakdown: Breakdown = Breakdown(),
    ): Measurement {
        val provider = providerTokens.coerceAtLeast(0)
        val estimated = estimatedTokens.coerceAtLeast(0)
        return Measurement(
            totalTokens = maxOf(provider, estimated),
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