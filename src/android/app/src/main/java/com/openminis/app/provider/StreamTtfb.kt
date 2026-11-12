package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/**
 * Time-to-first-byte watchdog for providers whose only bound is OkHttp's
 * `readTimeout`.
 *
 * [GH#188] 上传阶段与首包阶段分开计时：上传不计入 TTFB；超时取消调用并
 * 只关闭本次使用的连接，重试不会复用同一条 HTTP/2 连接。
 *
 * Anthropic/Gemini used a 10-minute read timeout and nothing else. A request
 * written into a half-open connection (local proxy survived a network flap,
 * server never answers) produced no headers, no failure and no bytes until
 * that 10-minute timeout — the UI sat in "thinking" and retry/cancel felt
 * broken. OpenAI already had a per-call watchdog; this is the same guard for
 * the other two.
 *
 * [GH#188] The window now has two phases when a [TtfbWatchState] is supplied:
 *  - UPLOAD: call start → `requestBodyEnd`, with the same generous budget.
 *    A 1MB multimodal body over a slow proxy legitimately takes 20-25s and
 *    must not be mistaken for a dead server.
 *  - TTFB: `requestBodyEnd` → response headers, measured FROM upload end.
 * On timeout this cancels the call AND closes the one captured connection, so
 * the automatic retry cannot reuse the same poisoned pooled HTTP/2 connection
 * (the second half of #188; compare the OpenAI path's targeted eviction).
 *
 * Without a watch state (existing callers) the behaviour is unchanged: one
 * budget from call start.
 *
 * The watchdog only covers the wait for response HEADERS. Once the stream is
 * flowing the agent loop's `streamStallWatchdog` owns the body bounds. Call
 * cancellation unblocks `execute()` with an [IOException], which is mapped to
 * a retryable error.
 */
internal object StreamTtfb {

    const val TIMEOUT_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun execute(
        call: Call,
        provider: String,
        timeoutMs: Long = TIMEOUT_MS,
        watch: TtfbWatchState? = null,
    ): Response {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val headersArrived = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val timedOutPhase = AtomicReference<String?>(null)
        val watchdog = scope.launch {
            val startedAtNanos = System.nanoTime()
            while (!headersArrived.get()) {
                val now = System.nanoTime()
                val uploadDone = watch?.uploadDoneAtNanos?.get() ?: 0L
                val (phase, elapsedMs) = classifyPhase(
                    startedAtNanos = startedAtNanos,
                    uploadDoneAtNanos = uploadDone,
                    nowNanos = now,
                    hasWatch = watch != null,
                )
                if (elapsedMs >= timeoutMs) {
                    if (headersArrived.get()) return@launch
                    timedOut.set(true)
                    timedOutPhase.set(phase)
                    com.openminis.app.logging.AppLogger.warning(
                        provider,
                        "[T-android-stream-ttfb] no response headers ($phase phase, " +
                            "${timeoutMs / 1000}s) — cancelling call + evicting connection",
                    )
                    call.cancel()
                    // [GH#188] Targeted eviction: only this call's physical
                    // connection. Never evictAll — concurrent sessions may be
                    // holding healthy connections in the shared pool.
                    runCatching { watch?.connection?.get()?.socket()?.close() }
                    return@launch
                }
                val remaining = timeoutMs - elapsedMs
                delay(minOf(remaining, POLL_SLICE_MS).coerceAtLeast(1L))
            }
        }
        return try {
            call.execute()
        } catch (e: IOException) {
            if (timedOut.get()) {
                val phase = timedOutPhase.get()
                throw LLMError.TransientError(
                    if (phase == "upload") {
                        "request body upload did not finish within ${timeoutMs / 1000}s " +
                            "(TTFB) — check network/proxy"
                    } else {
                        "no response from server (${timeoutMs / 1000}s TTFB) — check network/proxy"
                    },
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            watchdog.cancel()
        }
    }

    private const val NANOS_PER_MILLI = 1_000_000L

    /**
     * [GH#188] Which budget the watchdog is spending, and how long it has
     * spent: `upload` counts from call start (writeTimeout bounds a stalled
     * socket, this catches a trickling proxy), `ttfb` counts from
     * requestBodyEnd only. A caller without a watch state keeps the old
     * single clock from call start (reported as `ttfb` for message parity).
     *
     * Pure — pinned by StreamTtfbPhaseTest.
     */
    internal fun classifyPhase(
        startedAtNanos: Long,
        uploadDoneAtNanos: Long,
        nowNanos: Long,
        hasWatch: Boolean,
    ): Pair<String, Long> = when {
        hasWatch && uploadDoneAtNanos > 0L ->
            "ttfb" to (nowNanos - uploadDoneAtNanos) / NANOS_PER_MILLI
        hasWatch ->
            "upload" to (nowNanos - startedAtNanos) / NANOS_PER_MILLI
        else ->
            "ttfb" to (nowNanos - startedAtNanos) / NANOS_PER_MILLI
    }

    /** Poll cadence; short budgets stay precise because the delay is capped. */
    private const val POLL_SLICE_MS = 250L
}
