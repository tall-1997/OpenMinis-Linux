package com.openminis.app.sandbox

/**
 * Guest paths for host shared storage. Pure so the shell argv and
 * [PRootKernel.bindMounts] cannot drift: `shell_execute` builds `-b` from
 * the session map, not from the kernel map.
 */
internal object SharedStorageBindPlan {
    const val SDCARD = "/sdcard"
    const val EMULATED = "/storage/emulated/0"
    const val MOUNTS_SDCARD = "/var/minis/mounts/sdcard"

    fun plan(hostPath: String?, userNamedSdcard: Boolean): Map<String, String> {
        if (hostPath.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf(
            SDCARD to hostPath,
            EMULATED to hostPath,
        )
        if (!userNamedSdcard) out[MOUNTS_SDCARD] = hostPath
        return out
    }
}
