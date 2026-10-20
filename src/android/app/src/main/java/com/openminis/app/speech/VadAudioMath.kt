package com.openminis.app.speech

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * [T-android-vad] Audio math shared by the recognition engines, extracted
 * from [ProviderSpeechRecognitionEngine] and [VoiceActivityDetector] so
 * those files stay under the architecture ceiling. No behaviour change.
 */

/** Rough dB estimate over the chunk for the UI waveform. */
internal fun rmsDb(buf: ByteArray, len: Int): Float {
    var sum = 0.0
    var count = 0
    var i = 0
    while (i + 1 < len) {
        val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
        sum += sample.toDouble() * sample
        count++
        i += 2
    }
    if (count == 0) return 0f
    val rms = sqrt(sum / count)
    if (rms <= 1.0) return 0f
    // Map amplitude RMS onto the [0, 12]-ish scale the System engine's
    // onRmsChanged reports, so the shared normalizer behaves identically.
    return (20 * log10(rms / 32768.0) + 50).toFloat().coerceIn(0f, 12f)
}

/** Perceptual level for the waveform (iOS `computeWaveformLevels`). */
private const val LEVEL_GAIN = 14f
private const val LEVEL_SCALE = 0.95f
private const val LEVEL_FLOOR = 0.04f

internal fun perceptualLevel(buf: FloatArray, n: Int): Float {
    var sumSq = 0.0f
    for (i in 0 until n) sumSq += buf[i] * buf[i]
    val rms = if (n > 0) sqrt(sumSq / n) else 0f
    val level = sqrt(min(1.0f, rms * LEVEL_GAIN)) * LEVEL_SCALE
    return min(1.0f, max(LEVEL_FLOOR, level))
}

/**
 * [T-android-vad-manual-stop] 48 kHz → 16 kHz PCM16, mono.
 *
 * Plain 3:1 decimation with a 3-sample box average. The capture path
 * already low-passes nothing, so averaging the samples that are dropped is
 * what keeps aliasing out of the band the recogniser listens to; picking
 * every third sample instead measurably degraded recognition of sibilants
 * in local testing. The ratio is fixed because both rates are compile-time
 * constants — the caller passes what we open AudioRecord with and what the
 * library emits.
 */
internal fun downsampleTo16k(pcm48: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
    val ratio = srcRate / dstRate // 3
    val inSamples = pcm48.size / 2
    val outSamples = inSamples / ratio
    val out = ByteArray(outSamples * 2)
    var si = 0
    for (o in 0 until outSamples) {
        var acc = 0
        for (k in 0 until ratio) {
            val b = si * 2
            acc += ((pcm48[b + 1].toInt() shl 8) or (pcm48[b].toInt() and 0xFF)).toShort().toInt()
            si++
        }
        val v = (acc / ratio).coerceIn(-32768, 32767)
        out[o * 2] = (v and 0xFF).toByte()
        out[o * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
}
