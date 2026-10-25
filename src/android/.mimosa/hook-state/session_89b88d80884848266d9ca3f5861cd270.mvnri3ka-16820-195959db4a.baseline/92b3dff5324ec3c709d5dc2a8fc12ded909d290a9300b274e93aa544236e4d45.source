package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [T-stream-stall-watchdog] Bound how long a model stream may go silent.
 *
 * ## The gap this closes
 *
 * [firstEventWatchdog] only ever guarded the *first* chunk, and said so: "once
 * anything arrives the watchdog retires and the stream runs to completion".
 * Nothing else watched the stream after that, so the only remaining bound was
 * the transport's own read timeout — 600s on `OpenAIProvider`, 10min on the
 * Anthropic and Gemini clients. A relay that emits one delta and then goes
 * quiet therefore pinned the UI in "streaming" for up to ten minutes with the
 * Stop button as the only way out, and none of the retry/fallback machinery
 * ever ran because no exception was ever thrown.
 *
 * That is provider-agnostic: it happens on any gateway that drops or stalls an
 * SSE connection mid-generation (measured on a public relay as
 * `curl: (56) Failure when receiving data from the peer` and as a 60s
 * zero-byte timeout under 5-way concurrency), and it happens on flaky mobile
 * networks where the TCP connection survives but stops delivering.
 *
 * ## Two phases, one operator
 *
 * - **phase 1** — nothing has arrived yet: allow [firstTimeoutMs]. The
 *   request may still be queued, or the model may be thinking before its first
 *   token.
 * - **phase 2** — the stream is alive: allow [idleTimeoutMs] of silence
 *   between chunks. The connection already proved itself, so a long gap now
 *   means progress stopped, not that it is slow to start.
 *
 * Phase 2 is deliberately **opt-in**: [idleTimeoutMs] of 0 disables it, which
 * is what [firstEventWatchdog] passes so its documented contract (and its
 * regression test `retires after the first chunk, a late stop is not killed`)
 * is unchanged.
 *
 * Both phases surface [LLMError.TransientError], which is `isRetryable`. The
 * agent loop's retry path already rolls this turn's partial blocks back to
 * `turnStartBlockIndex` and keeps the text the user read on screen while the
 * fresh attempt streams into a new buffer (`[T-android-fallback-text-rewind]`),
 * so a mid-stream stall retries without duplicating content.
 *
 * ## Why polling
 *
 * The deadline is recomputed from the last event timestamp on a 1s tick rather
 * than by restarting a timer job per chunk. A stream can deliver hundreds of
 * chunks per second; cancelling and relaunching a coroutine for each one costs
 * far more than one wake per second, and the tick is capped by
 * `min(remaining, slice)` so short timeouts stay precise.
 */
fun Flow<LLMStreamChunk>.streamStallWatchdog(
    firstTimeoutMs: Long,
    idleTimeoutMs: Long = 0L,
    reason: String = "no first chunk within ${firstTimeoutMs / 1000}s",
    idleReason: String = "stream stalled — no chunk for ${idleTimeoutMs / 1000}s",
): Flow<LLMStreamChunk> = channelFlow {
    val seen = AtomicBoolean(false)
    val startNanos = System.nanoTime()
    val lastEventNanos = AtomicLong(startNanos)
    val stalledAfterFirstEvent = AtomicBoolean(false)

    val upstream = launch {
        try {
            collect { chunk ->
                seen.set(true)
                lastEventNanos.set(System.nanoTime())
                send(chunk)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled by the watchdog (or by the outer job) — nothing to
            // rethrow here; the job completes cancelled and join() returns.
            throw e
        }
    }

    val watchdog = launch {
        while (isActive) {
            // Retire before computing any deadline: with phase 2 disabled the
            // phase-1 deadline stays in the past forever, and checking it first
            // would kill a stream that is merely slow *after* its first chunk —
            // exactly the contract `firstEventWatchdog` promises (and that
            // `retires after the first chunk, a late stop is not killed` pins).
            if (seen.get() && idleTimeoutMs <= 0L) return@launch
            val now = System.nanoTime()
            val deadlineNanos = if (!seen.get() || idleTimeoutMs <= 0L) {
                startNanos + firstTimeoutMs * NANOS_PER_MILLI
            } else {
                lastEventNanos.get() + idleTimeoutMs * NANOS_PER_MILLI
            }
            val remainingMs = (deadlineNanos - now) / NANOS_PER_MILLI
            if (remainingMs <= 0L) {
                // Only phase 2 counts as a stall; phase 1 keeps the historical
                // "never produced anything" wording.
                stalledAfterFirstEvent.set(seen.get())
                upstream.cancel()
                return@launch
            }
            delay(minOf(remainingMs, POLL_SLICE_MS))
        }
    }

    upstream.join()
    watchdog.cancel()
    if (!seen.get()) {
        throw LLMError.TransientError(reason)
    }
    if (stalledAfterFirstEvent.get()) {
        // [T-stall-resume] Mark mid-stream stalls explicitly so the retry
        // layer can resume from the partial text instead of regenerating
        // from scratch (the stream WAS alive when it died).
        throw LLMError.TransientError(idleReason, stalledAfterFirstEvent = true)
    }
}

/**
 * [T-first-event-watchdog] Cancel a stream that produces no chunk at all
 * within [timeoutMs], then surface a [LLMError.TransientError] so the agent
 * loop's existing auto-retry/fallback chain takes over.
 *
 * A hung relay (DNS/TCP/TLS/read below the provider layer) never throws — it
 * just never emits. Without this operator the collector waits forever and no
 * retry machinery ever runs ("thinking… forever, stop button still armed").
 *
 * Muse-equivalent: FirstEventWatchdog 45s default / 90s for reasoning models.
 * The timeout is per FIRST chunk — once anything arrives (thinking delta,
 * text, tool call), the watchdog retires and the stream runs to completion.
 *
 * Prefer [streamStallWatchdog] for live provider streams: it keeps this
 * contract and additionally bounds mid-stream silence. This wrapper exists so
 * the first-event-only behaviour stays available (and stays covered by
 * `FirstEventWatchdogTest`).
 */
fun Flow<LLMStreamChunk>.firstEventWatchdog(
    timeoutMs: Long,
    reason: String = "no first chunk within ${timeoutMs / 1000}s",
): Flow<LLMStreamChunk> = streamStallWatchdog(
    firstTimeoutMs = timeoutMs,
    idleTimeoutMs = 0L,
    reason = reason,
)

private const val NANOS_PER_MILLI = 1_000_000L

/** Watchdog tick. Short timeouts stay precise because the delay is capped by the remaining budget. */
private const val POLL_SLICE_MS = 1_000L
