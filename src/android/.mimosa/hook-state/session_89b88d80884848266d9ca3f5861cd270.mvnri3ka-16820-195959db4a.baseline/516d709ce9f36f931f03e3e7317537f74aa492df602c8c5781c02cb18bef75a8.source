package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

object FileWriteTool {
    const val NAME = "file_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Write content to a file on the Linux filesystem. Faster than shell_execute for writing files. Creates the file if it doesn't exist. Use append mode to add to existing files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Create Python statistics script', 'Write configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to write (e.g. /root/test.txt)"),
            "content" to AgentToolParam("string", "The text content to write to the file"),
            "append" to AgentToolParam("boolean", "If true, append to existing file instead of overwriting (default: false)"),
            "create_dirs" to AgentToolParam("boolean", "If true, create parent directories if they don't exist (default: false)"),
        ),
        required = listOf("tool_title", "path", "content"),
        propertyOrdering = listOf("tool_title", "path", "content", "append", "create_dirs"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val content = args.optString("content", "")
            val append = args.optBoolean("append", false)
            val createDirs = args.optBoolean("create_dirs", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // T219: read-only mount guard. Reject before opening so we don't
            // half-create files inside a Locked external mount and surface a
            // friendly hint pointing the user at Settings. Mirrors iOS
            // MountedFolderCoordinator.isLinuxPathUnderReadOnlyMount used by
            // AIChatViewModel.fileWrite (AIChatViewModel.swift:8333-8341).
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }
            WritePathGuard.denyReason(path)?.let { msg ->
                return ToolExecutionResult(msg, false, toolTitle = toolTitle)
            }
            // [T-memory-poison-guard] Same daily quota as memory_write and the
            // shell gate — without it a refused loop just switches tools and
            // keeps appending to /var/minis/memory through this path.
            com.openminis.app.data.repository.MemoryRepository
                .fileToolQuotaRefusal(context.filesDir, sessionId, path)
                ?.let { msg -> return ToolExecutionResult(msg, false, toolTitle = toolTitle) }

            // T123: per-session resolver so /var/minis/workspace/...,
            // /var/minis/attachments/..., /var/minis/offloads/...,
            // /var/minis/browser/... land in this session's host dir
            // rather than the global bind-mount map (which is overwritten
            // every time another session boots its shell, last-writer-wins).
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            // [T-file-checkpoint] Snapshot the CURRENT bytes before they are
            // replaced, so a bad rewrite is recoverable via file_checkpoint
            // restore. Best-effort — a capture failure must never block the
            // write itself. Capture only fires when the file already exists;
            // a create has nothing to lose and restore would just delete it.
            if (!append && file.exists() && file.isFile) {
                FileCheckpointStore.capture(
                    context, sessionId, listOf(path),
                    label = "before file_write", source = "file_write",
                ).checkpoint?.let { cp ->
                    com.openminis.app.logging.AppLogger.info(
                        "FileWrite", "checkpoint ${cp.id} captured for $path",
                    )
                }
            }

            // Validate UTF-8
            try {
                content.toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                return ToolExecutionResult("Error: Content is not valid UTF-8", false, toolTitle = toolTitle)
            }

            // T123: mirror iOS AIChatViewModel L8339 — auto-create the
            // parent dir whenever it doesn't exist, regardless of the
            // create_dirs flag. Per-session subdirs (workspace, etc.) are
            // materialized lazily, so a fresh session writing into
            // /var/minis/workspace/foo/bar.md would otherwise hit "Parent
            // directory does not exist" on the very first call.
            val parent = file.parentFile
            if (parent != null && (createDirs || !parent.exists())) {
                parent.mkdirs()
            }

            // AtomicFileWrite: per-path lock so a concurrent file_edit cannot
            // interleave with this write, an atomic temp+rename so a crash
            // mid-write cannot leave a half-written file, and a read-back
            // verification so a write that did not really land is an ERROR
            // rather than a success the caller acts on.
            //
            // The old code only verified anything for /var/minis/mounts/ paths
            // and merely logged a warning when the check failed — everywhere
            // else `writeText` returning without throwing was reported as
            // success, which is how "wrote it but nothing is on disk" stayed
            // invisible.
            val bytes = AtomicFileWrite.write(file, content, append)
                ?: return ToolExecutionResult(
                    "Error: write to $path reported success but did not persist to disk. " +
                        "The file may be on a full or read-only volume — check free space " +
                        "and Settings → Mount External Folders.",
                    false, toolTitle = toolTitle,
                )

            com.openminis.app.logging.AppLogger.info(
                "FileWrite",
                "wrote path=$path host=${file.absolutePath} bytes=$bytes append=$append",
            )
            ToolExecutionResult("Wrote to $path ($bytes bytes)", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error writing file: ${e.message}", false)
        }
    }
}
