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

 /** Per-event cap for [recordRawSse]; one delta can be large, a trace must not be. */
 private const val MAX_RAW_SSE_CHARS =4000

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
 currentFile().appendText(StreamTrace.encodeChunk(chunk).toString() + "\n")
 }.onFailure {
 AppLogger.error(TAG, "stream trace write failed: ${it.message}")
 }
 }

 /**
 * [T-thinking-request-trace] Record the OUTGOING request body once per
 * request, as one `{"kind":"request", …}` line in the same session file.
 *
 * Why this exists: the chunk trace can prove what the model streamed but
 * not what we ASKED for. "Thinking never shows up" has two very different
 * causes — the request never carried a reasoning parameter, or it carried
 * one the endpoint ignored — and without the outgoing body those are
 * indistinguishable from the outside. Only the thinking-relevant keys are
 * recorded (`thinking` / `reasoning` / `reasoning_effort` /
 * `enable_thinking` / `include_reasoning`), so the trace stays small and
 * never contains conversation content.
 */
 fun recordRequest(body: org.json.JSONObject) {
 if (!enabled) return
 val relevant = listOf("thinking", "reasoning", "reasoning_effort", "enable_thinking", "include_reasoning")
 val captured = org.json.JSONObject()
 relevant.forEach { key -> if (body.has(key)) captured.put(key, body.get(key)) }
 runCatching {
 val line = org.json.JSONObject()
 .put("kind", "request")
 .put("keys", org.json.JSONArray(body.keys().asSequence().toList()))
 .put("thinkingParams", captured)
 currentFile().appendText(line.toString() + "\n")
 }.onFailure {
 AppLogger.error(TAG, "stream trace request write failed: ${it.message}")
 }
 }

 /**
 * [T-thinking-raw-sse] Record one RAW SSE event payload (already re-joined
 * from its `data:` lines) when tracing is on.
 *
 * Why: [record] captures only the chunks OUR parser produced, so "no
 * thinking chunk" cannot distinguish "the endpoint sent none" from "the
 * endpoint sent reasoning in a field we do not read". The raw line settles
 * that question in one request. Truncated per event so a huge delta cannot
 * bloat the trace; nothing is written when tracing is off.
 */
 fun recordRawSse(payload: String) {
 if (!enabled) return
 runCatching {
 val clipped = if (payload.length <= MAX_RAW_SSE_CHARS) {
 payload
 } else {
 payload.take(MAX_RAW_SSE_CHARS) + "…(clipped)"
 }
 val line = org.json.JSONObject()
 .put("kind", "raw_sse")
 .put("data", clipped)
 currentFile().appendText(line.toString() + "\n")
 }.onFailure {
 AppLogger.error(TAG, "stream trace raw write failed: ${it.message}")
 }
 }

 private fun currentFile(): File {
 file.get()?.let { return it }
 synchronized(this) {
 file.get()?.let { return it }
 val dir = baseDir ?: throw IllegalStateException("trace dir not resolved")
 dir.mkdirs()
 return File(dir, "trace_${dateFormat.get().format(Date())}.jsonl").also { file.set(it) }
 }
 }

 /** End the current recording session (next record starts a new file). */
 fun newSession() {
 file.set(null)
 }
}
