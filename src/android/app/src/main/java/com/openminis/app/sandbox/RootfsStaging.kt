package com.openminis.app.sandbox

import java.io.File
import java.io.IOException

/**
 * A validated staging tree replaces the installed tree. A failed promote puts
 * the previous tree back and never leaves a half-written rootfs marked installed.
 */
object RootfsStaging {
    fun promote(staging: File, installed: File) {
        if (!staging.isDirectory) throw IOException("rootfs staging is missing")
        val parent = installed.parentFile ?: throw IOException("rootfs has no parent")
        val trash = File(parent, installed.name + ".replacing")
        trash.deleteRecursively()
        if (installed.exists() && !installed.renameTo(trash)) {
            throw IOException("cannot move installed rootfs aside")
        }
        if (!staging.renameTo(installed)) {
            if (trash.exists() && !trash.renameTo(installed)) {
                throw IOException("cannot restore rootfs after a failed promote")
            }
            throw IOException("cannot promote rootfs staging")
        }
        trash.deleteRecursively()
    }
}
