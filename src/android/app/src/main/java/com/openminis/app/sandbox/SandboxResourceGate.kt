package com.openminis.app.sandbox

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-session locks so parallel sub-agents / chats cannot deadlock the
 * shared PRoot guest (one gradle daemon, one SDK tree, one apt dpkg lock).
 */
object SandboxResourceGate {
    private val apkLock = Mutex()
    /**
     * Shared with [RootfsManager] so boot-time `minis-mirror` / dpkg-world
     * restore cannot race an agent `apt-get` / `minis-dev-setup` on the guest
     * dpkg lock files.
     */
    val aptMutex = Mutex()
    private val named = ConcurrentHashMap<String, Mutex>()

    /** How long a new apt command waits for [aptMutex]. The install itself is not capped. */
    const val APT_WAIT_MS = 5 * 60 * 1000L

    fun isApkBuild(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("gradlew") ||
            c.contains("gradle ") ||
            Regex("""(^|\s|\./)gradle(\s|$)""").containsMatchIn(c) ||
            c.contains("assembledebug") ||
            c.contains("assemblerelease") ||
            c.contains("bundleRelease".lowercase()) ||
            c.contains("aapt2") ||
            c.contains("bundletool")
    }

    fun isPackageManager(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("apt-get") || c.contains("apt ") ||
            c.contains("dpkg") || c.contains("sdkmanager") ||
            c.contains("minis-dev-setup") || c.contains("minis-android-sdk-setup") ||
            c.contains("minis-mirror")
    }

    /**
     * Wrap a command with resource locks to serialize conflicting operations.
     *
     * APK builds and package managers (apt/dpkg/sdkmanager/minis-dev-setup)
     * are serialized to prevent dpkg lock conflicts. Shell commands that don't
     * touch package state run concurrently.
     *
     * The apt wait is capped at [aptWaitMs]. The command that already holds
     * the lock is not cancelled: `minis-dev-setup-full` is documented at
     * 10–20 minutes, and a timeout around [Mutex.withLock] both aborted that
     * install and dropped the host lock while the guest apt was still running.
     */
    suspend fun <T> withCommandLock(
        command: String,
        aptWaitMs: Long = APT_WAIT_MS,
        block: suspend () -> T,
    ): T {
        return when {
            // Package manager wins when a line matches both (apt-get install gradle).
            isPackageManager(command) -> withAptLock(aptWaitMs, block)
            isApkBuild(command) -> apkLock.withLock { block() }
            else -> block()
        }
    }

    private suspend fun <T> withAptLock(waitMs: Long, block: suspend () -> T): T {
        if (!awaitAptLock(waitMs)) {
            throw RuntimeException(
                "apt/dpkg is busy for 5+ minutes (another install is running). Retry later.",
            )
        }
        try {
            return block()
        } finally {
            aptMutex.unlock()
        }
    }

    /** tryLock so a timeout cannot leave the mutex held or cancel the holder. */
    private suspend fun awaitAptLock(waitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + waitMs.coerceAtLeast(0L)
        while (!aptMutex.tryLock()) {
            if (System.currentTimeMillis() >= deadline) return false
            delay(50)
        }
        return true
    }

    suspend fun <T> withNamedLock(name: String, block: suspend () -> T): T {
        val mutex = named.getOrPut(name) { Mutex() }
        return mutex.withLock { block() }
    }
}
