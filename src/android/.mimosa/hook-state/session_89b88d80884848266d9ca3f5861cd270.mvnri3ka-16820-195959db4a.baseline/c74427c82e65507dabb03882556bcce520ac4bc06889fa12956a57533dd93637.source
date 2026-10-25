package com.openminis.app.network

/**
 * A connectivity flap merges status. It never clears the shared connection pool.
 */
object NetworkFlapPolicy {
    const val DEBOUNCE_MS = 3_000L

    fun shouldEvictPool(): Boolean = false
}
