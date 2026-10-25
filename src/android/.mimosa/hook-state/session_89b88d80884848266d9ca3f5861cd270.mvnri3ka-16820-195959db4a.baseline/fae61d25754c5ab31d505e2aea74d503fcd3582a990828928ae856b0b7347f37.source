package com.openminis.app.diagnostics

import android.content.Context
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.tools.AtomicFileWrite
import java.io.File
import java.io.IOException

/**
 * An automatically entered route that did not survive a healthy tick is not
 * entered automatically again. Covers native crash, stall, silent kill, and
 * a watchdog reboot that left no app tombstone. The session id is only a
 * route parameter.
 */
object RouteFuse {
    private const val FILE_NAME = "route-fuse.txt"

    fun noteAutoEnter(context: Context, routeKey: String) {
        note(dir(context), routeKey, System.currentTimeMillis())
    }

    fun markHealthy(context: Context, routeKey: String) {
        markHealthyAt(dir(context), routeKey, System.currentTimeMillis())
    }

    fun blocksAutoEnter(context: Context, routeKey: String): Boolean =
        blocks(dir(context), routeKey)

    fun blocksAnyUnhealthy(context: Context): Boolean = read(dir(context))?.healthy == false

    internal fun note(dir: File, routeKey: String, now: Long) {
        val key = routeKey.trim()
        if (key.isEmpty()) return
        write(dir, "$key\t$now\t0")
    }

    internal fun markHealthyAt(dir: File, routeKey: String, now: Long) {
        val current = read(dir) ?: return
        if (current.route != routeKey) return
        if (now - current.startedAt < ResourceLimits.HEALTHY_TICK_MS) return
        write(dir, "${current.route}\t${current.startedAt}\t1")
    }

    internal fun blocks(dir: File, routeKey: String): Boolean {
        val current = read(dir) ?: return false
        if (current.route != routeKey) return false
        return !current.healthy
    }

    private data class Record(val route: String, val startedAt: Long, val healthy: Boolean)

    private fun read(dir: File): Record? {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return null
        val text = file.inputStream().use { input ->
            val buf = ByteArray(512)
            val n = input.read(buf)
            if (n <= 0) return null
            String(buf, 0, n)
        }
        val parts = text.trim().split('\t')
        if (parts.size < 3) return null
        val started = parts[1].toLongOrNull() ?: return null
        return Record(parts[0], started, parts[2] == "1")
    }

    private fun write(dir: File, line: String) {
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        // Unified atomic write: same tmp+rename dance plus per-path lock,
        // short-write detection and post-write verify. The old rename-failed
        // direct-writeText fallback is covered by the copy-over fallback
        // inside AtomicFileWrite; null (both failed, previous bytes kept)
        // throws like the old tmp.writeText did.
        if (AtomicFileWrite.write(file, line, temp = File(dir, "$FILE_NAME.tmp")) == null) {
            throw IOException("route-fuse write failed for ${file.name}")
        }
    }

    private fun dir(context: Context): File = File(context.filesDir, "logs")
}
