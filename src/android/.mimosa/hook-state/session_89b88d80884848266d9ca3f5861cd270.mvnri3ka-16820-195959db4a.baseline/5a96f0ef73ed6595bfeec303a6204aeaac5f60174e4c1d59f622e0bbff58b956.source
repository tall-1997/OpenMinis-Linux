package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.logging.AppLogger

/**
 * Refuses request bodies that would abort the process while being built.
 *
 * ## Why this exists
 *
 * A request body is not just the bytes that go on the wire — building it costs
 * more than that. Serializing a JSON body holds the source object plus the old
 * and new buffers at once, and a body carrying conversation-scale inline images
 * can run to tens of megabytes. On a device that has already spent its footprint
 * budget, the allocation itself is what kills the process: the request never
 * lands, and the crash report points at the allocator rather than at the body
 * that was too big.
 *
 * ## Two gates
 *
 * 1. **Fixed ceiling** — a body at or above [MAX_BODY_BYTES] is refused
 *    outright. Deterministic, needs no memory information at all, and it is the
 *    gate that catches the common case (a handful of 12-megapixel PNGs at
 *    ~15 MB each). This one must never be relaxed.
 *
 * 2. **Dynamic headroom** — above [HEADROOM_FLOOR_BYTES] we also require
 *    [HttpBody.serializationMultiplier] × the body to fit in what the process
 *    has left. The multiplier is what keeps the check honest about how the body
 *    is actually produced:
 *
 *      - **JSON → 3x.** Serialization holds the source object, the old buffer
 *        and the doubled new buffer together at peak.
 *      - **multipart / raw bytes → 1x.** Those bytes are already resident in
 *        memory before we get here, and OkHttp streams them to the socket one
 *        part at a time. Demanding 3x would refuse a legitimate 20 MB image
 *        upload — the exact use case multipart exists for.
 *
 * Below the floor the dynamic check is skipped entirely: ordinary text requests
 * must not fail because of a transient near-death memory dip that would not
 * have mattered for a few hundred kilobytes.
 *
 * ## The honest limitation
 *
 * There is no public Android API for the per-process memory ceiling that LMK
 * and cgroup actually enforce, so nothing here can be exact. What we do have is
 * the Java heap's own accounting (`Runtime.maxMemory()` minus what is
 * allocated), which is what the headroom check uses. It covers the Java side of
 * the peak and misses native, so gate 2 is best-effort by construction — gate 1
 * is the one that must hold the line, because it needs no memory information at
 * all.
 *
 * The app ships `android:largeHeap="true"`, so `maxMemory()` is the
 * `largeMemoryClass` ceiling rather than the default one.
 */
object RequestBodyGate {

    private const val TAG = "RequestBodyGate"

    /** Bodies at or above this are refused outright. */
    const val MAX_BODY_BYTES = 32L * 1024L * 1024L

    /** Below this the dynamic check is skipped. */
    private const val HEADROOM_FLOOR_BYTES = 4L * 1024L * 1024L

    /**
     * Throws [LLMError.ProviderError] when [body] must not be sent.
     *
     * @param context caller identity for the log line ("rawPassthrough",
     *   "editImage", …) so a field report names the code path that asked.
     */
    fun check(body: HttpBody, context: String) {
        val estimated = body.estimatedBytes
        if (estimated < 0) {
            throw LLMError.ProviderError("Request size is unknown; refusing to build it.")
        }

        // ---- Gate 1: fixed ceiling -------------------------------------
        if (estimated >= MAX_BODY_BYTES) {
            val mb = estimated / (1024 * 1024)
            val limitMb = MAX_BODY_BYTES / (1024 * 1024)
            val msg = "Request too large (about $mb MB, limit $limitMb MB). " +
                "Start a new conversation or remove large images/attachments, then try again."
            AppLogger.error(
                TAG,
                "🛑 [$context] request body ~${mb}MB exceeds the ${limitMb}MB ceiling; " +
                    "refusing to build it (would risk an out-of-memory abort).",
            )
            throw LLMError.ProviderError(msg)
        }

        // ---- Gate 2: dynamic headroom ----------------------------------
        if (estimated <= HEADROOM_FLOOR_BYTES) return
        val needed = estimated * body.serializationMultiplier
        val available = availableJavaHeapBytes()
        // 0 means "no information" (a hardened ROM, or a runtime that refuses
        // to tell us). Treat that as unknown, never as "no memory" — failing a
        // request we could have made is worse than letting it through.
        if (available <= 0) {
            throw LLMError.ProviderError("Heap headroom is unknown; refusing to build this request.")
        }
        if (needed > available) {
            val neededMb = needed / (1024 * 1024)
            val availableMb = available / (1024 * 1024)
            val msg = "Not enough memory to send this request (about $neededMb MB needed, " +
                "$availableMb MB free). Close other sessions or restart the app, then try again."
            AppLogger.error(
                TAG,
                "🛑 [$context] request body needs ~${neededMb}MB to build " +
                    "but only ${availableMb}MB of heap remains; refusing (would risk an OOM abort).",
            )
            throw LLMError.ProviderError(msg)
        }
    }

    /**
     * Free Java heap in bytes, or 0 when unknown. See the class KDoc for why
     * this is a proxy rather than the real per-process limit.
     */
    private fun availableJavaHeapBytes(): Long = try {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        (rt.maxMemory() - used).coerceAtLeast(0L)
    } catch (_: Throwable) {
        0L
    }
}
