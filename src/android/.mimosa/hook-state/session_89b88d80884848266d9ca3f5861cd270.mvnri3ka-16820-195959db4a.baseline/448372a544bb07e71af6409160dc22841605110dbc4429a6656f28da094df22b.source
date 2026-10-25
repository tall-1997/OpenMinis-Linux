package com.openminis.app.sandbox.kernel.remediation

import java.io.File

/** File flag. The next frame and the next cold start can read it without the main looper. */
object DegradeFlags {
    private const val NAME = "render-degrade.flag"

    fun trip(filesDir: File, reason: String) {
        val dir = File(filesDir, "sandbox")
        if (!dir.mkdirs() && !dir.isDirectory) return
        runCatching {
            File(dir, NAME).writeText("${System.currentTimeMillis()} $reason\n")
        }
    }

    fun isTripped(filesDir: File): Boolean = File(filesDir, "sandbox/$NAME").isFile

    fun clear(filesDir: File) {
        File(filesDir, "sandbox/$NAME").delete()
    }
}
