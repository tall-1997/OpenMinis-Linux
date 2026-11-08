package com.openminis.app.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [T-android-stop-cancels-socket] Blocking [Call.execute] does not notice
 * coroutine cancellation until the socket times out: the collector is torn
 * down (user pressed Stop) while the producer thread stays parked in the
 * socket read. This primitive resumes the call teardown from the
 * cancellation handler — [Call.cancel] closes the underlying socket, which
 * unblocks the parked read with an IOException immediately.
 *
 * [invokeOnCompletion][kotlinx.coroutines.Job.invokeOnCompletion] fires on
 * cancellation as well, so the streaming producers that already hook it
 * (Anthropic/Gemini) get the same effect; use this helper where a plain
 * `execute()` sits inside suspend code (non-stream paths, image/video).
 */
internal suspend fun Call.executeCancellable(): Response {
    val job = coroutineContext[kotlinx.coroutines.Job]
    val handle = job?.invokeOnCompletion { runCatching { cancel() } }
    return try {
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            try {
                val response = execute()
                if (cont.isActive) cont.resume(response) { runCatching { response.close() } } else runCatching { response.close() }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
    } finally {
        handle?.dispose()
    }
}
