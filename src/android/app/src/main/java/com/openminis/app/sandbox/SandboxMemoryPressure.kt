package com.openminis.app.sandbox

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Event-scoped admission probe, not a permanent polling service. */
internal object SandboxMemoryPressure {
    suspend fun reason(context: Context): String? = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val reserve = maxOf(info.threshold, (info.totalMem / 10).coerceIn(256L shl 20, 1024L shl 20))
        if (info.lowMemory || info.availMem < reserve) {
            return@withContext "Waiting for memory before starting a heavy task (${info.availMem / (1024 * 1024)} MiB available)"
        }
        // A previous shell may have returned after launching a background JVM.
        // Detect its live process before admitting another heavy command. No
        // command arguments or environment values are captured or logged.
        val ownUid = android.os.Process.myUid()
        val busy = File("/proc").listFiles().orEmpty().asSequence()
            .filter { it.name.toIntOrNull() != null }
            .any { dir ->
                runCatching {
                    val comm = File(dir, "comm").readText().trim()
                    comm in setOf("java", "jadx", "jadx-gui", "cc1", "cc1plus", "rustc") &&
                        ProcfsSnapshot.parseStatusUid(File(dir, "status").readText()) == ownUid
                }.getOrDefault(false)
            }
        if (busy) "Waiting for a previous heavy process to exit (including background JVMs)" else null
    }
}
