package com.openminis.app.data

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Mirror-accelerated, resumable, background-tolerant APK downloader for app
 * updates.
 *
 * Design notes:
 *
 *  - **Mirror fallback.** GitHub release asset URLs are re-hosted onto a set
 *    of public mirrors; we probe every candidate's time-to-first-byte in
 *    parallel and stream the full body from whichever answers first. This
 *    keeps downloads working for users where github.com is throttled or
 *    geo-blocked. Mirrors are best-effort public relays — if one dies the
 *    others still race, and the origin URL is always in the candidate set.
 *
 *  - **Background tolerant.** The download runs on a process-wide
 *    [CoroutineScope] (SupervisorJob + IO), NOT the composable's scope, so
 *    leaving the About screen (or the whole settings) does not cancel it.
 *    UI re-attaches by collecting [state]. A partial download survives a
 *    process kill as a `.part` file; the next attempt resumes via HTTP Range.
 *
 *  - **Resume.** We stream into `<name>.part` and rename to the final name on
 *    completion. A `.part` from an interrupted run is resumed from its length
 *    with `Range: bytes=N-`. If the server ignores Range (200 instead of
 *    206) we restart cleanly; a 416 means we already have the whole file.
 */
object UpdateDownloadManager {

    private const val TAG = "UpdateDownloadManager"
    private const val PROBE_TIMEOUT_MS = 6_000L
    private const val PROGRESS_NOTIFY_MIN_MS = 400L

