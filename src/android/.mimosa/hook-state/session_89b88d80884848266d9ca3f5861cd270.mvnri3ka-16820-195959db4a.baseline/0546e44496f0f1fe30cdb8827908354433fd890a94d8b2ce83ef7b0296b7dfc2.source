package com.openminis.app.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host-side storage accounting. Walking a full Ubuntu rootfs on the UI
 * thread (or blocking first paint) can take minutes; this helper skips
 * virtual trees, prefers native `du`, and caches the last rootfs size.
 */
object StorageScanner {
    const val PREFS = "minis_storage_scan"
    const val KEY_ROOTFS_BYTES = "ubuntu_rootfs_bytes"

    val SKIP_DIR_NAMES: Set<String> = setOf(
        "proc", "sys", "dev", "run", "tmp",
        "node_modules", ".git", "__pycache__", ".gradle",
    )

    fun cachedRootfsBytes(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_ROOTFS_BYTES, 0L)

    fun saveRootfsBytes(context: Context, bytes: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ROOTFS_BYTES, bytes)
            .apply()
    }

    fun directorySize(
        dir: File,
        skipNames: Set<String> = SKIP_DIR_NAMES,
        maxFiles: Int = 80_000,
    ): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        var files = 0
        dir.walkTopDown()
            .onEnter { file -> file.name !in skipNames }
            .forEach { file ->
                if (file.isFile) {
                    total += file.length()
                    files++
                    if (files >= maxFiles) return total
                }
            }
        return total
    }

    fun directorySizePreferDu(dir: File, timeoutSec: Long = 60L): Long {
        if (!dir.exists()) return 0L
        val path = dir.absolutePath
        val commands = listOf(
            listOf("du", "-sk", path),
            listOf("/system/bin/du", "-sk", path),
            listOf("/system/bin/toybox", "du", "-sk", path),
        )
        for (cmd in commands) {
            val parsed = runDuKilobytes(cmd, timeoutSec) ?: continue
            return parsed * 1024L
        }
        return directorySize(dir)
    }

    private fun runDuKilobytes(cmd: List<String>, timeoutSec: Long): Long? {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val line = p.inputStream.bufferedReader().use { it.readLine() }
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0 || line.isNullOrBlank()) return null
            line.trim().substringBefore('\t').substringBefore(' ').toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun mediaSizesBySession(mediaDir: File, sessionIds: Set<String>): Map<String, Long> {
        if (!mediaDir.exists() || sessionIds.isEmpty()) return emptyMap()
        val sizes = mutableMapOf<String, Long>()
        mediaDir.walkTopDown()
            .onEnter { file -> file.name !in SKIP_DIR_NAMES }
            .forEach { file ->
                if (file.isFile) {
                    val sid = file.parentFile?.name ?: return@forEach
                    if (sessionIds.contains(sid)) {
                        sizes[sid] = (sizes[sid] ?: 0L) + file.length()
                    }
                }
            }
        return sizes
    }
}
