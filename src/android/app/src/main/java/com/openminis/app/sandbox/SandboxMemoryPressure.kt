package com.openminis.app.sandbox

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Event-scoped admission probe, not a permanent polling service. */
internal object SandboxMemoryPressure {
    private var currentLimit = 1
    private var recoverySince = 0L
    private var lastSample = Long.MIN_VALUE
    private var sampledRevision = -1
    private var cachedLimits = 1 to 1
    @Synchronized
    fun executionLimits(context: Context): Pair<Int, Int> {
        val prefs = com.openminis.app.data.ToolLimitPrefs
        val sampledAt = android.os.SystemClock.elapsedRealtime()
        if (sampledRevision == prefs.revision.value && lastSample != Long.MIN_VALUE && sampledAt - lastSample < 1000L) return cachedLimits
        lastSample = sampledAt
        sampledRevision = prefs.revision.value
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val reserve = maxOf(info.threshold, (info.totalMem / 10).coerceIn(256L shl 20, 1024L shl 20))
        val target = when {
            info.lowMemory || info.availMem < reserve -> 1
            !prefs.autoConcurrency() -> prefs.heavyConcurrency()
            else -> ((info.availMem - reserve) / (512L shl 20)).toInt()
                .coerceIn(1, prefs.heavyConcurrency())
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (target <= currentLimit || !prefs.autoConcurrency()) {
            currentLimit = target
            recoverySince = 0
        } else {
            if (recoverySince == 0L) recoverySince = now
            if (now - recoverySince >= 10_000L) {
                currentLimit++
                recoverySince = now
            }
        }
        cachedLimits = currentLimit to minOf(currentLimit, prefs.heavyConcurrency())
        return cachedLimits
    }

    suspend fun reason(context: Context): String? = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val reserve = maxOf(info.threshold, (info.totalMem / 10).coerceIn(256L shl 20, 1024L shl 20))
        if (info.lowMemory || info.availMem < reserve) {
            return@withContext "Waiting for memory before starting a heavy task (${info.availMem / (1024 * 1024)} MiB available)"
        }
        // Admission owns foreground heavy permits. Do not reject every JVM:
        // that would prevent configured independent heavy tasks from coexisting.
        null
    }
}
