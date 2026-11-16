package com.openminis.app.agent

import com.openminis.app.data.RetryClassifier
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.ContextOverflowDetector

/**
 * [T-pi-retry-classifier][T-pi-overflow-detector] Single classification gate for
 * provider errors inside the agent loop (pi retry.ts / overflow.ts ports).
 *
 * Two decisions live here so the loop ext stays a thin consumer:
 *  - Transient-transport verdict: the pi retry blacklist (quota/billing/subscription
 *    exhaustion) OUTRANKS the transient whitelist — quota windows reset in hours,
 *    not seconds, so a "quota exceeded" body must not buy same-provider retries
 *    even when it arrived as a 429.
 *  - Overflow verdict: providers that report prompt overflow as a generic error
 *    (z.ai code 1261, HTTP 413, assorted "prompt too long" wordings) route into
 *    the loop's compact-and-retry branch; the detector's exclusion table vetoes
 *    rate-limit text so throttling is never misread as overflow.
 */
object LoopErrorGate {

    /**
     * True when the loop should retry on the SAME provider: the transport shape
     * is transient (network / server 5xx / connect-phase timeout / 429) AND the
     * message is neither permanently capacity-limited nor quota-exhausted.
     * [is5xx] and [isPermanentCapacity] come from the caller's existing
     * ProviderError.detail classification.
     */
    fun isTransient(error: Throwable, is5xx: Boolean, isPermanentCapacity: Boolean): Boolean {
        val transportTransient = error is LLMError.NetworkError ||
            error is LLMError.TransientError ||
            error is LLMError.RateLimited ||
            is5xx ||
            (error is LLMError.Timeout && error.phase == LLMError.Timeout.TimeoutPhase.CONNECT)
        if (!transportTransient || isPermanentCapacity) return false
        return RetryClassifier.classify(error.message, null) != RetryClassifier.RetryClass.QUOTA_EXHAUSTED
    }

    /**
     * True when the failure is a prompt/context overflow and the loop's
     * compact-and-retry branch should engage. Delegates to
     * [ContextOverflowDetector.detectFromLlmError].
     */
    fun isContextOverflow(error: Throwable): Boolean =
        (error as? LLMError)?.let { ContextOverflowDetector.detectFromLlmError(it).isOverflow } ?: false
}
