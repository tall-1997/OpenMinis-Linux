package com.openminis.app.sandbox

import java.io.File

/** Fail closed if procfs is incomplete. Never treat a returned command as proof of idleness. */
internal object ShellIdleSafety {
    fun canReap(): Boolean = runCatching {
        val own = android.os.Process.myUid()
        val self = android.os.Process.myPid()
        val dirs = File("/proc").listFiles() ?: return false
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid == self) continue
            val status = try { File(dir, "status").readText() } catch (_: Exception) {
                // Other UIDs are hidden on Android. If the directory belongs to
                // our UID but its status is unreadable, do not reclaim.
                val uid = runCatching { android.system.Os.stat(dir.path).st_uid }.getOrNull()
                if (uid == own || uid == null && dir.exists()) return false
                continue
            }
            if (ProcfsSnapshot.parseStatusUid(status) != own) continue
            val comm = File(dir, "comm").readText().trim()
            if (comm !in setOf("bash", "sh", "libproot.so", "proot")) return false
            if (comm == "bash" || comm == "sh") {
                val children = File(dir, "task/$pid/children").readText().trim()
                if (children.isNotEmpty()) return false
            }
        }
        true
    }.getOrDefault(false)
}
