package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError
import java.io.IOException

/**
 * [T-canceled-io-classify] OkHttp surfaces a cancelled call as
 * IOException("Canceled"), occasionally wrapped by a provider callbackFlow
 * teardown as CancellationException(cause) or NetworkError(cause).
 *
 * That is a LOCAL teardown artifact — the stall watchdog's cancel cascade
 * ([FirstEventWatchdog.streamStallWatchdog]: upstream.cancel() → call.cancel()
 * → the provider's blocked body read rethrows the IOException raw), an
 * HTTP/2 connection teardown, a fallback switch — not a provider network
 * failure. The old loop let it fall through: LoopErrorGate.isTransient
 * rejects raw IOExceptions and a non-LLMError means no fallback, so the bare
 * "Canceled" text hit setInlineError with no retry — field-observed: runs
 * died at the 45s/90s watchdog boundaries and the chat showed "Canceled"
 * inline.
 *
 * The classification: a transient stall whose retry resumes the partial turn
 * ([LLMError.TransientError.stalledAfterFirstEvent]) — the same contract the
 * watchdog's own verdicts use.
 */
internal object CanceledIoClassification {

    /** The OkHttp cancellation IOException inside [raw]'s cause chain, if any. */
    fun find(raw: Throwable): IOException? {
        var walker: Throwable? = raw
        while (walker != null) {
            if (walker is IOException && walker.message == "Canceled") return walker
            walker = walker.cause
        }
        return null
    }

    /**
     * The transient-stall classification for a teardown-cancelled stream, or
     * null when [raw] is not an OkHttp cancellation (the caller keeps its own
     * error path). [hasPartialText] marks mid-turn teardowns so the retry
     * resumes from the partial text instead of regenerating from scratch.
     */
    fun asTransientStall(raw: Throwable, hasPartialText: Boolean): LLMError.TransientError? {
        if (find(raw) == null) return null
        return LLMError.TransientError(
            "stream was cancelled mid-run (local teardown) — retrying on a fresh connection",
            stalledAfterFirstEvent = hasPartialText,
        )
    }
}
