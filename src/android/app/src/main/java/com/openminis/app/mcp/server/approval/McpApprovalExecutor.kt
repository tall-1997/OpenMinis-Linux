package com.openminis.app.mcp.server.approval

import com.openminis.app.mcp.server.McpServerCore
import com.openminis.app.mcp.server.approval.McpApprovalCore.ErrorCode
import com.openminis.app.mcp.server.approval.McpApprovalCore.MAX_ARGUMENT_CHARS
import com.openminis.app.mcp.server.approval.McpApprovalCore.MAX_OUTPUT_CHARS
import com.openminis.app.mcp.server.approval.McpApprovalCore.MAX_RUN_SECONDS
import com.openminis.app.mcp.server.approval.McpApprovalCore.Operation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * [T-mcp-approval] Execution half of the approval flow. Pure JVM logic: it
 * only talks to a [McpServerCore.ToolDispatcher] (injectable) and the
 * filesystem, so every branch is covered by plain JVM tests.
 *
 * Responsibilities:
 *  - source-file binding: an operation approved with a source hash may only
 *    run against a file whose content hashes to that value;
 *  - timeout clamp: shell tools handed to the dispatcher can never run longer
 *    than [MAX_RUN_SECONDS];
 *  - artifacts: arguments, dispatcher result, and flattened output land in the
 *    task directory (≤ [McpApprovalCore.MAX_TASK_FILE_BYTES] per file).
 */
class McpApprovalExecutor(
    private val dispatcher: McpServerCore.ToolDispatcher,
) {

    /** Execute-time evidence for the source-file binding. */
    data class SourceContext(
        val sourceFileHash: String? = null,
        val sourceFilePath: String? = null,
    )

    data class ExecResult(
        val callResult: McpServerCore.CallResult? = null,
        val errorCode: ErrorCode? = null,
        val errorMessage: String? = null,
    ) {
        val ok: Boolean get() = errorCode == null

        companion object {
            fun ofError(code: ErrorCode, message: String): ExecResult =
                ExecResult(errorCode = code, errorMessage = message)
        }
    }

    /**
     * Verify the source binding for [op]. Returns an ok result when no binding
     * exists or the evidence matches; an error result otherwise.
     */
    fun checkSource(op: Operation, ctx: SourceContext): ExecResult {
        val bound = op.sourceFileHash ?: return ExecResult()
        val presentedHash = ctx.sourceFileHash
        if (!presentedHash.isNullOrBlank()) {
            return if (presentedHash.equals(bound, ignoreCase = true)) ExecResult()
            else ExecResult.ofError(ErrorCode.REQUEST_ID_MISMATCH, "sourceFileHash does not match the approval binding")
        }
        val path = ctx.sourceFilePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (!file.isFile()) {
                return ExecResult.ofError(ErrorCode.REQUEST_ID_MISMATCH, "sourceFilePath is not a readable file")
            }
            // Cap the hashed size: source evidence files are hashes of small
            // scripts, never giant blobs.
            if (file.length() > MAX_SOURCE_FILE_BYTES) {
                return ExecResult.ofError(ErrorCode.ARGUMENTS_TOO_LARGE, "source file exceeds $MAX_SOURCE_FILE_BYTES bytes")
            }
            val actual = sha256Hex(file)
            return if (actual.equals(bound, ignoreCase = true)) ExecResult()
            else ExecResult.ofError(ErrorCode.REQUEST_ID_MISMATCH, "source file content does not match the approval binding")
        }
        return ExecResult.ofError(ErrorCode.REQUEST_ID_MISMATCH, "approval binds a source hash but no source evidence was presented")
    }

    /**
     * Run the operation through the dispatcher and write artifacts into
     * [taskRoot]. Never throws: dispatcher failures become error results.
     */
    fun execute(op: Operation, taskRoot: String, ctx: SourceContext): ExecResult {
        checkSource(op, ctx).let { if (!it.ok) return it }

        val arguments = runCatching { JSONObject(op.argumentsJson) }.getOrNull()
            ?: return ExecResult.ofError(ErrorCode.ARGUMENTS_TOO_LARGE, "arguments are not valid JSON")

        // Timeout clamp: the executor owns the run ceiling. Applies to any
        // tool schema that carries a timeout_sec field.
        if (arguments.has("timeout_sec")) {
            arguments.put("timeout_sec", arguments.optInt("timeout_sec").coerceAtMost(MAX_RUN_SECONDS))
        }

        val callResult = runCatching { dispatcher.callTool(op.tool, arguments) }.getOrElse {
            return ExecResult.ofError(ErrorCode.TASK_NOT_ACTIVE, "tool execution failed: ${it.message}")
        }

        runCatching { writeArtifacts(taskRoot, op, callResult) }
        return ExecResult(callResult = callResult)
    }

    // -- artifacts -----------------------------------------------------------

    private fun writeArtifacts(taskRoot: String, op: Operation, result: McpServerCore.CallResult) {
        val dir = File(taskRoot)
        if (!dir.exists()) dir.mkdirs()
        writeLimited(File(dir, "arguments.json"), op.argumentsJson)
        writeLimited(
            File(dir, "result.json"),
            JSONObject()
                .put("tool", op.tool)
                .put("isError", result.isError)
                .put("executedAtMs", System.currentTimeMillis())
                .put("content", result.content)
                .toString(),
        )
        val flat = flattenContent(result.content)
        if (flat.isNotBlank()) writeLimited(File(dir, "output.txt"), flat)
    }

    private fun flattenContent(content: JSONArray): String = buildString {
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            when (val type = item.optString("type", "text")) {
                "text" -> append(item.optString("text", "")).append('\n')
                "image" -> append("[image: ").append(item.optString("data", "").length).append(" bytes base64]\n")
                else -> append("[").append(type).append(" content omitted]\n")
            }
        }
    }.take(MAX_OUTPUT_CHARS.toInt())

    private fun writeLimited(file: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val bounded = if (bytes.size <= McpApprovalCore.MAX_TASK_FILE_BYTES) bytes
        else bytes.copyOf(McpApprovalCore.MAX_TASK_FILE_BYTES.toInt())
        file.writeBytes(bounded)
    }

    companion object {
        /** Upper bound for hashing a source-evidence file (64 MiB). */
        const val MAX_SOURCE_FILE_BYTES = 64L * 1024 * 1024

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
