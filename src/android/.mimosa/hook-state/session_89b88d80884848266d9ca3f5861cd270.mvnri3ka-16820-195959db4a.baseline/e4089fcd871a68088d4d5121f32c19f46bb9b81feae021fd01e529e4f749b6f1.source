package com.openminis.app.provider

/**
 * [T-stall-echo-strip] Deterministic echo suppression for stall-resume retries.
 *
 * [StallResume] carries a verbatim tail of the already-shown text into the
 * retried request as a continuation seed. Echo-prone models restate that
 * seed before continuing, which duplicated the whole stretch on screen
 * (observed 2026-10-05 on 2.0.36: byte-identical restart at the seed's
 * first character, the second copy a superset that kept growing). The
 * "do not restate" instruction in the seed is advisory; this guard is
 * arithmetic and model-independent.
 *
 * Contract: [feed] receives the retried attempt's text deltas in order and
 * returns the part that may reach consumers. The stripper aligns incoming
 * bytes against the exact seed it was constructed with (the same tail
 * [StallResume.note] embeds, obtained via [StallResume.tail]):
 *
 *  - while the buffer agrees with the seed byte-for-byte it is buffered —
 *    the echo may still be growing, so nothing can be published yet;
 *  - the moment the buffer diverges from the seed (or outgrows it), the
 *    matched span is the echo: if it is at least [MIN_ECHO] chars it is
 *    dropped, everything after passes through, and the stripper resolves
 *    to plain pass-through for the rest of the stream;
 *  - a divergence before [MIN_ECHO] is treated as coincidence (the model
 *    simply did not echo) and the whole buffer passes through;
 *  - [flush] resolves any buffered remainder at stream end.
 *
 * Worst-case display latency equals the seed length (echo held back until
 * the divergence point); a no-echo resume diverges within the first few
 * bytes, so the common case costs almost nothing. A rewritten (non-verbatim)
 * echo diverges immediately and is intentionally left alone — that is not
 * the byte-identical duplication this guard exists for.
 */
class StallEchoStripper(
    seed: String,
    private val minEcho: Int = MIN_ECHO,
) {
    private val seedText: String = seed
    private var buffer: StringBuilder? = if (seed.isEmpty()) null else StringBuilder()
    private var matched = 0

    /** Feed one retried-attempt delta; returns the part that may be shown. */
    fun feed(chunk: String): String {
        if (chunk.isEmpty()) return ""
        val buf = buffer ?: return chunk // resolved — plain pass-through
        buf.append(chunk)
        return drain()
    }

    /** Resolve any state still held when the stream ends. */
    fun flush(): String {
        val buf = buffer ?: return ""
        buffer = null
        // Stream ended while still aligned inside the seed: the matched
        // span is the echo (nothing new ever arrived behind it). Below the
        // threshold it was never an echo — hand everything back.
        return if (matched >= minEcho) {
            if (buf.length > matched) buf.substring(matched) else ""
        } else {
            buf.toString()
        }
    }

    private fun drain(): String {
        val buf = buffer ?: return ""
        // Grow the byte-for-byte match against the seed from where the
        // previous feed left off — incremental, O(chunk) per call.
        val seedEnd = minOf(buf.length, seedText.length)
        var t = matched
        while (t < seedEnd && buf[t] == seedText[t]) t++
        val seedExhausted = t == seedText.length
        if (t < seedEnd || (seedExhausted && buf.length > seedText.length)) {
            // Diverged from the seed, or the full seed is behind us with
            // fresh content in the same buffer — resolve now.
            val skip = if (t >= minEcho) t else 0
            buffer = null
            return if (skip == 0) buf.toString() else buf.substring(skip)
        }
        // Still inside the seed echo — keep buffering.
        matched = t
        return ""
    }

    companion object {
        /** Aligned spans shorter than this are coincidence, not an echo. */
        const val MIN_ECHO = 64
    }
}
