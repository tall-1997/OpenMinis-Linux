package com.openminis.app.provider

import android.content.Context
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.logging.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-stream-trace-live] Runtime stream-trace recorder for the log-management
 * replay view. When enabled, every decoded stream chunk is appended as one
 * JSON line under `filesDir/diagnostics/stream-traces/` (one file per
 * recording session, rotated by day), replayable by eye or via
 * [StreamTrace.decode] for diffing.
 *
 * [T-diagnostics-host-path] The first cut wrote to a literal
 * `/var/minis/...` constant — a PRoot-guest path that does not exist in the
 * app process — so trace files were never written and the replay view was
 * permanently empty. The directory is now a real app-level host path,
 * captured from the callers that own a [Context].
 *
 * The unit-test regression net ([StreamTrace] + test resources) is the
 * enforcement layer; this recorder is the observation layer — "what did the
 * model actually stream last night" without re-running anything.
 */
object StreamTraceRecorder {
    private const val TAG = "StreamTrace"
    private const val PREFS = "stream_trace"
    private const val PREFS_ENABLED = "enabled"

    @Volatile
    var enabled: Boolean = false
        private set

    /** Resolved at [setEnabled] / [restore]; the only writers of traces. */
    @Volatile
    private var baseDir: File? = null

    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
    }

    private val file = java.util.concurrent.atomic.AtomicReference<File?>(null)

    fun dir(context: Context): File = File(context.filesDir, "diagnostics/stream-traces")

    fun setEnabled(enabled: Boolean, context: android.content.Context) {
        this.enabled = enabled
        baseDir = dir(context)
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(PREFS_ENABLED, enabled).apply()
        if (!enabled) file.set(null)
    }

    fun restore(context: android.content.Context) {
        enabled = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREFS_ENABLED, false)
        baseDir = dir(context)
    }

    /** One JSON line per chunk; new file per recording session. */
    fun record(chunk: LLMStreamChunk) {
        if (!enabled) return
        runCatching {
            val f = file.get() ?: synchronized(this) {
                file.get() ?: baseDir.let { dir ->
                    dir?.mkdirs()
                    File(dir, "trace_${dateFormat.get().format(Date())}.jsonl")
                }.also { file.set(it) }
            }
            f.appendText(StreamTrace.encodeChunk(chunk).toString() + "\n")
        }.onFailure {
            AppLogger.error(TAG, "stream trace write failed: ${it.message}")
        }
    }

    /** End the current recording session (next record starts a new file). */
    fun newSession() {
        file.set(null)
    }
}
