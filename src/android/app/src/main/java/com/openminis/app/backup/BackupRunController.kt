package com.openminis.app.backup

import android.content.Context
import com.openminis.app.R
import com.openminis.app.backup.remote.RcloneChunkedUpload
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.BackupForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-android-backup-run-controller] Owns a backup run for the life of the
 * PROCESS, not the screen.
 *
 * The run used to live on `BackupViewModel.viewModelScope`. That ViewModel is
 * scoped to the Backup nav entry, so leaving the screen, switching tab or
 * popping back to Settings cleared it and cancelled the export mid-package
 * ("can't leave the backup screen while it runs"). A backup is a job the user
 * starts and walks away from; it has to outlive the UI that started it.
 *
 * Hence a process-level singleton with its own [SupervisorJob] scope: the
 * ViewModel is a thin reader of the flows below plus a forwarder of start /
 * stop, and any number of screen instances can come and go while one run
 * proceeds. Staying alive once the whole APP is backgrounded is a separate
 * problem, handled by [com.openminis.app.service.BackupForegroundService],
 * which [start] promotes the process with.
 *
 * One run at a time: [start] is a no-op while [isRunning].
 */
object BackupRunController {

    private const val TAG = "BackupRunController"

    /**
     * Stored sentinel for a user-cancelled run, mapped to a translated string
     * at render time. Persisted in history, so it must not be locale-dependent
     * (same treatment as [BackupHistory.INTERRUPTED_MARKER]).
     */
    const val STOPPED_MARKER = "stopped"

