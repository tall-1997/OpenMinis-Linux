package com.openminis.app.provider

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call

/** Close blocking HTTP reads at cancellation onset, not after the reader finishes. */
@OptIn(InternalCoroutinesApi::class)
internal fun Call.cancelOnCancellation(job: Job?): DisposableHandle? =
    job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) cancel()
    }

/** A terminal SSE event may precede usage. Drain briefly, never wait for remote EOF. */
internal class StreamTailDrain(
    private val scope: CoroutineScope,
    private val cancelRead: () -> Unit,
    private val graceMs: Long = 1_500L,
) : AutoCloseable {
    private var timer: Job? = null
    private val expired = AtomicBoolean(false)
    val isExpired: Boolean get() = expired.get()

    fun terminal() {
        if (timer != null) return
        timer = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            delay(graceMs)
            expired.set(true)
            cancelRead()
        }
    }

    override fun close() {
        timer?.cancel()
    }
}
