package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Optional context accounting cannot make opening an otherwise readable session fail. */
internal suspend fun <T : Any> runSessionContextEstimate(
    estimate: suspend () -> T,
    publish: (T) -> Unit,
    reportFailure: (Exception) -> Unit,
) {
    try {
        currentCoroutineContext().ensureActive()
        val result = estimate()
        currentCoroutineContext().ensureActive()
        publish(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        reportFailure(failure)
    }
}
