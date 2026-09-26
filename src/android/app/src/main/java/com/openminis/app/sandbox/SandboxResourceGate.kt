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
    private val executionGate = AdaptiveExecutionGate()

    enum class ResourceClass { AUTO, HEAVY }

    fun isHeavy(command: String): Boolean {
        // Resource cleanup must remain available while a daemon holds memory.
        val trimmed = command.trim()
        if (Regex("""^(?:kill|pkill|killall)(?:\s|$)""").containsMatchIn(trimmed) ||
            Regex("""^(?:\S*/)?gradle(?:w)?\s+--stop\s*$""").matches(trimmed)) return false
        return isApkBuild(command) || isPackageManager(command) || Regex("""(^|[\s/;&|()])(?:jadx(?:-gui)?|apktool|java|javac|kotlinc|ninja|make|cmake|gcc|g\+\+|clang(?:\+\+)?|rustc|cargo|ffmpeg)(?=$|[\s;&|()])""")
            .containsMatchIn(command.lowercase())
    }

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
     * Builds, package managers and other heavy tools share a global budget.
     * Package state also retains its existing dpkg lock. Lightweight commands
     * remain concurrent; opaque scripts can explicitly request HEAVY.
     *
     * The apt wait is capped at [aptWaitMs]. The command that already holds
     * the lock is not cancelled: `minis-dev-setup-full` is documented at
     * 10–20 minutes, and a timeout around [Mutex.withLock] both aborted that
     * install and dropped the host lock while the guest apt was still running.
     */
    suspend fun <T> withCommandLock(
        command: String,
        aptWaitMs: Long = com.openminis.app.data.ToolLimitPrefs.queueTimeoutSec() * 1000L,
        resourceClass: ResourceClass = ResourceClass.AUTO,
        pressure: suspend () -> String? = { null },
        onWaiting: (String) -> Unit = {},
        limits: () -> Pair<Int, Int> = {
            val total = com.openminis.app.data.ToolLimitPrefs.commandConcurrency()
            total to minOf(total, com.openminis.app.data.ToolLimitPrefs.heavyConcurrency())
        },
        block: suspend () -> T,
    ): T {
        val heavy = resourceClass == ResourceClass.HEAVY || isHeavy(command)
        // Light commands (including cleanup) do not consume heavy permits.
        // Their per-lane serialization remains in ExecutionCoordinator.
        if (!heavy) return block()
        val queuedAt = System.nanoTime()
        suspend fun admitted(): T = executionGate.run(
            heavy, if (aptWaitMs <= 0) 0 else
                (aptWaitMs - (System.nanoTime() - queuedAt) / 1_000_000).also {
                    check(it > 0) { "Execution queue timed out; command was not started" }
                },
            limits = { limits().let { AdaptiveExecutionGate.Limits(it.first, it.second) } },
            pressure = { if (heavy) pressure() else null },
            onWaiting = onWaiting,
            block = block,
        )
        return if (isPackageManager(command)) withAptLock(aptWaitMs, onWaiting) { admitted() }
        else admitted()
    }

    private suspend fun <T> withAptLock(waitMs: Long, onWaiting: (String) -> Unit, block: suspend () -> T): T {
        if (aptMutex.isLocked) onWaiting("WAITING_RESOURCE: package manager is busy; command not started")
        if (!awaitAptLock(waitMs)) {
            throw RuntimeException(
                "Package manager queue timed out; command was not started.",
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
            if (waitMs > 0 && System.currentTimeMillis() >= deadline) return false
            delay(50)
        }
        return true
    }

    suspend fun <T> withNamedLock(name: String, block: suspend () -> T): T {
        val mutex = named.getOrPut(name) { Mutex() }
        return mutex.withLock { block() }
    }
}
