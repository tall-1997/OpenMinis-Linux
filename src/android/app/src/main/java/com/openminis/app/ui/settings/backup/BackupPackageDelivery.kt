package com.openminis.app.ui.settings.backup

import android.content.Context
import com.openminis.app.backup.BackupHistory
import com.openminis.app.backup.PhoneBackupFolderStore
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * Package delivery for an export run: upload the produced `.minisbak` to
 * every enabled destination — rclone remotes AND phone SAF folders — and
 * report one [BackupHistory.DestinationOutcome] per destination.
 *
 * Extracted verbatim from BackupViewModel.kt when that file hit its
 * architecture line ceiling. The function mutates no ViewModel state, so it
 * is a file-level `internal` function whose collaborators arrive as plain
 * parameters (the same shape as [deleteBackupPackageFiles]); the call site
 * in [BackupViewModel.startExport] passes `getApplication()` and the
 * ViewModel-owned [PhoneBackupFolderStore], so behaviour is unchanged. Log
 * lines keep the "BackupViewModel" tag via the private [TAG] below.
 */

/**
 * Upload the produced package to every enabled rclone remote as a single
 * verified `.minisbak` (see RcloneChunkedUpload, which no longer chunks).
 * Returns a list of "name: reason"
 * strings for remotes that failed; empty when all succeeded (or none are
 * configured). Never throws — a failed remote must not lose the local copy.
 */
internal fun deliverToRemotes(
    context: Context,
    phoneStore: PhoneBackupFolderStore,
    packageFile: File,
    backupId: String,
    onProgress: (String) -> Unit,
): List<BackupHistory.DestinationOutcome> {
    val store = com.openminis.app.backup.remote.RcloneRemoteStore(context)
    val enabled = store.enabledRemotes
    val phone = phoneStore.enabledFolders
    if (enabled.isEmpty() && phone.isEmpty()) return emptyList()
    if (enabled.isNotEmpty()) store.syncToRclone()
    val uploader = com.openminis.app.backup.remote.RcloneChunkedUpload(context)
    // [T-android-backup-history] Report EVERY destination, not just the
    // failures. "Which servers did last night's backup actually reach?"
    // was unanswerable while only errors were returned.
    val outcomes = mutableListOf<BackupHistory.DestinationOutcome>()
    for (remote in enabled) {
        try {
            onProgress("Sending to ${remote.name}…")
            uploader.upload(packageFile, remote, backupId) { p ->
                val pct = if (p.totalBytes > 0) (p.bytesSent * 100 / p.totalBytes) else 0
                onProgress("Sending to ${remote.name}… $pct%")
            }
            outcomes.add(
                BackupHistory.DestinationOutcome(
                    remote.name, succeeded = true,
                    kind = remote.backend, path = remote.path,
                ),
            )
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Backup] delivery to ${remote.name} failed: ${e.message}")
            outcomes.add(
                BackupHistory.DestinationOutcome(
                    remote.name, succeeded = false, detail = e.message ?: "failed",
                    kind = remote.backend, path = remote.path,
                ),
            )
        }
    }
    for (folder in phone) {
        try {
            onProgress("Copying to ${folder.name}…")
            phoneStore.copyPackage(folder, packageFile, packageFile.name)
            outcomes.add(
                BackupHistory.DestinationOutcome(
                    folder.name, succeeded = true,
                    kind = "phone", path = folder.treeUri,
                ),
            )
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Backup] phone copy to ${folder.name} failed: ${e.message}")
            outcomes.add(
                BackupHistory.DestinationOutcome(
                    folder.name, succeeded = false, detail = e.message ?: "failed",
                    kind = "phone", path = folder.treeUri,
                ),
            )
        }
    }
    return outcomes
}

private const val TAG = "BackupViewModel"
