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
 * ## 失败语义（[GH#367] 第二轮复审）
 *
 * 每个目的地独立 best-effort：一个服务器/文件夹不可达不能阻止清理其它副本，
 * 失败写 AppLogger。但只要有一个目的地“无法确认已经删掉”，本地 fallback
 * 就必须保留——本地包可能是最后一份副本，删掉它会把“删除备份”变成不可逆
 * 数据丢失。因此以下情况一律算失败（而不是“文件本来就不存在”）：
 *  - 远端在配置里已被改名/删除，无法定位 → 无法确认，失败；
 *  - rclone 配置读取/同步失败 → 查询失败，失败；
 *  - phone 目录不可达（`exists()` false / 抛 SecurityException）→ 失败；
 *  - `listFiles()` 抛异常或返回不可信结果 → 查询失败，失败。
 * 只有“目录可达且列表里确实没有这个文件”才按幂等成功处理
 * （[PhoneBackupFolderStore.deletePackage]）。
 *
 * ## 可注入协作方
 *
 * 两组删除操作被抽成 [PhoneFolderDeletionGateway] /
 * [RemotePackageDeletionGateway]，默认参数为 null 时才在函数内部构造真实
 * 的 rclone/SAF 实现。测试可以传 fake，覆盖“远端缺配置”“SAF 删除失败”
 * 等以前只能靠真机复现的分支。
 */
