package com.openminis.app.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streaming chat export — T-export-optimize (b443b54d).
 *
 * Previously [SessionListScreen.exportSession] loaded every [MessageEntity]
 * for a session at once, built the full JSON / TXT payload as a single
 * [String] in memory, and handed that to [Intent.EXTRA_TEXT]. Hundreds of
 * messages caused jank, ghosting and OOM crashes.
 *
 * This exporter:
 *   - paginates the session via [ChatRepository.loadMessagePageRaw] in
 *     batches of [BATCH_SIZE], releasing each batch after it's written;
 *   - streams the serialized output into `cacheDir/export-staging/<uuid>/`
 *     using a [BufferedWriter] so peak memory stays bounded;
 *   - wraps the staged transcript + a `session.json` metadata sidecar in
 *     a single [ZipOutputStream]-built archive;
 *   - moves the final `.zip` into `cacheDir/shared/` (already declared in
 *     `file_provider_paths.xml`), where it can be handed out via
 *     [FileProvider];
 *   - cleans the staging directory on success and failure.
 *
 * Runs on [Dispatchers.IO]; [progress] is a [StateFlow] so a future UI
 * (progress overlay) can subscribe without re-architecting the call site.
 * Concurrent exports are serialized on [exportLock] so they cannot clobber
 * the singleton progress or race the same zip name.
 */
object ChatExporter {

    private const val BATCH_SIZE = 50
    private const val LOG_CATEGORY = "ChatExporter"

    sealed interface Progress {
        data object Idle : Progress
        data class Running(val done: Int, val total: Int) : Progress
        data class Done(val zipUri: Uri, val summary: Summary) : Progress
        data class Failed(val throwable: Throwable) : Progress
    }

    /**
     * Lightweight summary of a finished export. Populated as we stream so
     * the multi-select / "ready to share" UI can render a key-value preview
     * without re-reading the payload.
     */
    data class Summary(
        val format: String,              // "json" | "text"
        val messageCount: Int,
        val firstCreatedAt: Long?,       // ms, or null if empty
        val lastCreatedAt: Long?,
        val imageAttachments: Int,
        val videoAttachments: Int,
        val estimatedBytes: Long,
    )

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private val exportLock = Mutex()

