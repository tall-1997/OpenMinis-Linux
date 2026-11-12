package com.openminis.app.provider

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * [GH#188/#199] Per-call state shared between the OkHttp event listener and
 * [StreamTtfb]. Mirrors the OpenAI `CallWatchState` for the Anthropic/Gemini
 * clients, whose only watchdog used to count request-body UPLOAD time against
 * the time-to-first-byte budget and then let the retry reuse the same pooled
 * connection.
 *
 * Attached to the request as an OkHttp tag; absent for calls that do not opt
 * in (the listener then does nothing).
 */
internal class TtfbWatchState {
    /** requestBodyEnd — the moment upload finished (monotonic nanos). */
    val uploadDoneAtNanos = AtomicLong(0L)

    /** The physical connection serving this call, for targeted eviction. */
    val connection = AtomicReference<Connection?>(null)
}

/**
 * Minimal listener that fills [TtfbWatchState] in. One instance per provider
 * client is enough: it reads the state off the request tag, so concurrent
 * calls never share counters.
 */
internal class UploadAwareTtfbListener : EventListener() {

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        call.request().tag(TtfbWatchState::class.java)?.uploadDoneAtNanos?.set(System.nanoTime())
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        call.request().tag(TtfbWatchState::class.java)?.connection?.set(connection)
    }
}
