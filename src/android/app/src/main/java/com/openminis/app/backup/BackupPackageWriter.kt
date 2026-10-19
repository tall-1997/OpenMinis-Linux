package com.openminis.app.backup

import java.io.File

/**
 * [T-android-backup-package-name-parity] Turns the finished staging tree into a
 * `.minisbak` package named the way iOS names it, and finalises it atomically.
 *
 * Split out of [BackupExporter] only because that file's line ceiling never
 * rises; the naming rule itself lives in [BackupPackageName], which both the
 * exporter and this writer go through.
 */
internal object BackupPackageWriter {

    /**
     * Package [staging] as [dir]/<iOS-name>.minisbak and return the file.
     *
     * Writes to a `.partial` sibling and renames on success. A kill or a full
     * disk mid-write would otherwise leave a TRUNCATED file carrying a
     * perfectly valid `.minisbak` name, which then shows up in the restore
     * picker with a plausible size and date — discovered only when the restore
     * fails, plausibly on a new device after wiping the old one.
     */
    fun write(
        dir: File,
        staging: File,
        backupId: String,
        snapshotAtMillis: Long,
        deviceName: String,
        encrypted: Boolean,
    ): File {
        // [T-android-backup-package-name-parity] iOS's naming scheme; the id
        // part is millisecond-sortable plus a hash of the backup id, so two
        // exports in the same minute no longer collide and packages from
        // several devices in one folder stay told apart.
        val name = BackupPackageName.packageFileName(backupId, snapshotAtMillis, deviceName, encrypted)
        val out = File(dir, name)
        out.parentFile?.mkdirs()
        out.delete()

        val partial = File(out.parentFile, ".${out.name}.partial")
        partial.delete()
        try {
            BackupZip.archive(staging, partial)
            if (!partial.renameTo(out)) {
                throw BackupException("Could not finalise the backup package.")
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        return out
    }
}