    /** Process scope. SupervisorJob so one failed run cannot poison the next. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class Request(
        val categories: Set<BackupCategory>,
        val maxFileBytes: Long?,
        /** Non-null only when the user chose to encrypt. */
        val passphrase: String?,
    ) {
        val encrypted: Boolean get() = !passphrase.isNullOrEmpty()
    }

    data class RunResult(
        val totalBytes: Long,
        val skippedFiles: Int,
        val destinations: List<BackupHistory.DestinationOutcome>,
        val localCopyRemoved: Boolean = false,
    ) {
        /**
         * Every enabled destination holds a verified copy. Requires at least
         * one destination — "nowhere" is not "everywhere".
         */
        val allDelivered: Boolean
            get() = destinations.isNotEmpty() && destinations.all { it.succeeded }

        /**
         * [T-android-backup-destination-gate] This run had nowhere to go: no
         * destination received it, so its only copy is inside the app sandbox.
         *
         * Not reachable by pressing Start (the screen refuses to start a run
         * with no destination), but reachable by removing the last destination
         * in the other screen mid-run — so it is classified honestly instead of
         * being reported as delivered.
         */
        val localOnly: Boolean get() = destinations.isEmpty()
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _lastResult = MutableStateFlow<RunResult?>(null)
    val lastResult: StateFlow<RunResult?> = _lastResult.asStateFlow()

    /**
     * Bumped on every history write so screens can re-read [BackupHistory].
     * The history file is the source of truth; this is only the "look again"
     * signal, so a screen composed mid-run shows the live record.
     */
    private val _historyVersion = MutableStateFlow(0L)
    val historyVersion: StateFlow<Long> = _historyVersion.asStateFlow()

    /** The in-flight run, held so [stop] can reach it. */
    @Volatile private var runJob: Job? = null

    fun clearError() { _errorText.value = null }

    /**
     * Drop the finished card only once it reports a run that delivered
     * everywhere. A failed destination stays — that is the result a user needs
     * to come back to — and so does a local-only run, whose card is the only
     * statement of where the package is.
     */
    fun clearSettledSuccess() {
        if (_isRunning.value) return
        val r = _lastResult.value ?: return
        if (r.allDelivered) _lastResult.value = null
    }

    /** Cancel the running backup at its next suspension point. */
    fun stop() {
        val job = runJob ?: return
        AppLogger.info(TAG, "[Backup] user requested stop")
        job.cancel()
    }

    /**
     * Start a backup. Returns false if one is already running.
     *
     * The "no destination" refusal ([T-android-backup-destination-gate]) stays
     * in [com.openminis.app.ui.settings.backup.BackupViewModel.startExport],
     * because it is validation of what the user asked for rather than part of
     * running it — and the refuser must be the code that can also tell them
     * where to fix it.
     */
    fun start(context: Context, request: Request): Boolean {
        if (_isRunning.value) return false
        val app = context.applicationContext
        val cats = request.categories
        _isRunning.value = true
        _errorText.value = null
        _lastResult.value = null
        _statusText.value = "Starting…"

        val history = BackupHistory.get(app)
        // Open the record BEFORE any work, so a run killed mid-flight still
        // leaves evidence. BackupHistory reconciles a record left RUNNING into
        // FAILED on next launch.
        val log = mutableListOf<BackupHistory.LogEntry>()
        var record = BackupHistory.Record(
            backupId = "",
            startedAt = System.currentTimeMillis(),
            status = BackupHistory.Status.RUNNING,
            categories = cats.map { it.key }.sorted(),
            encrypted = request.encrypted,
        )
        publishHistory(history, record)

        // [T-android-backup-fgs] Promote the process for the life of the run.
        // Started HERE rather than by the screen: this is the single place a
        // run begins, so a scheduled/agent backup is protected the same way as
        // one started from the button. A refused start never stops the backup.
        BackupForegroundService.start(app)

        fun note(line: String, problem: Boolean = false) {
            // [T-android-backup-log-cap] capped append
            BackupHistory.appendLog(log, line, problem)
        }
        fun progress(line: String) {
            _statusText.value = line
            note(line)
            // The running history row's subtitle IS the latest log line, so
            // progress lives with the run rather than inside the button.
            publishHistory(history, record.copy(log = log.toList()))
        }

        runJob = scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    BackupExporter(app, AppDatabase.getInstance(app)).export(
                        BackupExporter.Options(
                            categories = cats,
                            maxFileBytes = request.maxFileBytes,
                            // Unconditional ([T-backup-credentials-always]): a
                            // backup exists to reconstitute a device. Only the
                            // passphrase tracks encryption.
                            includeCredentials = true,
                            passphrase = request.passphrase,
                        ),
                    ) { line -> progress(line) }
                }

                // Deliver to every enabled destination. A failure never
                // discards the local package.
                val outcomes = withContext(Dispatchers.IO) {
                    // [T-android-backup-stop-reaches-delivery] The delivery
                    // below is BLOCKING code, so job.cancel() (Stop) could not
                    // reach it: it waited out every upload before the run saw
                    // the cancellation. The job's state is passed in as a flag
                    // that the upload and the folder copy poll.
                    val job = coroutineContext[Job]
                    deliverToDestinations(
                        app, summary.packageFile, summary.backupId,
                        isCancelled = { job?.isActive == false },
                    ) { progress(it) }
                }
                outcomes.forEach {
                    note(
                        if (it.succeeded) "Delivered to ${it.name}"
                        else "Delivery to ${it.name} failed: ${it.detail}",
                        problem = !it.succeeded,
                    )
                }

                // The local package is a FALLBACK once every destination holds
                // a verified copy ([T-android-backup-local-cleanup]). With no
                // destinations, or any failure, it stays — it is then the
                // backup.
                var localRemoved = false
                if (outcomes.isNotEmpty() && outcomes.all { it.succeeded }) {
                    val freed = summary.packageFile.length()
                    if (withContext(Dispatchers.IO) { summary.packageFile.delete() }) {
                        localRemoved = true
                        // Say so: doing it silently is the wrong way, since the
                        // user cannot tell "cleaned up" from "the backup is
                        // gone". The line lands in the record too.
                        val msg = app.getString(R.string.backup_local_removed, humanBytes(freed))
                        AppLogger.info(TAG, "[Backup] $msg")
                        note(msg)
                    }
                } else if (outcomes.isNotEmpty()) {
                    note(app.getString(R.string.backup_local_kept))
                } else {
                    // Say where it is, in the record too: "did that backup go
                    // anywhere?" is asked long after the card is gone.
                    note(app.getString(R.string.backup_result_local_pending))
                }

                _lastResult.value = RunResult(
                    totalBytes = summary.totalBytes,
                    skippedFiles = summary.skippedFiles,
                    destinations = outcomes,
                    localCopyRemoved = localRemoved,
                )
                val failed = outcomes.filterNot { it.succeeded }
                if (failed.isNotEmpty()) {
                    _errorText.value = "Saved locally, but delivery failed for: " +
                        failed.joinToString("; ") { "${it.name}: ${it.detail ?: "failed"}" }
                }
                record = record.copy(
                    backupId = summary.backupId,
                    finishedAt = System.currentTimeMillis(),
                    // A run that could not reach every destination, or dropped
                    // files at the cap, is neither clean success nor failure.
                    status = if (failed.isEmpty() && summary.skippedFiles == 0) {
                        BackupHistory.Status.SUCCEEDED
                    } else {
                        BackupHistory.Status.COMPLETED_WITH_ISSUES
                    },
                    totalBytes = summary.totalBytes,
                    skippedFiles = summary.skippedFiles,
                    skippedEntries = summary.skippedPaths.map {
                        BackupHistory.SkippedEntry(it.path, it.size)
                    },
                    packageName = summary.packageFile.name,
                    destinations = outcomes,
                    log = log.toList(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped by the user: a real outcome, not a vanished run —
                // left RUNNING it would render as a permanent spinner.
                AppLogger.info(TAG, "[Backup] export cancelled by user")
                note(STOPPED_MARKER, problem = true)
                record = record.copy(
                    finishedAt = System.currentTimeMillis(),
                    status = BackupHistory.Status.FAILED,
                    errorMessage = STOPPED_MARKER,
                    log = log.toList(),
                )
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "[Backup] export failed: ${e.message}")
                _errorText.value = e.message ?: "Backup failed."
                note(e.message ?: "Backup failed.", problem = true)
                record = record.copy(
                    finishedAt = System.currentTimeMillis(),
                    status = BackupHistory.Status.FAILED,
                    errorMessage = e.message,
                    log = log.toList(),
                )
            } finally {
                runJob = null
                _statusText.value = null
                publishHistory(history, record)
                _isRunning.value = false
            }
        }
        return true
    }

    /** One history write, plus the "look again" signal for live screens. */
    private fun publishHistory(history: BackupHistory, record: BackupHistory.Record) {
        history.upsert(record)
        _historyVersion.value = _historyVersion.value + 1
    }

    /**
     * Send [packageFile] to every enabled destination: rclone remotes first,
     * then folders on this phone. Failures are returned as outcomes rather than
     * thrown — delivering to three of four places is a result worth recording,
     * and the local package is kept for the one that failed.
     *
     * [isCancelled] is polled between destinations and inside each transfer, so
     * Stop takes effect during a slow upload instead of after the last one.
     */
    private suspend fun deliverToDestinations(
        app: Context,
        packageFile: File,
        backupId: String,
        isCancelled: () -> Boolean,
        onProgress: (String) -> Unit,
    ): List<BackupHistory.DestinationOutcome> {
        val outcomes = mutableListOf<BackupHistory.DestinationOutcome>()
        val uploader = RcloneChunkedUpload(app)
        // Read the destinations HERE rather than trusting the list the screen
        // validated against at Start: a toggle may have been flipped while the
        // run was busy packaging.
        val enabled = RcloneRemoteStore(app).enabledRemotes

        fun checkStop() {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("stopped")
        }

        for (remote in enabled) {
            checkStop()
            try {
                onProgress("Sending to ${remote.name}…")
                uploader.upload(packageFile, remote, backupId, isCancelled) { p ->
                    val pct = if (p.totalBytes > 0) (p.bytesSent * 100 / p.totalBytes) else 0
                    onProgress("Sending to ${remote.name}… $pct%")
                }
                outcomes.add(
                    BackupHistory.DestinationOutcome(
                        remote.name, succeeded = true,
                        kind = remote.backend, path = remote.path,
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The upload reports its own cancellation as a plain failure;
                // if Stop is why it failed, the run is stopped, not "delivery
                // failed" — do not let a stopped run accumulate outcomes.
                checkStop()
                AppLogger.error(TAG, "[Backup] delivery to ${remote.name} failed: ${e.message}")
                outcomes.add(
                    BackupHistory.DestinationOutcome(
                        remote.name, succeeded = false, detail = e.message ?: "failed",
                        kind = remote.backend, path = remote.path,
                    ),
                )
            }
        }

        val phoneStore = PhoneBackupFolderStore(app)
        for (folder in phoneStore.enabledFolders) {
            checkStop()
            try {
                onProgress("Copying to ${folder.name}…")
                withContext(Dispatchers.IO) {
                    phoneStore.copyPackage(folder, packageFile, packageFile.name, isCancelled)
                }
                outcomes.add(
                    BackupHistory.DestinationOutcome(
                        folder.name, succeeded = true,
                        kind = "phone", path = folder.treeUri,
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                checkStop()
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
}
