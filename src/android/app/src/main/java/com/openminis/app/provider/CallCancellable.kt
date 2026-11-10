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
 * Use this helper where a plain `execute()` sits inside suspend code.
 * Streaming readers retain [cancelOnCancellation] until their body is closed.
 */
internal suspend fun Call.executeCancellable(): Response {
    val job = coroutineContext[kotlinx.coroutines.Job]
    val handle = cancelOnCancellation(job)
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
