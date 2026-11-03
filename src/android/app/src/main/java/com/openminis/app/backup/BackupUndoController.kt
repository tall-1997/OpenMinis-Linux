package com.openminis.app.backup

import android.app.Application
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.AppRestart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-backup-undo-bridge] Orchestrates undo points around a restore.
 *
 * Lives outside `BackupViewModel` on purpose: that file sits on the
 * architecture ceiling (1066 lines), so every line here is a line it does not
 * have to grow by. The ViewModel keeps one field and one call site.
 *
 * Two ordering rules are load-bearing and both are covered by tests:
 *  - the WAL is checkpointed BEFORE the snapshot, or the newest committed
 *    transactions stay outside `minis.db` and the undo silently rewinds past
 *    them;
 *  - Room is closed BEFORE the files are replaced, or the live connection keeps
 *    writing into the inode the restore just detached.
 */
internal class BackupUndoController(
    private val app: Application,
    private val db: () -> AppDatabase,
    private val scope: CoroutineScope,
) {

    private val _point = MutableStateFlow<BackupUndoStore.UndoPoint?>(null)

    /** Undo point created for the restore that just finished, if any. */
    val point: StateFlow<BackupUndoStore.UndoPoint?> = _point.asStateFlow()

    private val _points = MutableStateFlow<List<BackupUndoStore.UndoPoint>>(emptyList())

    /** Every undo point still on disk, newest first. */
    val points: StateFlow<List<BackupUndoStore.UndoPoint>> = _points.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun store() = BackupUndoStore(app.filesDir)

    /**
     * Snapshot the selected categories before [com.openminis.app.backup.BackupImporter]
     * writes anything. Blocking IO — call from a background dispatcher.
     *
     * Never throws: a snapshot that cannot be written must not stop the restore
     * the user asked for. It returns null and the caller reports "this restore
     * has no undo point" instead.
     */
    fun capture(categories: Set<BackupCategory>): BackupUndoStore.UndoPoint? {
        return try {
            // Fold the WAL into minis.db so the snapshot holds every committed
            // transaction, not just the ones SQLite happened to move already.
            runCatching {
                db().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            }.onFailure { AppLogger.warning(TAG, "[Undo] wal_checkpoint failed: ${it.message}") }
            val store = store()
            store.prune()
            val result = store.capture(categories)
            if (result.point == null) {
                AppLogger.warning(TAG, "[Undo] snapshot failed: ${result.error}")
            }
            _point.value = result.point
            if (result.point != null) refresh()
            result.point
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Undo] capture threw: ${e.message}")
            _point.value = null
            null
        }
    }

    /**
     * Roll the device back to [point] and relaunch. The relaunch is part of the
     * operation, not a convenience: `SharedPreferences` and every repository
     * cache still hold pre-undo state until the process starts over.
     */
    fun restore(point: BackupUndoStore.UndoPoint) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppDatabase.closeForRestore()
                store().restore(point)
            }
            _busy.value = false
            if (result.ok) {
                AppLogger.info(TAG, "[Undo] restored ${point.id} (${result.bytes} bytes), restarting")
                AppRestart.restart(app)
            } else {
                _error.value = result.error ?: "Undo failed"
                AppLogger.error(TAG, "[Undo] restore ${point.id} failed: ${result.error}")
                // The handle is gone either way; rebuild it before the user
                // touches anything else.
                runCatching { AppDatabase.getInstance(app) }
            }
        }
    }

    fun discard(point: BackupUndoStore.UndoPoint) {
        scope.launch {
            withContext(Dispatchers.IO) {
                store().delete(point.id)
                refreshBlocking()
            }
            if (_point.value?.id == point.id) _point.value = null
        }
    }

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) { refreshBlocking() }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun refreshBlocking() {
        _points.value = store().list()
    }

    /** Test seam: the undo directory, so the bridge and the store agree. */
    internal fun undoDir(): File = File(app.filesDir, BackupUndoStore.DIR_NAME)

    private companion object {
        const val TAG = "BackupUndo"
    }
}
