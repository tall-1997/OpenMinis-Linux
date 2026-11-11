package com.openminis.app.ui.settings.backup

import android.content.Context
import com.openminis.app.backup.BackupExporter
import com.openminis.app.backup.BackupHistory
import com.openminis.app.backup.PhoneBackupFolderStore
import com.openminis.app.backup.remote.RcloneChunkedUpload
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.logging.AppLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [GH#367] Delete one recorded backup package from EVERY place it was written:
 * rclone remotes, phone SAF folders, and the local package directory.
 *
 * ## 为什么单独抽出来
 *
 * 旧实现只遍历 rclone remotes——但 `deliverToRemotes` 同时写 phone 文件夹
 * 目的地（SAF tree URI）。对 phone-only 的备份，历史页仍然显示“连文件删”，
 * 用户点下去记录消失、.minisbak 却还在文件夹里；本地包（所有目的地都失败时
 * 的兜底副本）同样不会被清掉。这是“删了但没删”的语义漏洞，不是 UI 文案
 * 问题，所以按目的地类型分派删除，而不是隐藏按钮。
 *
 * ## 失败语义
 *
 * 每个目的地独立 best-effort：一个服务器/文件夹不可达不能阻止清理其它副本，
 * 失败写 AppLogger（与 [BackupViewModel.removeHistoryRecordWithFiles] 的原
 * 语义一致）。记录本身仍会被删除——半删状态由日志追溯。
 */
internal suspend fun deleteBackupPackageFiles(
    context: Context,
    record: BackupHistory.Record,
) = withContext(Dispatchers.IO) {
    val packageName = record.packageName
    if (packageName.isNullOrEmpty()) {
        AppLogger.warning(TAG, "[Backup] record ${record.id} has no package name; nothing to delete")
        return@withContext
    }

    val phoneStore = PhoneBackupFolderStore(context)
    val rcloneStore = runCatching { RcloneRemoteStore(context).also { it.syncToRclone() } }
        .getOrElse {
            AppLogger.error(TAG, "[Backup] rclone sync failed while deleting '$packageName': ${it.message}")
            null
        }
    val uploader = RcloneChunkedUpload(context)
    // [GH#367] Only drop the local fallback once every reachable destination
    // was actually cleaned. If one delete failed, the local package may be the
    // last surviving copy — deleting it too would turn "remove backup" into
    // irreversible data loss.
    var allDestinationDeletesSucceeded = true

    for (outcome in record.destinations.filter { it.succeeded }) {
        if (outcome.kind == PHONE_KIND) {
            // [GH#367] SAF deletion: find the folder by its persisted tree URI
            // first (names are user-editable and can collide), then by name for
            // records written before `path` was populated.
            val folder = phoneStore.folders.firstOrNull { it.treeUri == outcome.path }
                ?: phoneStore.folders.firstOrNull { it.name == outcome.name }
            if (folder == null) {
                allDestinationDeletesSucceeded = false
                AppLogger.warning(
                    TAG,
                    "[Backup] phone destination '${outcome.name}' no longer configured; " +
                        "cannot delete '$packageName'",
                )
                continue
            }
            runCatching { phoneStore.deletePackage(folder, packageName) }
                .onSuccess { deleted ->
                    if (deleted) {
                        AppLogger.info(TAG, "[Backup] deleted '$packageName' from phone folder '${folder.name}'")
                    } else {
                        allDestinationDeletesSucceeded = false
                        AppLogger.error(
                            TAG,
                            "[Backup] phone folder '${folder.name}' refused deleting '$packageName'",
                        )
                    }
                }
                .onFailure {
                    allDestinationDeletesSucceeded = false
                    AppLogger.error(
                        TAG,
                        "[Backup] deleting '$packageName' from phone folder '${folder.name}' failed: ${it.message}",
                    )
                }
            continue
        }
        val store = rcloneStore
        if (store == null) {
            // The rclone sync failed and this outcome is a remote: the file may
            // still exist there, so keep the local fallback.
            allDestinationDeletesSucceeded = false
            continue
        }
        val remote = store.remotes.firstOrNull { it.name == outcome.name } ?: continue
        runCatching { uploader.deletePackage(remote, packageName) }
            .onFailure {
                allDestinationDeletesSucceeded = false
                AppLogger.error(
                    TAG,
                    "[Backup] deleting '$packageName' from '${outcome.name}' failed: ${it.message}",
                )
            }
    }

    if (!allDestinationDeletesSucceeded) {
        AppLogger.warning(
            TAG,
            "[Backup] keeping the local copy of '$packageName': at least one destination could not be cleaned",
        )
        return@withContext
    }

    // [GH#367] The local copy is a fallback, not an archive: when the record is
    // removed with files, it must go too — otherwise a "deleted" backup keeps
    // occupying the app sandbox forever (and is re-offered nowhere, so the user
    // cannot find it to clean up).
    val local = File(BackupExporter.backupsDirectory(context), packageName)
    if (local.isFile) {
        if (runCatching { local.delete() }.getOrDefault(false)) {
            AppLogger.info(TAG, "[Backup] deleted local package '$packageName'")
        } else {
            AppLogger.error(TAG, "[Backup] failed to delete local package '$packageName'")
        }
    }
}

private const val TAG = "BackupViewModel"

/** [GH#367] `DestinationOutcome.kind` value used for phone SAF folders. */
private const val PHONE_KIND = "phone"
