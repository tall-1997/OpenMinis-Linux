package com.openminis.app.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Propagates the run owner across dispatcher switches for synchronous adapters. */
class ActiveRunContext private constructor(
    val run: ActiveRun,
) : ThreadContextElement<ActiveRun?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveRunContext> {
        private val local = ThreadLocal<ActiveRun?>()
        private val jobs = java.util.concurrent.ConcurrentHashMap<Job, ActiveRun>()

        fun current(): ActiveRun? = local.get()

        @Synchronized
        fun register(job: Job, run: ActiveRun) { jobs[job] = run }

        @Synchronized
        fun unregister(job: Job) { jobs.remove(job) }

        @Synchronized
        fun forJob(job: Job): ActiveRun? = jobs[job]

        fun element(run: ActiveRun): ActiveRunContext = ActiveRunContext(run)
    }

    override fun updateThreadContext(context: CoroutineContext): ActiveRun? {
        val previous = local.get()
        local.set(run)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ActiveRun?) {
        local.set(oldState)
    }
}
