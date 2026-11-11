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
 * [GH#521] True for chunks that represent actual model progress (text,
 * thinking, a tool call forming) and false for lifecycle/accounting markers.
 * The first-token phase must not be satisfied by `Started` (headers) or by an
 * early `Usage` block — those are exactly the "headers arrived, no token yet"
 * state the watchdog exists to bound.
 */
internal fun LLMStreamChunk.countsAsFirstTokenProgress(): Boolean = when (this) {
    is LLMStreamChunk.Started,
    is LLMStreamChunk.Usage,
    is LLMStreamChunk.Finished -> false
    else -> true
}

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
 * [GH#521] An optional third window, [firstContentTimeoutMs], sits between
 * them: it starts when headers (`Started`) land and expires if no real token
 * follows. It is off by default so the historical contract is unchanged.
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
    /**
     * [GH#521] 200 headers 之后、首个 token 之前的独立看门狗预算。
     *
     * [GH#521] Once response HEADERS arrive (`Started`), the phase-1 budget is
     * satisfied and previously nothing watched the gap before the first
     * TOKEN: an OpenAI-compatible gateway can return 200 + headers and then
     * sit silent for 15-30s (measured), which looked like a healthy stream.
     * With this > 0 the operator adds a distinct "headers → first content"
     * phase. `Started` does NOT count as content; text/thinking/tool chunks do.
     *
     * 0 keeps the historical two-phase contract (used by
     * [firstEventWatchdog]) so its regression tests stay valid.
     */
    firstContentTimeoutMs: Long = 0L,
    firstContentReason: String = "connected, but no first token within ${firstContentTimeoutMs / 1000}s",
): Flow<LLMStreamChunk> = channelFlow {
    val seen = AtomicBoolean(false)
    // [GH#521] Separate the "headers arrived" marker from "a token arrived".
    val contentSeen = AtomicBoolean(false)
    val headersAtNanos = AtomicLong(0L)
    val startNanos = System.nanoTime()
    val lastEventNanos = AtomicLong(startNanos)
    // FIRST_EVENT / FIRST_CONTENT / IDLE — which phase tripped, if any.
    val timedOutPhase = java.util.concurrent.atomic.AtomicReference<String?>(null)

    val upstream = launch {
        try {
            collect { chunk ->
                val now = System.nanoTime()
                seen.set(true)
                lastEventNanos.set(now)
                if (chunk is LLMStreamChunk.Started) {
                    headersAtNanos.compareAndSet(0L, now)
                } else if (chunk.countsAsFirstTokenProgress()) {
                    // Any real progress chunk (text, thinking, a tool call
                    // being formed) satisfies the first-token phase. Usage /
                    // Finished markers do not.
                    contentSeen.set(true)
                }
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
            val hasAny = seen.get()
            val hasContent = contentSeen.get()
            // Retire before computing any deadline: with phase 2 disabled and
            // no first-content phase the deadline would stay in the past
            // forever, and checking it first would kill a stream that is
            // merely slow *after* its first chunk — exactly the contract
            // `firstEventWatchdog` promises (and that `retires after the first
            // chunk, a late stop is not killed` pins).
            if (hasAny && hasContent && idleTimeoutMs <= 0L) return@launch
            if (hasAny && !hasContent && idleTimeoutMs <= 0L && firstContentTimeoutMs <= 0L) return@launch

            val now = System.nanoTime()
            val deadlineNanos: Long
            val phase: String
            when {
                !hasAny -> {
                    phase = "first-event"
                    deadlineNanos = startNanos + firstTimeoutMs * NANOS_PER_MILLI
                }
                !hasContent && firstContentTimeoutMs > 0L -> {
                    // [GH#521] Measured from headers, not from stream start:
                    // the upload/first-header wait already has its own bound
                    // at the transport layer, and counting it here would
                    // double-charge a slow upload.
                    phase = "first-content"
                    val base = headersAtNanos.get().takeIf { it > 0L } ?: startNanos
                    deadlineNanos = base + firstContentTimeoutMs * NANOS_PER_MILLI
                }
                idleTimeoutMs > 0L -> {
                    phase = "idle"
                    deadlineNanos = lastEventNanos.get() + idleTimeoutMs * NANOS_PER_MILLI
                }
                else -> return@launch
            }
            val remainingMs = (deadlineNanos - now) / NANOS_PER_MILLI
            if (remainingMs <= 0L) {
                timedOutPhase.set(phase)
                upstream.cancel()
                return@launch
            }
            delay(minOf(remainingMs, POLL_SLICE_MS))
        }
    }

    upstream.join()
    watchdog.cancel()
    when (timedOutPhase.get()) {
        // [GH#521] Headers landed but no token did: retry as transient. Do
        // NOT set stalledAfterFirstEvent — nothing visible was emitted, so
        // there is no partial text to resume from.
        "first-content" -> throw LLMError.TransientError(firstContentReason)
        // [T-stall-resume] Mark mid-stream stalls explicitly so the retry
        // layer can resume from the partial text instead of regenerating
        // from scratch (the stream WAS alive when it died).
        "idle" -> throw LLMError.TransientError(idleReason, stalledAfterFirstEvent = true)
    }
    if (!seen.get()) {
        throw LLMError.TransientError(reason)
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
