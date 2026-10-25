package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.io.VerifiedCopy
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.SessionWorkspace
import org.json.JSONObject
import java.io.File

/**
 * [T-android-present-tool] Deepseek-harness-style deliverable presentation:
 * when the agent produces a report / chart / exported file the user should
 * keep, `present` COPIES it (never moves — the sandbox original stays the
 * agent's working copy) into `filesDir/minis-global/shared/`, the directory
 * the sandbox binds at `/var/minis/shared` and the activated
 * MinisDocumentsProvider exposes to the system Files app as
 * "Minis Ultra > shared". The result text carries the guest `Path:` line of
 * the archived copy — the machine-readable channel the chat card
 * (PresentDeliverableCard) parses to render Open / Share actions, the same
 * parse-from-content pattern ToolOutputSpill.parseGuestPath uses.
 *
 * Source validation follows the file-tool convention:
 * [PRootKernel.resolveSessionHostPath] maps the guest path through the
 * session's bind mounts and then applies [SessionWorkspace.acceptsResolved],
 * which refuses another session's `minis-sessions/<other>` or another
 * project's `minis-workspaces/<other>` tree — a worker cannot archive a
 * sibling session's files. [WritePathGuard] semantics are untouched: present
 * READS the source (reads are unrestricted) and writes only to the app-owned
 * shared directory, never to a guest write path.
 *
 * [executeCore] is the pure file layer (no Android types) so the collision
 * numbering, size cap and success copy are unit-testable on the JVM.
 */
object PresentTool {
    const val NAME = "present_file"

    /** Hard cap per archived file: deliverables, not dumps. */
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    /** Guest path the sandbox bind-mounts onto [sharedHostDir]. */
    const val SHARED_GUEST_DIR = "/var/minis/shared"

