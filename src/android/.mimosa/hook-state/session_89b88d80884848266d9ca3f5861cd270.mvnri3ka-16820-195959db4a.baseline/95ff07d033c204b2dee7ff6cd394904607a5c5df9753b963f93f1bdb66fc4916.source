package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

object FileEditTool {
    const val NAME = "file_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Make targeted edits to an existing file using exact string replacement. ALWAYS use file_read first to see the current file contents before editing. Prefer file_edit over file_write when modifying existing files — only the changed part needs to be specified. The old_string must match exactly one location in the file (including whitespace/indentation), unless replace_all is true.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Fix typo in Python script', 'Update config value'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to the file to edit (e.g. /root/script.py)"),
            "old_string" to AgentToolParam("string", "The exact text to find in the file. Must match precisely including whitespace and indentation. Must be unique in the file unless replace_all is true."),
            "new_string" to AgentToolParam("string", "The replacement text. Use empty string to delete old_string."),
            "replace_all" to AgentToolParam("boolean", "If true, replace ALL occurrences of old_string (default: false)"),
        ),
        required = listOf("tool_title", "path", "old_string", "new_string"),
        propertyOrdering = listOf("tool_title", "path", "old_string", "new_string", "replace_all"),
    )

    /**
     * [T-text-replacers] Three-level degradation ladder (Exact → line-trimmed
     * → block-anchor). Failures no longer fall through to a generic "not found"
     * — ambiguity tells the model to add context or use replace_all; not-found
     * tells it to file_read the current content.
     */
    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val oldString = args.optString("old_string", "")
            val newString = args.optString("new_string", "")
            val replaceAll = args.optBoolean("replace_all", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }
            if (oldString.isEmpty()) {
                return ToolExecutionResult("Error: 'old_string' is required and cannot be empty", false, toolTitle = toolTitle)
            }

            // T219: read-only mount guard — see FileWriteTool for rationale.
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
            // shell gate — see FileWriteTool.
            com.openminis.app.data.repository.MemoryRepository
                .fileToolQuotaRefusal(context.filesDir, sessionId, path)
                ?.let { msg -> return ToolExecutionResult(msg, false, toolTitle = toolTitle) }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            

            // T-tool-write-race: read, compute AND write under ONE lock.
            // readModifyWrite takes the read inside the lock, so the compute
            // always sees the bytes this write will replace.
            //
            // [T-text-replacers] 三级降级阶梯（Exact → line-trimmed →
            // block-anchor）。替换失败不再落到笼统的 not-found：ambiguity
            // 提示加上下文或 replace_all，not-found 提示重新 file_read。
            // [T-file-edit-silent-drop] 两个暗坑的修复：
            //  1. Success 返回的 ReplaceOutcome 之前落到 readModifyWrite 的
            //     else 分支——原样返回、不落盘，工具却报 "Edited N
            //     replacements" 成功。现在 ReplaceOutcome 实现
            //     HasPersistableText，readModifyWrite 会真正写入。
            //  2. Failure 返回的 String 错误消息之前会被 readModifyWrite
            //     当成"新文件内容"写进磁盘——一次失败的替换会摧毁整个文件。
            //     现在 Failure 用 EditFailure 标记（persistableText=null），
            //     只报错、不动文件。
            val outcome = AtomicFileWrite.readModifyWrite(file) { current ->
                when (val r = TextReplacers.replace(current, oldString, newString, replaceAll)) {
                    is TextReplacers.Result.Success -> {
                        // [T-file-checkpoint] Snapshot inside the SAME per-path
                        // lock as the write, and only on the success branch: a
                        // capture outside the lock could read bytes another
                        // writer already replaced, and capturing before we know
                        // the replacement matched would litter the list with
                        // checkpoints for edits that never happened.
                        FileCheckpointStore.capture(
                            context, sessionId, listOf(path),
                            label = "before file_edit", source = "file_edit",
                        ).checkpoint?.let { cp ->
                            com.openminis.app.logging.AppLogger.info(
                                "FileEdit", "checkpoint ${cp.id} captured for $path",
                            )
                        }
                        ReplaceOutcome(r.newContent, r.count)
                    }
                    is TextReplacers.Result.Failure ->
                        EditFailure(r.message)
                }
            }

            when (outcome) {
                null -> ToolExecutionResult(
                    "Error: failed to write $path (atomic write did not verify on disk)",
                    false, toolTitle = toolTitle,
                )
                is EditFailure -> ToolExecutionResult(
                    "Error: ${outcome.message}",
                    false, toolTitle = toolTitle,
                )
                is ReplaceOutcome -> ToolExecutionResult(
                    "Edited $path (${outcome.replacements} replacement(s), ${outcome.newContent.length} bytes)",
                    true, toolTitle = toolTitle,
                )
                else -> ToolExecutionResult("Error: unexpected outcome", false, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error editing file: ${e.message}", false)
        }
    }

    /**
     * Successful replacement payload. Implements [HasPersistableText]
     * so readModifyWrite actually writes [newContent] to disk — previously this
     * fell through to the no-write branch and file_edit reported success
     * without changing the file.
     */
    internal class ReplaceOutcome(
        val newContent: String,
        val replacements: Int,
    ) : HasPersistableText {
        override fun persistableText(): String? = newContent
    }

    /**
     * Failure marker — carries only an error message. [HasPersistableText]
     * returns null so readModifyWrite does NOT touch the file. Previously the
     * failure path returned a bare String which readModifyWrite interpreted as
     * new content and wrote over the user's file.
     */
    internal class EditFailure(
        val message: String,
    ) : HasPersistableText {
        override fun persistableText(): String? = null
    }
}