internal suspend fun deleteBackupPackageFiles(
    context: Context,
    record: BackupHistory.Record,
    phoneGateway: PhoneFolderDeletionGateway? = null,
    remoteGateway: RemotePackageDeletionGateway? = null,
) = withContext(Dispatchers.IO) {
    val packageName = record.packageName
    if (packageName.isNullOrEmpty()) {
        AppLogger.warning(TAG, "[Backup] record ${record.id} has no package name; nothing to delete")
        return@withContext
    }

    // [GH#367] Only build the collaborators a record actually needs: a
    // local-only / phone-only record must not pay the rclone sync round-trip.
    val succeededDestinations = record.destinations.filter { it.succeeded }
    val phoneOutcomes = succeededDestinations.filter { it.kind == PHONE_KIND }
    val remoteOutcomes = succeededDestinations.filter { it.kind != PHONE_KIND }
    val phone = phoneGateway
        ?: if (phoneOutcomes.isNotEmpty()) {
            PhoneFolderDeletionGatewayImpl(PhoneBackupFolderStore(context))
        } else {
            null
        }
    val remote = remoteGateway
        ?: if (remoteOutcomes.isNotEmpty()) createRcloneGateway(context) else null

    // [GH#367] Only drop the local fallback once every reachable destination
    // was actually cleaned. If one delete failed (or could not be verified),
    // the local package may be the last surviving copy — deleting it too would
    // turn "remove backup" into irreversible data loss.
    var allDestinationDeletesSucceeded = true

    // ── Phone SAF folders ──────────────────────────────────────────────
    for (outcome in phoneOutcomes) {
        // SAF deletion: find the folder by its persisted tree URI first (names
        // are user-editable and can collide), then by name for records written
        // before `path` was populated.
        val folders = phone?.let { runCatching { it.folders }.getOrNull() }.orEmpty()
        val folder = folders.firstOrNull { it.treeUri == outcome.path }
            ?: folders.firstOrNull { it.name == outcome.name }
        if (folder == null) {
            // Missing folder OR unreadable folder list = cannot verify. Treat
            // both as failure so the local copy survives.
            allDestinationDeletesSucceeded = false
            AppLogger.warning(
                TAG,
                "[Backup] phone destination '${outcome.name}' no longer configured or could not " +
                    "be queried; cannot confirm deletion of '$packageName' — keeping the local copy",
            )
            continue
        }
        val error = runCatching { phone!!.deletePackage(folder, packageName) }
            .getOrElse { it.message ?: "SAF delete failed" }
        if (error == null) {
            AppLogger.info(TAG, "[Backup] deleted '$packageName' from phone folder '${folder.name}'")
        } else {
            allDestinationDeletesSucceeded = false
            AppLogger.error(
                TAG,
                "[Backup] phone folder '${folder.name}' refused deleting '$packageName': $error",
            )
        }
    }

    // ── rclone remotes ─────────────────────────────────────────────────
    for (outcome in remoteOutcomes) {
        val gateway = remote
        if (gateway == null) {
            // The rclone sync failed and this outcome is a remote: the file may
            // still exist there, so keep the local fallback.
            allDestinationDeletesSucceeded = false
            AppLogger.error(
                TAG,
                "[Backup] rclone remotes unavailable while deleting '$packageName' " +
                    "from '${outcome.name}'; keeping the local copy",
            )
            continue
        }
        val configured = runCatching { gateway.remoteNames() }.getOrNull()
        if (configured == null) {
            // Query failure, NOT "the remote has no such file": conservative.
            allDestinationDeletesSucceeded = false
            AppLogger.error(
                TAG,
                "[Backup] could not query rclone remotes while deleting '$packageName' " +
                    "from '${outcome.name}'; keeping the local copy",
            )
            continue
        }
        if (outcome.name !in configured) {
            // [GH#367] This used to `continue` silently and still delete the
            // local fallback. A remote renamed/removed since the backup may
            // still hold the only surviving copy, so an unverifiable remote is
            // a FAILURE, never an implicit success.
            allDestinationDeletesSucceeded = false
            AppLogger.warning(
                TAG,
                "[Backup] remote '${outcome.name}' is no longer configured; cannot confirm " +
                    "deletion of '$packageName' — keeping the local copy",
            )
            continue
        }
        val error = runCatching { gateway.deletePackage(outcome.name, packageName) }
            .getOrElse { it.message ?: "rclone delete failed" }
        if (error == null) {
            AppLogger.info(TAG, "[Backup] deleted '$packageName' from remote '${outcome.name}'")
        } else {
            allDestinationDeletesSucceeded = false
            AppLogger.error(
                TAG,
                "[Backup] deleting '$packageName' from '${outcome.name}' failed: $error",
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
    // cannot find it to clean up). This is also the whole delete path for a
    // local-only record (no destination ever succeeded), exposed from the
    // history detail screen.
    val local = File(BackupExporter.backupsDirectory(context), packageName)
    if (local.isFile) {
        if (runCatching { local.delete() }.getOrDefault(false)) {
            AppLogger.info(TAG, "[Backup] deleted local package '$packageName'")
        } else {
            AppLogger.error(TAG, "[Backup] failed to delete local package '$packageName'")
        }
    }
}

/**
 * [GH#367] Phone-folder half of the deletion path, injectable for tests.
 * `null` from [deletePackage] means "deleted or confirmed absent"; any string
 * is the failure reason and keeps the local fallback.
 */
internal interface PhoneFolderDeletionGateway {
    val folders: List<PhoneBackupFolderStore.Folder>
    fun deletePackage(folder: PhoneBackupFolderStore.Folder, displayName: String): String?
}

/**
 * [GH#367] rclone-remote half of the deletion path, injectable for tests.
 */
internal interface RemotePackageDeletionGateway {
    /** Configured remote names; `null` = the rclone config could not be read. */
    fun remoteNames(): Set<String>?

    /**
     * `null` = deleted. A remote-reported "not found" is deliberately NOT
     * treated as success here: it can mean the remote path is misconfigured,
     * and deleting the local fallback on that guess could destroy the last copy.
     */
    fun deletePackage(remoteName: String, displayName: String): String?
}

/** [GH#367] Production SAF adapter. */
private class PhoneFolderDeletionGatewayImpl(
    private val store: PhoneBackupFolderStore,
) : PhoneFolderDeletionGateway {
    override val folders: List<PhoneBackupFolderStore.Folder> get() = store.folders

    override fun deletePackage(folder: PhoneBackupFolderStore.Folder, displayName: String): String? =
        if (store.deletePackage(folder, displayName)) null
        else "folder '${folder.name}' is unreachable or the SAF provider refused the delete"
}

/** [GH#367] Production rclone adapter. */
private class RcloneRemoteDeletionGateway(
    private val store: RcloneRemoteStore,
    private val uploader: RcloneChunkedUpload,
) : RemotePackageDeletionGateway {
    override fun remoteNames(): Set<String>? =
        runCatching { store.remotes.map { it.name }.toSet() }.getOrNull()

    override fun deletePackage(remoteName: String, displayName: String): String? {
        val remote = store.remotes.firstOrNull { it.name == remoteName }
            ?: return "remote '$remoteName' is no longer configured"
        return runCatching { uploader.deletePackage(remote, displayName) }
            .exceptionOrNull()
            ?.let { it.message ?: it.javaClass.simpleName }
    }
}

/**
 * [GH#367] Build the production rclone gateway, or null when rclone itself
 * cannot be prepared (the caller must then keep the local fallback).
 */
private fun createRcloneGateway(context: Context): RemotePackageDeletionGateway? =
    runCatching {
        RcloneRemoteStore(context).also { it.syncToRclone() }
    }.onFailure {
        AppLogger.error(TAG, "[Backup] rclone sync failed while deleting a package: ${it.message}")
    }.getOrNull()?.let { RcloneRemoteDeletionGateway(it, RcloneChunkedUpload(context)) }

private const val TAG = "BackupViewModel"

/** [GH#367] `DestinationOutcome.kind` value used for phone SAF folders. */
private const val PHONE_KIND = "phone"
