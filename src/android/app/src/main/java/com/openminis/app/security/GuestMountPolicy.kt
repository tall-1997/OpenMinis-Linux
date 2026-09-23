package com.openminis.app.security

/**
 * Guest `/data` is not the phone's `/data`. `/sdcard` is a placeholder until
 * All Files Access is granted. `rm` of a missing path exits 0, so reject the
 * command instead of letting it succeed silently. Host `su` / `android-su`
 * segments are skipped: those paths are real on the host.
 */
object GuestMountPolicy {
    fun rejection(command: String, sdcardMounted: Boolean): String? {
        for (unit in guestUnits(command)) {
            val hit = unmountedPath(unit, sdcardMounted) ?: continue
            return "拒绝在未挂载的客户机路径上执行命令（$hit）。" +
                "客户机 /data 不是手机 /data；/sdcard 需要「所有文件访问」才会挂载。" +
                "请改用 /var/minis/workspace，或用 android-su 访问真实宿主路径。"
        }
        return null
    }

    internal fun guestUnits(command: String): List<String> {
        val units = mutableListOf<String>()
        for (segment in shellSegments(command)) {
            val argv = tokenizeCommand(segment)
            val head = argv.firstOrNull()?.substringAfterLast('/')
            val cIdx = argv.indexOf("-c")
            if (cIdx >= 0 && cIdx + 1 < argv.size && (head == "su" || head == "android-su")) {
                continue
            }
            if (cIdx >= 0 && cIdx + 1 < argv.size && (head == "sh" || head == "bash")) {
                units += shellSegments(argv[cIdx + 1])
                continue
            }
            units += segment
        }
        return units
    }

    private fun unmountedPath(unit: String, sdcardMounted: Boolean): String? {
        for (token in tokenizeCommand(unit)) {
            val path = token.removeSuffix("/")
            if (path == "/data" || path.startsWith("/data/")) return "/data"
            if (!sdcardMounted && isSdcard(path)) return "/sdcard"
        }
        return null
    }

    private fun isSdcard(path: String): Boolean =
        path == "/sdcard" || path.startsWith("/sdcard/") ||
            path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/")
}
