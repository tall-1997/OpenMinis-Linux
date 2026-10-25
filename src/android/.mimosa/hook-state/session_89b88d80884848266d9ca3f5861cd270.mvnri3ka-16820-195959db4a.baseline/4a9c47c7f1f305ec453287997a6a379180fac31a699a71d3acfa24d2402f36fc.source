package com.openminis.app.data.repository

import java.io.File

/**
 * Copies daily diary files (`YYYY-MM-DD.md`) from the old global memory dir
 * into a session's own memory dir. SOUL.md / LEARNED.md / GLOBAL.md / personas
 * stay global and are never copied.
 */
object MemoryDiaryMigrator {
    private val DAILY = Regex("""^\d{4}-\d{2}-\d{2}\.md$""")

    fun isDailyDiary(name: String): Boolean = DAILY.matches(name)

    /**
     * Copy missing daily logs from [globalMemory] into [sessionMemory].
     * Existing destination files are left alone.
     * @return number of files copied.
     */
    fun copyInto(sessionMemory: File, globalMemory: File): Int {
        if (!globalMemory.isDirectory) return 0
        sessionMemory.mkdirs()
        var copied = 0
        val files = globalMemory.listFiles() ?: return 0
        for (src in files) {
            if (!src.isFile || !isDailyDiary(src.name)) continue
            val dst = File(sessionMemory, src.name)
            if (dst.exists()) continue
            src.copyTo(dst)
            copied++
        }
        return copied
    }
}