    /**
     * Stream-export [session] in [format] (`"json"` | `"text"`) and return
     * a content [Uri] pointing at the zipped archive, suitable for
     * [Intent.ACTION_SEND]. Throws on failure; caller's coroutine scope
     * decides how to surface it. [Progress] is published to [progress] as
     * batches are written.
     */
    suspend fun exportToZip(
        context: Context,
        session: ChatSessionEntity,
        repository: ChatRepository,
        format: String,
    ): Pair<Uri, Summary> = com.openminis.app.data.body.Admission.occupy(
        com.openminis.app.data.body.ResourceLimits.SESSION_PREVIEW_BUDGET.toLong(),
    ) { withContext(Dispatchers.IO) {
        exportLock.withLock {
            val isJson = format == "json"
            val ext = if (isJson) "json" else "txt"
            val stagingRoot = File(context.cacheDir, "export-staging")
            val workDir = File(stagingRoot, UUID.randomUUID().toString())
            if (!workDir.mkdirs() && !workDir.isDirectory) {
                throw IllegalStateException("export-staging mkdir failed: ${workDir.absolutePath}")
            }

            try {
                val transcriptFile = File(workDir, "messages.$ext")
                val summary = streamTranscript(repository, session, isJson, transcriptFile)

                val metaFile = File(workDir, "session.json")
                writeSessionMeta(metaFile, session, summary)

                val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
                val zipFile = File(sharedDir, "${safeName(session)}.zip")
                if (zipFile.exists()) zipFile.delete()

                ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                    zipFileEntry(zos, "messages.$ext", transcriptFile)
                    zipFileEntry(zos, "session.json", metaFile)
                }

                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, zipFile)
                _progress.value = Progress.Done(uri, summary)
                AppLogger.info(LOG_CATEGORY, "exportToZip ok: ${zipFile.absolutePath} (${zipFile.length()} bytes, ${summary.messageCount} msgs)")
                uri to summary
            } catch (t: Throwable) {
                _progress.value = Progress.Failed(t)
                AppLogger.error(LOG_CATEGORY, "exportToZip failed: ${t.message}")
                throw t
            } finally {
                runCatching { workDir.deleteRecursively() }
            }
        }
    } }

    /**
     * Bulk export. One zip with a folder per session plus `manifest.json`.
     * A single-item list reuses [exportToZip] so the share sheet matches the
     * row context-menu path.
     */
    suspend fun exportManyToZip(
        context: Context,
        sessions: List<ChatSessionEntity>,
        repository: ChatRepository,
        format: String,
    ): Pair<Uri, Summary> = com.openminis.app.data.body.Admission.occupy(
        com.openminis.app.data.body.ResourceLimits.SESSION_PREVIEW_BUDGET.toLong(),
    ) { withContext(Dispatchers.IO) {
        if (sessions.isEmpty()) {
            throw IllegalArgumentException("no sessions to export")
        }
        if (sessions.size == 1) {
            return@withContext exportToZip(context, sessions.first(), repository, format)
        }
        exportLock.withLock {
            val isJson = format == "json"
            val ext = if (isJson) "json" else "txt"
            val stagingRoot = File(context.cacheDir, "export-staging")
            val workDir = File(stagingRoot, UUID.randomUUID().toString())
            if (!workDir.mkdirs() && !workDir.isDirectory) {
                throw IllegalStateException("export-staging mkdir failed: ${workDir.absolutePath}")
            }
            try {
                var totalMessages = 0
                var images = 0
                var videos = 0
                var bytes = 0L
                var first: Long? = null
                var last: Long? = null
                val manifestSessions = JSONArray()
                val usedFolders = mutableSetOf<String>()
                val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
                val zipFile = File(sharedDir, "minis-sessions-${sessions.size}.zip")
                if (zipFile.exists()) zipFile.delete()

                ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                    sessions.forEachIndexed { index, session ->
                        _progress.value = Progress.Running(index, sessions.size)
                        val folder = uniqueFolder(safeName(session), usedFolders)
                        val sessionDir = File(workDir, folder).apply { mkdirs() }
                        val transcriptFile = File(sessionDir, "messages.$ext")
                        val summary = streamTranscript(
                            repository, session, isJson, transcriptFile,
                            reportProgress = false,
                        )
                        val metaFile = File(sessionDir, "session.json")
                        writeSessionMeta(metaFile, session, summary)
                        zipFileEntry(zos, "$folder/messages.$ext", transcriptFile)
                        zipFileEntry(zos, "$folder/session.json", metaFile)
                        totalMessages += summary.messageCount
                        images += summary.imageAttachments
                        videos += summary.videoAttachments
                        bytes += summary.estimatedBytes
                        first = minOfNullable(first, summary.firstCreatedAt)
                        last = maxOfNullable(last, summary.lastCreatedAt)
                        manifestSessions.put(
                            JSONObject()
                                .put("id", session.id)
                                .put("title", session.title ?: "")
                                .put("folder", folder)
                                .put("message_count", summary.messageCount),
                        )
                    }
                    val manifest = JSONObject()
                        .put("format", if (isJson) "json" else "text")
                        .put("session_count", sessions.size)
                        .put("message_count", totalMessages)
                        .put("sessions", manifestSessions)
                    zos.putNextEntry(ZipEntry("manifest.json"))
                    zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

                val summary = Summary(
                    format = if (isJson) "json" else "text",
                    messageCount = totalMessages,
                    firstCreatedAt = first,
                    lastCreatedAt = last,
                    imageAttachments = images,
                    videoAttachments = videos,
                    estimatedBytes = bytes,
                )
                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, zipFile)
                _progress.value = Progress.Done(uri, summary)
                AppLogger.info(
                    LOG_CATEGORY,
                    "exportManyToZip ok: ${zipFile.absolutePath} (${sessions.size} sessions, $totalMessages msgs)",
                )
                uri to summary
            } catch (t: Throwable) {
                _progress.value = Progress.Failed(t)
                AppLogger.error(LOG_CATEGORY, "exportManyToZip failed: ${t.message}")
                throw t
            } finally {
                runCatching { workDir.deleteRecursively() }
            }
        }
    } }

    private suspend fun streamTranscript(
        repository: ChatRepository,
        session: ChatSessionEntity,
        isJson: Boolean,
        out: File,
        reportProgress: Boolean = true,
    ): Summary {
        val total = repository.messageCount(session.id)
        var done = 0
        var first: Long? = null
        var last: Long? = null
        var images = 0
        var videos = 0
        var bytes = 0L

        if (reportProgress) {
            _progress.value = Progress.Running(0, total)
        }

        BufferedWriter(OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8)).use { writer ->
            if (isJson) {
                writer.write("[")
                var firstEntry = true
                forEachBatch(repository, session.id, total) { batch ->
                    for (msg in batch) {
                        if (!firstEntry) writer.write(",")
                        firstEntry = false
                        val obj = JSONObject().apply {
                            put("id", msg.id)
                            put("role", msg.role)
                            put("content", msg.partsJson)
                            put("created_at", msg.createdAt)
                        }
                        val rendered = obj.toString()
                        writer.write(rendered)
                        bytes += rendered.length.toLong()
                        if (first == null) first = msg.createdAt
                        last = msg.createdAt
                        val (img, vid) = countAttachments(msg.partsJson)
                        images += img
                        videos += vid
                        done += 1
                    }
                    writer.flush()
                    if (reportProgress) {
                        _progress.value = Progress.Running(done, total)
                    }
                }
                writer.write("]")
            } else {
                writer.write(session.title ?: "Conversation")
                writer.write("\n\n")
                forEachBatch(repository, session.id, total) { batch ->
                    for (msg in batch) {
                        val role = if (msg.role == "user") "You" else "Assistant"
                        val text = extractPlainText(msg.partsJson)
                        writer.write(role)
                        writer.write(": ")
                        writer.write(text)
                        writer.write("\n\n")
                        bytes += text.length.toLong() + role.length + 4
                        if (first == null) first = msg.createdAt
                        last = msg.createdAt
                        val (img, vid) = countAttachments(msg.partsJson)
                        images += img
                        videos += vid
                        done += 1
                    }
                    writer.flush()
                    if (reportProgress) {
                        _progress.value = Progress.Running(done, total)
                    }
                }
            }
        }

        return Summary(
            format = if (isJson) "json" else "text",
            messageCount = done,
            firstCreatedAt = first,
            lastCreatedAt = last,
            imageAttachments = images,
            videoAttachments = videos,
            estimatedBytes = bytes,
        )
    }

    private suspend inline fun forEachBatch(
        repository: ChatRepository,
        sessionId: String,
        total: Int,
        block: (List<MessageEntity>) -> Unit,
    ) {
        if (total <= 0) return
        var offset = 0
        while (offset < total) {
            val batch = repository.loadMessagePageRaw(sessionId, offset, BATCH_SIZE)
            if (batch.isEmpty()) break
            block(batch)
            offset += batch.size
            if (batch.size < BATCH_SIZE) break
        }
    }

    private fun writeSessionMeta(file: File, session: ChatSessionEntity, summary: Summary) {
        val meta = JSONObject().apply {
            put("id", session.id)
            put("title", session.title ?: "")
            put("model_id", session.modelId)
            put("created_at", session.createdAt)
            put("message_count", summary.messageCount)
            summary.firstCreatedAt?.let { put("first_created_at", it) }
            summary.lastCreatedAt?.let { put("last_created_at", it) }
            put("image_attachments", summary.imageAttachments)
            put("video_attachments", summary.videoAttachments)
            put("format", summary.format)
        }
        file.writeText(meta.toString(2), Charsets.UTF_8)
    }

    private fun safeName(session: ChatSessionEntity): String {
        val base = (session.title ?: "conversation")
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .take(64)
            .ifEmpty { "conversation" }
        return "$base-${session.id.take(8)}"
    }

    private fun uniqueFolder(base: String, used: MutableSet<String>): String {
        var name = base
        var n = 2
        while (!used.add(name)) {
            name = "$base-$n"
            n += 1
        }
        return name
    }

    private fun minOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun zipFileEntry(zos: ZipOutputStream, name: String, file: File) {
        zos.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zos, bufferSize = 16 * 1024) }
        zos.closeEntry()
    }

    private fun extractPlainText(partsJson: String): String = try {
        val arr = JSONArray(partsJson)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type") == "text") {
                // [T-android-retry-attachment-loss] Strip the persisted
                // <user-attached-files> XML inventory from the human-readable
                // text export — it's model-facing metadata, not chat content.
                // (The JSON export above keeps full-fidelity parts_json.)
                var value = obj.optString("value")
                val start = value.indexOf("<user-attached-files>")
                if (start >= 0) {
                    val endTag = "</user-attached-files>"
                    val end = value.indexOf(endTag, start)
                    value = if (end >= 0) {
                        value.substring(0, start) + value.substring(end + endTag.length)
                    } else {
                        value.substring(0, start)
                    }.trim()
                }
                if (value.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(value)
                }
            }
        }
        sb.toString()
    } catch (_: Throwable) {
        partsJson
    }

    /** Best-effort `(images, videos)` count by walking parts_json. */
    private fun countAttachments(partsJson: String): Pair<Int, Int> = try {
        val arr = JSONArray(partsJson)
        var img = 0
        var vid = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            when (obj.optString("type")) {
                "image", "image_url" -> img += 1
                "video", "video_url" -> vid += 1
            }
        }
        img to vid
    } catch (_: Throwable) {
        0 to 0
    }
}
