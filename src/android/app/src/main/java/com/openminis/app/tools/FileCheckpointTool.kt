package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshots of files the agent is about to modify, so a bad rewrite can be
 * rolled back without asking the user to restate the original content.
 *
 * Storage lives in the session's PRIVATE base dir (`sessions/<owner>/
 * .checkpoints/`), never in `/var/minis/workspace`: workspace is a shared
 * project subdir, so a snapshot there would leak into the shell and be picked
 * up by project sync. Snapshots are per-chat and die with the chat.
 *
 * A checkpoint records whether each path EXISTED. Restoring a path that did
 * not exist before deletes it — that is what makes "undo my change" correct for
 * a file the agent just created, not only for one it overwrote.
 */
object FileCheckpointStore {
    private const val DIR = ".checkpoints"
    private const val MANIFEST = "manifest.json"
    private const val PAYLOAD = "payload"
    /** Refuse to snapshot anything larger — checkpoints are a safety net, not a backup. */
    const val MAX_FILE_BYTES = 8L * 1024 * 1024
    const val MAX_CHECKPOINTS = 50

    class Entry(
        val path: String,
        val existed: Boolean,
        val payloadName: String?,
        val bytes: Long,
        val isDir: Boolean,
    )

    class Checkpoint(
        val id: String,
        val createdAt: Long,
        val label: String,
        val source: String,
        val entries: List<Entry>,
    )

    class Outcome(val checkpoint: Checkpoint?, val skipped: List<String>, val reason: String?)

    private fun root(filesDir: File, sessionId: String): File =
        File(SessionWorkspace.base(filesDir, ExecutionCoordinator.ownerSessionId(sessionId)), DIR)

    private fun dirFor(filesDir: File, sessionId: String, id: String): File = File(root(filesDir, sessionId), id)

    /**
     * Path resolution uses the RAW session id (a lane agent must map guest
     * paths through the same id file_write saw), while storage uses the owner
     * id — a lane agent's checkpoints belong in the same pool as its parent's,
     * or `restore` from the main chat could not find them.
     */

    /**
     * Snapshot [linuxPaths]. Never throws and never blocks the write that
     * triggered it: a checkpoint failure degrades to "no rollback point",
     * reported in [Outcome.reason], while the write proceeds.
     */
    /**
     * Maps a guest path to the host File the checkpoint payload refers to.
     * Injected so the snapshot/restore logic itself runs on a plain JVM in
     * unit tests; production always passes PRootKernel's resolver.
     */
    fun interface Resolver { fun resolve(path: String): File? }

    fun capture(
        context: Context,
        sessionId: String,
        linuxPaths: List<String>,
        label: String = "",
        source: String = "manual",
    ): Outcome = capture(
        filesDir = context.filesDir,
        sessionId = sessionId,
        linuxPaths = linuxPaths,
        label = label,
        source = source,
        resolver = { PRootKernel.resolveSessionHostPath(sessionId, it, context) },
    )

    fun capture(
        filesDir: File,
        sessionId: String,
        linuxPaths: List<String>,
        resolver: Resolver,
        label: String = "",
        source: String = "manual",
    ): Outcome { // block body: the early `return` below is illegal in an expression body
        return try {
        val wanted = linuxPaths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return Outcome(null, emptyList(), "no paths given")
        val entries = mutableListOf<Entry>()
        val skipped = mutableListOf<String>()
        val id = UUID.randomUUID().toString().take(8)
        val dir = dirFor(filesDir, sessionId, id)
        val payloadDir = File(dir, PAYLOAD).apply { mkdirs() }
        wanted.forEachIndexed { i, linuxPath ->
            val host = resolver.resolve(linuxPath)
            when {
                host == null -> skipped += "$linuxPath (unresolvable path)"
                host.isDirectory -> {
                    // Directory contents are not snapshotted; capture() is a
                    // per-file safety net, and recursively copying an
                    // arbitrary tree risks filling the device.
                    skipped += "$linuxPath (directory, not captured)"
                }
                host.length() > MAX_FILE_BYTES ->
                    skipped += "$linuxPath (${host.length()} bytes > $MAX_FILE_BYTES)"
                else -> {
                    if (host.exists()) {
                        val name = "$i.bin"
                        host.inputStream().use { ins -> File(payloadDir, name).outputStream().use { outs -> ins.copyTo(outs) } }
                        entries += Entry(
                            path = linuxPath,
                            existed = true,
                            payloadName = name,
                            bytes = host.length(),
                            isDir = false,
                        )
                    } else {
                        entries += Entry(
                            path = linuxPath,
                            existed = false,
                            payloadName = null,
                            bytes = 0,
                            isDir = false,
                        )
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            dir.deleteRecursively()
            Outcome(null, skipped, "nothing to snapshot")
        } else {
            val cp = Checkpoint(id, System.currentTimeMillis(), label, source, entries)
            File(dir, MANIFEST).writeText(encode(cp))
            prune(filesDir, sessionId, id)
            Outcome(cp, skipped, null)
        }
    } catch (e: Exception) {
            AppLogger.warning("FileCheckpoint", "capture failed: ${e.message}")
            Outcome(null, emptyList(), e.message ?: "capture failed")
        }
    }

    /** Restore [id] (or the newest checkpoint when null). Returns a per-path report. */
    fun restore(context: Context, sessionId: String, id: String?): Result = restore(
        filesDir = context.filesDir,
        sessionId = sessionId,
        id = id,
        resolver = { PRootKernel.resolveSessionHostPath(sessionId, it, context) },
    )

    fun restore(filesDir: File, sessionId: String, id: String?, resolver: Resolver): Result {
        // parens are load-bearing: without them `?:` binds to the else branch
        // only and `cp` comes out as Checkpoint?
        val cp = (if (id.isNullOrBlank()) newest(filesDir, sessionId) else load(filesDir, sessionId, id))
            ?: return Result(false, listOf("No checkpoint found${if (id.isNullOrBlank()) "" else " for id '$id'"}"), false)
        val lines = mutableListOf<String>()
        var failed = false
        for ((i, e) in cp.entries.withIndex()) {
            val host = resolver.resolve(e.path)
            if (host == null) {
                lines += "skipped ${e.path}: path no longer resolves"
                failed = true
                continue
            }
            try {
                if (e.existed && e.payloadName != null) {
                    val src = File(File(dirFor(filesDir, sessionId, cp.id), PAYLOAD), e.payloadName)
                    host.parentFile?.mkdirs()
                    // temp+rename so a crash mid-restore cannot leave a
                    // half-written file where a good one used to be.
                    val tmp = File(host.parentFile, ".restore-${cp.id}-$i.tmp")
                    src.inputStream().use { ins -> tmp.outputStream().use { outs -> ins.copyTo(outs) } }
                    if (!tmp.renameTo(host)) {
                        host.delete()
                        check(tmp.renameTo(host)) { "cannot replace ${e.path}" }
                    }
                    lines += "restored ${e.path} (${e.bytes} bytes)"
                } else {
                    if (host.exists()) {
                        if (!host.delete()) {
                            lines += "failed ${e.path}: cannot delete newly-created file"
                            failed = true
                            continue
                        }
                    }
                    lines += "removed ${e.path} (did not exist before the checkpoint)"
                }
            } catch (ex: Exception) {
                lines += "failed ${e.path}: ${ex.message}"
                failed = true
            }
        }
        if (!failed) drop(filesDir, sessionId, cp.id)
        return Result(!failed, lines, true, cp)
    }

    class Result(
        val success: Boolean,
        val lines: List<String>,
        val found: Boolean,
        val checkpoint: Checkpoint? = null,
    )

    fun list(context: Context, sessionId: String): List<Checkpoint> =
        root(context.filesDir, sessionId).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { load(context.filesDir, sessionId, it.name) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun drop(filesDir: File, sessionId: String, id: String): Boolean = dirFor(filesDir, sessionId, id).deleteRecursively()

    /** Best-effort: keep the newest [MAX_CHECKPOINTS], delete the rest. */
    private fun prune(filesDir: File, sessionId: String, keepId: String) {
        val ids = root(filesDir, sessionId).listFiles()?.filter { it.isDirectory }?.map { it.name } ?: return
        if (ids.size <= MAX_CHECKPOINTS) return
        val byTime = ids.sortedByDescending { File(root(filesDir, sessionId), it).lastModified() }
        byTime.drop(MAX_CHECKPOINTS).filter { it != keepId }.forEach { drop(filesDir, sessionId, it) }
    }

    private fun newest(filesDir: File, sessionId: String): Checkpoint? =
        root(filesDir, sessionId).listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.lastModified() }
            ?.let { load(filesDir, sessionId, it.name) }

    private fun load(filesDir: File, sessionId: String, id: String): Checkpoint? = try {
        val f = File(dirFor(filesDir, sessionId, id), MANIFEST)
        if (!f.isFile) null else {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("entries") ?: JSONArray()
            Checkpoint(
                id = o.optString("id", id),
                createdAt = o.optLong("createdAt"),
                label = o.optString("label"),
                source = o.optString("source"),
                entries = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    Entry(
                        path = e.optString("path"),
                        existed = e.optBoolean("existed", true),
                        payloadName = e.optString("payload").takeIf { it.isNotEmpty() },
                        bytes = e.optLong("bytes"),
                        isDir = e.optBoolean("dir", false),
                    )
                }.filter { it.path.isNotEmpty() },
            )
        }
    } catch (e: Exception) {
        AppLogger.warning("FileCheckpoint", "unreadable manifest $id: ${e.message}")
        null
    }

    private fun encode(cp: Checkpoint): String = JSONObject().apply {
        put("id", cp.id)
        put("createdAt", cp.createdAt)
        put("label", cp.label)
        put("source", cp.source)
        put("entries", JSONArray().apply {
            cp.entries.forEach { e ->
                put(JSONObject().apply {
                    put("path", e.path)
                    put("existed", e.existed)
                    put("payload", e.payloadName ?: "")
                    put("bytes", e.bytes)
                    put("dir", e.isDir)
                })
            }
        })
    }.toString()

    fun render(cps: List<Checkpoint>): String {
        if (cps.isEmpty()) return "(no checkpoints)"
        return buildString {
            append("checkpoints: ${cps.size}\n")
            cps.forEach { c ->
                val files = c.entries.size
                val created = c.entries.count { it.existed }
                append("[${c.id}] ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(c.createdAt))} " +
                    "from=${c.source} files=$files existing=$created")
                if (c.label.isNotBlank()) append(" — ${c.label}")
                append("\n")
                c.entries.take(8).forEach { append("    ${it.path}\n") }
                if (c.entries.size > 8) append("    … ${c.entries.size - 8} more\n")
            }
        }
    }
}

object FileCheckpointTool {
    const val NAME = "file_checkpoint"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Snapshot files before a risky rewrite and roll them back later. Operations: capture (op=capture, paths=[...]), " +
            "list, restore (op=restore, id=... ; omit id for the newest), drop. Auto-capture is on by default: file_write / file_edit " +
            "already snapshot the files they overwrite, so restore is usually enough. " +
            "capture explicitly before bulk shell rewrites (sed -i, scripts) that bypass file_write/file_edit.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "op" to AgentToolParam("string", "capture, list, restore, or drop.", enumValues = listOf("capture", "list", "restore", "drop")),
            "paths" to AgentToolParam("array", "Absolute Linux paths to snapshot when op=capture.", items = AgentToolParam("string", "One absolute path")),
            "id" to AgentToolParam("string", "Checkpoint id for restore/drop. Omit on restore to roll back the newest checkpoint."),
            "label" to AgentToolParam("string", "Optional short description of what the checkpoint is for."),
        ),
        required = listOf("tool_title", "op"),
        propertyOrdering = listOf("tool_title", "op", "paths", "id", "label"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title", NAME) }.getOrDefault(NAME)
        return try {
            val args = JSONObject(argsJson)
            when (args.optString("op", "list")) {
                "capture" -> {
                    val arr = args.optJSONArray("paths")
                    if (arr == null || arr.length() == 0) {
                        return ToolExecutionResult("Error: 'paths' is required for op=capture", false, errorCode = ToolErrorCode.INVALID_ARGUMENTS, toolTitle = toolTitle)
                    }
                    val paths = (0 until arr.length()).map { arr.optString(it) }
                    val out = FileCheckpointStore.capture(context, sessionId, paths, args.optString("label"), "file_checkpoint")
                    val cp = out.checkpoint
                    val text = buildString {
                        if (cp != null) {
                            append("Captured ${cp.id}: ${cp.entries.size} file(s).")
                            cp.entries.take(20).forEach { append("\n  ${it.path} (${it.bytes} bytes)") }
                            if (cp.entries.size > 20) append("\n  … ${cp.entries.size - 20} more")
                        } else {
                            append("Nothing captured (${out.reason}).")
                        }
                        out.skipped.forEach { append("\n  skipped: $it") }
                        if (cp != null) append("\nRoll back with op=restore id=${cp.id}")
                    }
                    ToolExecutionResult(text, true, toolTitle = toolTitle)
                }
                "restore" -> {
                    val r = FileCheckpointStore.restore(context, sessionId, args.optString("id").takeIf { it.isNotBlank() })
                    if (!r.found) return ToolExecutionResult("Error: ${r.lines.first()}", false, errorCode = ToolErrorCode.NOT_FOUND, toolTitle = toolTitle)
                    ToolExecutionResult(
                        r.lines.joinToString("\n"),
                        r.success,
                        errorCode = if (r.success) null else ToolErrorCode.EXECUTION_FAILED,
                        toolTitle = toolTitle,
                    )
                }
                "drop" -> {
                    val id = args.optString("id")
                    if (id.isBlank()) return ToolExecutionResult("Error: 'id' is required for op=drop", false, errorCode = ToolErrorCode.INVALID_ARGUMENTS, toolTitle = toolTitle)
                    val ok = FileCheckpointStore.drop(context.filesDir, sessionId, id)
                    ToolExecutionResult(if (ok) "Dropped checkpoint $id" else "Error: no checkpoint $id", ok, toolTitle = toolTitle)
                }
                else -> ToolExecutionResult(FileCheckpointStore.render(FileCheckpointStore.list(context, sessionId)), true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error file_checkpoint: ${e.message}", false, errorCode = ToolErrorCode.EXECUTION_FAILED, toolTitle = toolTitle)
        }
    }
}