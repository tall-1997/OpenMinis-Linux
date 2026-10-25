package com.openminis.app.data

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-token-usage-calibration] Runtime estimator calibration.
 *
 * Every static heuristic (len/4, ASCII-3/CJK-1, etc.) drifts under real
 * workloads — different models tokenise differently, code blocks are denser
 * than prose, and a single session can span all three. This keeps a
 * per-model EMA scale factor that steers the estimate toward the API's
 * reported usage without ever letting one outlier jerk the lever too far.
 *
 * When the provider reports usage (the [LLMStreamChunk.Usage] event), the
 * caller passes `observe(modelKey, estimated, actual)`; the estimator
 * multiplies its raw heuristic by the stored scale.
 *
 * Thread-safe. No Android dependency (pure JVM testable).
 */
object TokenUsageCalibration {
    private const val MIN_SCALE = 1.0
    private const val MAX_SCALE = 8.0
    private const val ALPHA = 0.3 // EMA weight for new sample

    private val scales = ConcurrentHashMap<String, Double>()

    /** Current scale factor for this model (1.0 = uncalibrated). */
    fun scale(modelKey: String): Double =
        scales[modelKey] ?: 1.0

    /**
     * Feed one observation: [estimated] was the heuristic guess, [actual]
     * is the provider-reported context token count for the same call.
     */
    fun observe(modelKey: String, estimated: Int, actual: Int) {
        if (modelKey.isBlank() || estimated <= 0 || actual <= 0) return
        val ratio = (actual.toDouble() / estimated).coerceIn(MIN_SCALE, MAX_SCALE)
        scales.compute(modelKey) { _, prev ->
            val base = prev ?: 1.0
            base * (1.0 - ALPHA) + ratio * ALPHA
        }
    }

    /**
     * Apply the calibration to a raw heuristic estimate.
     * Returns the same value when uncalibrated (scale = 1.0).
     */
    fun estimate(modelKey: String, rawEstimate: Int): Int =
        (rawEstimate * scale(modelKey)).toInt().coerceAtLeast(1)
}