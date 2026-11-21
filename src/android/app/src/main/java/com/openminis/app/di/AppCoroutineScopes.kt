package com.openminis.app.di

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Application-owned coroutine lifetimes; cancelled together during teardown/tests. */
class AppCoroutineScopes {
    val application: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val io: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + uncaughtIoHandler,
    )

    fun close() {
        application.cancel()
        io.cancel()
    }

    companion object {
        /**
         * Last-resort handler for `launch`ed blocks on [io] that never install
         * their own `runCatching`. With a SupervisorJob an uncaught child
         * exception does not cancel siblings, but it DOES go to the process's
         * default uncaught handler — i.e. a stray throw inside one of
         * MinisApp.onCreate's many `io.launch { ... }` blocks killed the whole
         * process. Startup work is best-effort by design (each statement is
         * supposed to runCatching); this handler turns a future statement that
         * forgets that from "app dies at boot" into a logged, degraded run.
         *
         * It logs and nothing more: it cannot resume the failed coroutine, and
         * swallowing details would hide the very regression it exists to catch.
         */
        private val uncaughtIoHandler = CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e(
                "AppCoroutineScopes",
                "Uncaught exception on the app IO scope — continuing degraded: " +
                    "${throwable.javaClass.simpleName}: ${throwable.message}",
                throwable,
            )
        }
    }
}
