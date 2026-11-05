package com.openminis.app.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Application-owned coroutine lifetimes; cancelled together during teardown/tests. */
class AppCoroutineScopes {
    val application: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun close() {
        application.cancel()
        io.cancel()
    }
}