    /** Fallback name when the source file has no usable name. */
    internal const val FALLBACK_NAME = "deliverable"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Archive a finished deliverable (report, chart, exported dataset, generated file) " +
            "so the USER can keep it: copies the file (the original stays untouched) into the app's " +
            "shared folder, which the system Files app exposes under Minis Ultra > shared, and returns " +
            "the sandbox path of the archived copy. Use it when the user asks to save / export / " +
            "download / keep a produced file — not for scratch files the agent still works on. " +
            "Maximum 50 MB per file; directories are rejected.",
        parameters = mapOf(
            "path" to AgentToolParam(
                "string",
                "Sandbox path of the file to archive, e.g. /var/minis/workspace/report.md. " +
                    "Must be a regular file inside this session's sandbox.",
            ),
            "title" to AgentToolParam(
                "string",
                "Optional short deliverable title shown on the result card, in the user's language.",
            ),
            "note" to AgentToolParam(
                "string",
                "Optional one-line note for the user, e.g. what the file contains.",
            ),
        ),
        required = listOf("path"),
        propertyOrdering = listOf("path", "title", "note"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid JSON arguments", false, toolTitle = NAME)
        }
        return executeCore(args, sharedHostDir(context)) { raw ->
            PRootKernel.resolveSessionHostPath(sessionId, WritePathGuard.normalize(raw), context)
        }
    }

    /** Host directory behind `/var/minis/shared` (system Files app visible). */
    fun sharedHostDir(context: Context): File =
        File(File(context.filesDir, SessionWorkspace.GLOBAL_DIR), SHARED_SUBDIR)

    /** The bind subdir name; pinned to [SessionWorkspace.GLOBAL_BIND_SUBDIRS]. */
    internal const val SHARED_SUBDIR = "shared"

    /**
     * The single `Path:` line in a successful result is the UI channel: the
     * deliverable card parses it to resolve the host file for Open / Share.
     * The line holds ONLY the path, so the regex can capture names with
     * spaces ("report (2).md") intact.
     */
    private val PATH_LINE = Regex("(?m)^Path: (" + Regex.escape(SHARED_GUEST_DIR) + "/.+)$")

    /** Guest path of the archived copy, or null when [output] carries none. */
    fun parseSharedGuestPath(output: String): String? {
        val raw = PATH_LINE.find(output)?.groupValues?.get(1)?.trim() ?: return null
        val name = raw.removePrefix("$SHARED_GUEST_DIR/")
        if (name.isEmpty() || name == "." || name == ".." || name.contains("/")) return null
        return "$SHARED_GUEST_DIR/$name"
    }

    /** Split off path separators, then neuter control chars; keep unicode names. */
    internal fun sanitizeFileName(raw: String): String {
        val base = raw.trim().replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = base.map { if (it.code in 0x00..0x1f) '_' else it }.joinToString("").trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return FALLBACK_NAME
        return cleaned.take(120)
    }

    /**
     * First free `name` / `name (N).ext` slot in [dir]. Returns the target
     * and the copy number (1 = first archive of this name, 2+ = collision).
     */
    internal fun allocateTarget(dir: File, name: String): Pair<File, Int> {
        var candidate = File(dir, name)
        var n = 1
        if (candidate.exists()) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            do {
                n++
                candidate = File(dir, "$stem ($n)$ext")
            } while (candidate.exists())
        }
        return candidate to n
    }

    internal fun executeCore(
        args: JSONObject,
        sharedDir: File,
        resolveSource: (String) -> File?,
    ): ToolExecutionResult {
        val rawPath = args.optString("path").trim()
        val title = args.optString("title").trim()
        val note = args.optString("note").trim()
        if (rawPath.isEmpty()) {
            return ToolExecutionResult("Error: 'path' is required", false, toolTitle = NAME)
        }
        val source = resolveSource(rawPath)
            ?: return ToolExecutionResult(
                "Error: cannot resolve $rawPath to a file inside this session's sandbox. " +
                    "present archives files under /var/minis only (workspace, attachments, ...).",
                false,
                errorCode = ToolErrorCode.NOT_FOUND,
                toolTitle = NAME,
            )
        if (!source.exists()) {
            return ToolExecutionResult(
                "Error: file not found: $rawPath",
                false,
                errorCode = ToolErrorCode.NOT_FOUND,
                toolTitle = NAME,
            )
        }
        if (source.isDirectory) {
            return ToolExecutionResult(
                "Error: path is a directory; present needs a regular file: $rawPath",
                false,
                errorCode = ToolErrorCode.UNSUPPORTED,
                toolTitle = NAME,
            )
        }
        val size = source.length()
        if (size <= 0L) {
            // Same doctrine as VerifiedCopy.copyFileToDocument's 0-byte-husk
            // guard: the raw copy layer would verify 0 == 0 as INTACT, so the
            // emptiness check belongs up here.
            return ToolExecutionResult(
                "Error: source is empty (0 bytes); present refuses to archive an empty file: $rawPath",
                false,
                errorCode = ToolErrorCode.UNSUPPORTED,
                toolTitle = NAME,
            )
        }
        if (size > MAX_FILE_BYTES) {
            return ToolExecutionResult(
                "Error: file is $size bytes; present accepts at most $MAX_FILE_BYTES bytes (50 MB).",
                false,
                errorCode = ToolErrorCode.UNSUPPORTED,
                toolTitle = NAME,
            )
        }
        if (!sharedDir.isDirectory && !sharedDir.mkdirs()) {
            return ToolExecutionResult(
                "Error: could not create the shared folder",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                toolTitle = NAME,
            )
        }
        val (target, copyNumber) = allocateTarget(sharedDir, sanitizeFileName(source.name))
        val copy = VerifiedCopy.copyFileToFile(source, target)
        if (!copy.ok) {
            return ToolExecutionResult(
                "Error: archived copy failed verification: ${copy.reason}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                toolTitle = NAME,
            )
        }
        val guestPath = "$SHARED_GUEST_DIR/${target.name}"
        val text = buildString {
            append("Presented \"").append(title.ifEmpty { target.name })
            append("\" — archived as ").append(target.name).append(".\n")
            append("Path: ").append(guestPath).append('\n')
            append("The original file is untouched; the copy is readable from the sandbox at the path above.\n")
            append("The user finds the copy in the system Files app under Minis Ultra > shared.\n")
            if (copyNumber > 1) {
                append("A same-name file was already archived; this is copy #").append(copyNumber).append(".\n")
            }
            if (note.isNotEmpty()) {
                append("Note: ").append(note).append('\n')
            }
        }
        return ToolExecutionResult(text, true, toolTitle = title.ifEmpty { NAME })
    }
}