    // Mirrors that re-host a GitHub release/download URL as a path suffix.
    // Order is irrelevant — they race. The origin URL is always added too.
    private val MIRROR_PREFIXES = listOf(
        "https://ghproxy.com/",
        "https://gh-proxy.com/",
        "https://mirror.ghproxy.com/",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // no overall cap; big files stream a while
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Short-timeout client just for mirror probing.
    private val probeClient = client.newBuilder()
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Observable download state; the UI collects this to render progress. */
    data class DownloadState(
        val running: Boolean = false,
        /** 0..1 when total size known, else -1 (indeterminate). */
        val progress: Float = 0f,
        val totalBytes: Long = -1L,
        val downloadedBytes: Long = 0L,
        /** The mirror/origin currently winning the race, for the status line. */
        val activeNode: String? = null,
        /** True while we are still racing mirrors (download not yet started). */
        val probing: Boolean = false,
        val doneFile: File? = null,
        val error: String? = null,
        /** True once the OS installer intent has been fired for doneFile. */
        val installLaunched: Boolean = false,
    )

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    // Process-wide scope so the download outlives any single screen.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Start (or restart) a download of [apkUrl] for [versionName]. If one is
     * already running this is a no-op; the caller simply keeps collecting
     * [state].
     *
     * If a COMPLETE APK for this exact version is already staged on disk it is
     * reused instead of re-fetched. This matters because a finished download
     * leaves no `.part` behind (it is renamed to the final name), so
     * [downloadWithResume] would otherwise restart from byte 0 — that is how
     * "cancel the system installer, tap download again" turned into a second
     * 98 MB transfer for a file we already had.
     *
     * [expectedSizeBytes] is the release asset size when known; a staged file
     * whose length disagrees is treated as a truncated leftover and refetched.
     */
    fun start(
        context: Context,
        apkUrl: String,
        versionName: String,
        expectedSizeBytes: Long = -1L,
    ) {
        if (job?.isActive == true) return
        val appCtx = context.applicationContext
        val outDir = File(appCtx.filesDir, "updates").apply { mkdirs() }
        val safeName = "minis-" + versionName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
        val finalFile = File(outDir, safeName)
        val partFile = File(outDir, "$safeName.part")

        _state.value = DownloadState(running = true, probing = true, progress = 0f)
        job = scope.launch {
            try {
                if (reusableCompleteApk(finalFile, expectedSizeBytes)) {
                    AppLogger.info(
                        TAG,
                        "reusing complete APK ${finalFile.name} size=${finalFile.length()} — skipping download",
                    )
                    publishDownloaded(appCtx, finalFile, versionName)
                    return@launch
                }
                val node = pickFastestNode(apkUrl)
                _state.value = _state.value.copy(probing = false, activeNode = node)
                downloadWithResume(node, partFile, finalFile, expectedSizeBytes)
                publishDownloaded(appCtx, finalFile, versionName)
            } catch (e: Exception) {
                AppLogger.error(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = _state.value.copy(
                    running = false,
                    probing = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * True when [f] is a complete staged APK for the version being requested.
     * [expectedSize] <= 0 means the release asset size was unknown, in which
     * case any non-empty file is accepted.
     */
    private fun reusableCompleteApk(f: File, expectedSize: Long): Boolean {
        if (!f.exists() || f.length() <= 0L) return false
        if (expectedSize > 0L && f.length() != expectedSize) {
            AppLogger.warning(
                TAG,
                "staged APK ${f.name} len=${f.length()} != expected=$expectedSize; refetching",
            )
            return false
        }
        return true
    }

    /** Persist the pending record and publish the completed state. */
    private fun publishDownloaded(appCtx: Context, file: File, versionName: String) {
        val sha = runCatching { PendingUpdateStore.sha256(file) }.getOrNull()
        PendingUpdateStore.setPending(
            appCtx,
            PendingUpdateStore.PendingUpdate(
                targetVersionName = versionName,
                apkPath = file.absolutePath,
                apkSize = file.length(),
                sha256 = sha,
                downloadedAtMs = System.currentTimeMillis(),
            ),
        )
        // The download request is satisfied — drop the "waiting on permission"
        // intent so a later resume doesn't try to start it a second time.
        PendingUpdateStore.clearPendingIntent(appCtx)
        _state.value = _state.value.copy(running = false, probing = false, progress = 1f, doneFile = file)
        AppLogger.info(TAG, "download complete ${file.absolutePath} size=${file.length()}")
    }

    /** Cancel the in-flight download (keeps the .part file for later resume). */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, probing = false)
    }

    /** Mark that the system installer was launched (so cleanup can treat it as installed). */
    fun markInstallLaunched() {
        _state.value = _state.value.copy(installLaunched = true)
    }

    /**
     * Race all mirror candidates (+ origin) for the fastest healthy response.
     * Each candidate is probed with a 1-byte Range GET; the first to answer
     * 200/206 within [PROBE_TIMEOUT_MS] wins and the losers are cancelled. If
     * none answer in time we fall back to the origin URL and let the real
     * download surface the true error.
     */
    private suspend fun pickFastestNode(originUrl: String): String = coroutineScope {
        val candidates = (MIRROR_PREFIXES.map { it + originUrl } + originUrl).distinct()
        val probes = candidates.map { url -> async { if (probeTtfb(url)) url else null } }
        // First-success race: the first probe to complete with a non-null URL
        // wins; failed probes (null) are discarded and we keep waiting on the
        // rest until one answers or the list is exhausted.
        var alive = probes.toMutableList()
        var winner: String? = null
        while (winner == null && alive.isNotEmpty()) {
            val res = select<Pair<String?, kotlinx.coroutines.Deferred<String?>>> {
                alive.forEach { d ->
                    d.onAwait { it to d }
                }
            }
            alive.remove(res.second)
            if (res.first != null) winner = res.first
        }
        alive.forEach { it.cancel() }
        winner ?: originUrl
    }

    /** Probe a URL: issue a 1-byte Range GET, true when it responds OK/206. */
    private suspend fun probeTtfb(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                resp.code == 200 || resp.code == 206
            }
        }.getOrDefault(false)
    }

    /**
     * Stream [url] into [partFile], resuming from its existing length via
     * Range, then atomically rename to [finalFile]. Progress reported to
     * [state] throttled to ~[PROGRESS_NOTIFY_MIN_MS].
     */
    private fun downloadWithResume(url: String, partFile: File, finalFile: File, expectedSizeBytes: Long = -1L) {
        val existing = if (partFile.exists()) partFile.length() else 0L
        val reqBuilder = Request.Builder().url(url)
        if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")
        val req = reqBuilder.build()

        client.newCall(req).execute().use { resp ->
            var resumeFrom = existing
            when (resp.code) {
                200 -> {
                    // Server ignored our Range — restart cleanly.
                    if (existing > 0) {
                        AppLogger.warning(TAG, "server ignored Range; restarting from 0")
                        resumeFrom = 0L
                        partFile.delete()
                    }
                }
                206 -> { /* honored, resume from existing */ }
                416 -> {
                    // Range not satisfiable. That means the part is complete only
                    // when its length matches the published asset. A shorter part
                    // (mirror rejected Range) must not be installed as the APK.
                    val sizeOk = expectedSizeBytes <= 0L || existing == expectedSizeBytes
                    if (existing > 0 && sizeOk) {
                        AppLogger.info(TAG, "416: treating existing .part as complete")
                        promotePart(partFile, finalFile)
                        return
                    }
                    if (existing > 0) {
                        AppLogger.warning(
                            TAG,
                            "416 but part $existing != expected $expectedSizeBytes; discarding part",
                        )
                        partFile.delete()
                    }
                    throw IllegalStateException("HTTP 416 with incomplete local file")
                }
                else -> throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("empty body")
            val contentLen = body.contentLength().takeIf { it > 0 } ?: -1L
            val total = if (contentLen > 0) resumeFrom + contentLen else -1L

            _state.value = _state.value.copy(totalBytes = total, downloadedBytes = resumeFrom)

            body.byteStream().use { input ->
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(resumeFrom)
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = resumeFrom
                    var lastNotify = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        raf.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= PROGRESS_NOTIFY_MIN_MS) {
                            lastNotify = now
                            val pct = if (total > 0) downloaded.toFloat() / total.toFloat() else -1f
                            _state.value = _state.value.copy(
                                progress = pct,
                                downloadedBytes = downloaded,
                            )
                        }
                    }
                    raf.fd.sync()
                }
            }
            _state.value = _state.value.copy(progress = 1f, downloadedBytes = total)
        }

        promotePart(partFile, finalFile)
    }

