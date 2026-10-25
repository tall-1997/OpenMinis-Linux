package com.openminis.app.ui.chat

import com.openminis.app.service.ActiveRun
import com.openminis.app.service.ActiveRunContext
import com.openminis.app.service.ActiveRunRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch

/** Create/register the per-session run before its coroutine can execute. */
internal fun CoroutineScope.launchActiveRun(
    sessionId: String,
    dispatcher: CoroutineDispatcher,
    ownerSessionIds: Set<String> = setOf(sessionId),
    beforeStart: (Job) -> Unit = {},
    block: suspend CoroutineScope.(ActiveRun) -> Unit,
): Job {
    val run = ActiveRunRegistry.begin(ownerSessionIds, null)
    val job = launch(dispatcher + ActiveRunContext.element(run), start = CoroutineStart.LAZY) {
        try {
            block(run)
        } finally {
            ActiveRunContext.unregister(coroutineContext[kotlinx.coroutines.Job] ?: run.coroutine!!)
            ActiveRunRegistry.finish(run)
        }
    }
    run.attach(job)
    ActiveRunContext.register(job, run)
    beforeStart(job)
    job.start()
    return job
}
