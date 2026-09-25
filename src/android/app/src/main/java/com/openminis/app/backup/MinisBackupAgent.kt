package com.openminis.app.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/** Makes WAL-backed chat data self-contained before Android backup/device transfer. */
class MinisBackupAgent : BackupAgent() {
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        checkpointDatabases()
        super.onFullBackup(data)
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        // WAL/SHM are process-local transient state and are deliberately not
        // restored. Remove any stale companions before Room opens the main DB.
        for (name in DATABASES) {
            File(getDatabasePath(name).path + "-wal").delete()
            File(getDatabasePath(name).path + "-shm").delete()
        }
    }

    private fun checkpointDatabases() {
        for (name in DATABASES) {
            val path = getDatabasePath(name)
            if (!path.exists()) continue
            runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    path.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                ).use { db -> db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } }
            }.onFailure { Log.w(TAG, "checkpoint failed for $name", it) }
        }
    }

    companion object {
        private const val TAG = "MinisBackupAgent"
        internal val DATABASES = listOf("minis.db", "skills.db", "mcp.db")
    }
}