    private fun promotePart(partFile: File, finalFile: File) {
        if (finalFile.exists() && !finalFile.delete()) {
            throw IllegalStateException("cannot replace ${finalFile.name}")
        }
        if (!partFile.renameTo(finalFile)) {
            // renameTo can fail across filesystems; copy+delete as fallback.
            partFile.copyTo(finalFile, overwrite = true)
            if (!partFile.delete()) {
                AppLogger.warning(TAG, "installed ${finalFile.name} but leftover part remains")
            }
        }
        if (!finalFile.isFile || finalFile.length() <= 0L) {
            throw IllegalStateException("download did not produce ${finalFile.name}")
        }
    }

    /**
     * Housekeeping for the private `updates/` dir, run every time the
     * check-update section is composed. Deletes:
     *  - APKs for an OLDER version than the running build (stale leftovers),
     *  - the APK for the CURRENT running version (already installed),
     *  - orphaned `.part` files whose download is not currently running.
     * Keeps the pending/next-version APK so resume + install still work.
     */
    fun pruneUpdateDir(context: Context) {
        scope.launch {
            runCatching {
                val dir = File(context.filesDir, "updates")
                if (!dir.exists()) return@runCatching
                // Normalize the same way UpdateChecker.resumableInstall does —
                // comparing a normalized filename version against a raw
                // "1.36.9-linux" local build made the two code paths disagree
                // about whether a staged APK was still needed.
                val current = UpdateVersionLogic.normalizeTag(BuildConfig.VERSION_NAME)
                val running = job?.isActive == true
                // Never delete the APK the pending record points at, whatever
                // the version arithmetic says: it is the one the user already
                // downloaded and is about to install.
                val protectedPath = PendingUpdateStore.getPending(context)?.apkPath
                dir.listFiles()?.forEach { f ->
                    val name = f.name
                    when {
                        !name.endsWith(".apk") && !name.endsWith(".apk.part") -> Unit
                        name.endsWith(".apk.part") -> {
                            // Drop orphan .part unless a download is running for it.
                            if (!running) {
                                AppLogger.info(TAG, "prune orphan part ${f.name}")
                                f.delete()
                            }
                        }
                        f.absolutePath == protectedPath -> {
                            AppLogger.info(TAG, "keep pending apk ${f.name}")
                        }
                        else -> {
                            val v = UpdateVersionLogic.normalizeTag(
                                name.removePrefix("minis-").removeSuffix(".apk"),
                            )
                            val cmp = UpdateVersionLogic.compareVersions(v, current)
                            if (cmp <= 0) {
                                // Older than, or equal to (already installed) running build.
                                AppLogger.info(TAG, "prune stale/installed apk ${f.name} (v=$v cmp=$cmp)")
                                f.delete()
                            }
                        }
                    }
                }
            }.onFailure { AppLogger.warning(TAG, "pruneUpdateDir failed: ${it.message}") }
        }
    }
}
