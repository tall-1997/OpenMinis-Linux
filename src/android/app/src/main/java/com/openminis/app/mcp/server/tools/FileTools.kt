package com.openminis.app.mcp.server.tools

import com.openminis.app.mcp.server.McpServerCore
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/** [T-mcp-refactor] file_read / file_write — path-gated workspace file tools. */
internal fun fileTools(deps: McpToolDeps): List<McpToolSpec> = listOf(
    McpToolSpec(
        name = "file_read",
        description = McpToolSchemas.fileRead,
        schema = McpToolSchemas.fileReadSchema,
        handler = { fileRead(deps, it) },
    ),
    McpToolSpec(
        name = "file_write",
        description = McpToolSchemas.fileWrite,
        schema = McpToolSchemas.fileWriteSchema,
        handler = { fileWrite(deps, it) },
    ),
    McpToolSpec(
        name = "file_transfer_pull",
        description = McpToolSchemas.fileTransferPull,
        schema = McpToolSchemas.fileTransferPullSchema,
        handler = { fileTransferPull(deps, it) },
    ),
    McpToolSpec(
        name = "file_transfer_push",
        description = McpToolSchemas.fileTransferPush,
        schema = McpToolSchemas.fileTransferPushSchema,
        handler = { fileTransferPush(deps, it) },
    ),
)

/** Max bytes per transfer direction — base64 inflates by 4/3, so an 8 MB
 *  payload arrives as ~10.7 MB of JSON; keep both under control. */
private const val MAX_TRANSFER_BYTES = 8L * 1024 * 1024

/** [T-mcp-fileread-oom] Text reads above this size are refused instead of
 *  streamed or truncated: mirrors the agent-side FileReadTool's large-file
 *  guard (the same 50 MB constant), so both file_read surfaces answer a
 *  huge log the same way — with guidance — rather than silently reading it. */
private val MAX_STREAM_FILE_SIZE = com.openminis.app.tools.FileReadTool.MAX_STREAM_FILE_SIZE

/**
 * [T-mcp-fileread-oom] Bounded text read: at most [maxChars] chars are
 * decoded, the stream stops there, and the second value says whether more
 * followed. `File.readText()` decoded the WHOLE file first (full byte[] plus
 * String) only to throw away all but 80 KB — a multi-hundred-MB log OOMs the
 * app before the truncation ever runs. Memory here stays at the output cap,
 * never the file size.
 */
internal fun readTextBounded(file: File, charset: Charset, maxChars: Int): Pair<String, Boolean> {
    val chars = CharArray(maxChars + 1)
    var count = 0
    file.reader(charset).use { reader ->
        while (count < chars.size) {
            val n = reader.read(chars, count, chars.size - count)
            if (n < 0) break
            count += n
        }
    }
    val truncated = count > maxChars
    var outChars = minOf(count, maxChars)
    // Cutting between the two halves of a surrogate pair ends the output with
    // a lone high surrogate — a string JSON.stringify serialises to invalid
    // \uD8xx escape that strict clients reject wholesale. Drop the orphaned
    // half; the truncated flag still reports honestly (one char less came out).
    if (truncated && outChars > 0 && Character.isHighSurrogate(chars[outChars - 1])) {
        outChars--
    }
    return String(chars, 0, outChars) to truncated
}

/** Shared prologue: require 'path', gate it, normalize, resolve to a host
 *  file through the mcp-server session tree. Returns (file, null) or
 *  (null, errorResult). */
private fun resolveFileArg(
    deps: McpToolDeps,
    arguments: JSONObject,
): Pair<File?, McpServerCore.CallResult?> {
    val rawPath = arguments.optString("path", "").ifEmpty {
        return null to McpToolShared.error("Error: 'path' is required")
    }
    McpToolShared.validateFilePath(rawPath)?.let { reason ->
        return null to McpServerCore.CallResult(
            JSONArray().put(McpToolShared.text("Path denied: $reason")),
            isError = true,
            errorCode = McpServerCore.ErrorCode.PATH_DENIED,
            errorData = reason,
        )
    }
    val normalized = McpToolShared.normalizePath(rawPath)
    // Resolve the Linux path to a host file through the session workspace,
    // exactly like FileReadTool does — so /var/minis/workspace reads resolve
    // inside the mcp-server session tree, not some other chat's.
    val ctx = deps.context
        ?: return null to McpToolShared.error("Error: no host context available")
    val file = PRootKernel.resolveSessionHostPath(McpToolShared.SESSION_ID, normalized, ctx)
        ?: return null to McpToolShared.error(
            "Error: cannot resolve path '$normalized' (rootfs-only guest path?)"
        )
    return file to null
}

private fun fileRead(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val (file, resolveErr) = resolveFileArg(deps, arguments)
    resolveErr?.let { return it }
    val normalized = McpToolShared.normalizePath(arguments.optString("path", ""))
    if (!file!!.exists()) {
        return McpToolShared.error("Error: file not found: $normalized")
    }
    if (file.isDirectory) {
        return McpToolShared.error("Error: path is a directory: $normalized")
    }
    return runCatching {
        val size = file.length()
        // [T-binary-preview] shared with the agent-side file_read: a
        // binary is a type guess plus a bounded hex preview, not a dead
        // end; UTF-16 BOM text decodes instead of mislabelled binary.
        val head = com.openminis.app.tools.FileReadTool.readHead(file, minOf(8192L, size).toInt())
        val textCharset = com.openminis.app.tools.FileReadTool.utf16CharsetOrNull(head)
        val isBinary = textCharset == null && head.any { it == 0.toByte() }
        if (isBinary) {
            return McpServerCore.CallResult(
                JSONArray().put(
                    McpToolShared.text(
                        com.openminis.app.tools.FileReadTool.binaryPreview(normalized, size, head)
                    )
                ),
            )
        }
        // [T-mcp-fileread-oom] Refuse before streaming: a silent truncate would
        // hand the model the head of a 300 MB log as if it were the file. The
        // recoverable error points at the tools that CAN page through it.
        if (size > MAX_STREAM_FILE_SIZE) {
            return McpToolShared.error(
                "Error: file is $size bytes, exceeds the " +
                    "${MAX_STREAM_FILE_SIZE / (1024 * 1024)} MB file_read limit. " +
                    "Use shell_exec with head/tail/grep/split for portions of large files."
            )
        }
        val (raw, truncated) = readTextBounded(file, textCharset ?: Charsets.UTF_8, McpToolShared.MAX_FILE_READ_CHARS)
        val content = if (truncated) {
            raw + "\n... (truncated at ${McpToolShared.MAX_FILE_READ_CHARS} chars)"
        } else {
            raw
        }
        val header = "[$normalized | $size bytes]"
        McpServerCore.CallResult(JSONArray().put(McpToolShared.text("$header\n$content")))
    }.getOrElse {
        McpToolShared.error("Error reading file: ${it.message}")
    }
}

private fun fileWrite(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val content = arguments.optString("content", "")
    if (content.length > McpToolShared.MAX_FILE_WRITE_CHARS) {
        return McpToolShared.error(
            "Error: content exceeds ${McpToolShared.MAX_FILE_WRITE_CHARS} chars (got ${content.length})"
        )
    }
    val append = arguments.optBoolean("append", false)
    val (file, resolveErr) = resolveFileArg(deps, arguments)
    resolveErr?.let { return it }
    val normalized = McpToolShared.normalizePath(arguments.optString("path", ""))
    if (file!!.isDirectory) {
        return McpToolShared.error("Error: path is a directory: $normalized")
    }
    return runCatching {
        file.parentFile?.mkdirs()
        if (append) file.appendText(content) else file.writeText(content)
        McpServerCore.CallResult(
            JSONArray().put(
                McpToolShared.text(
                    "[wrote $normalized | ${content.length} chars | ${if (append) "appended" else "overwritten"}]"
                )
            ),
        )
    }.getOrElse {
        McpToolShared.error("Error writing file: ${it.message}")
    }
}

private fun fileTransferPull(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val (file, resolveErr) = resolveFileArg(deps, arguments)
    resolveErr?.let { return it }
    val normalized = McpToolShared.normalizePath(arguments.optString("path", ""))
    if (!file!!.exists()) {
        return McpToolShared.error("Error: file not found: $normalized")
    }
    if (file.isDirectory) {
        return McpToolShared.error("Error: path is a directory: $normalized")
    }
    if (file.length() > MAX_TRANSFER_BYTES) {
        return McpToolShared.error(
            "Error: file is ${file.length()} bytes, exceeds $MAX_TRANSFER_BYTES byte limit"
        )
    }
    return runCatching {
        // Stream + hash in chunks; no full-file byte[] and no one-shot base64.
        val encoded = FileTransferCodec.encode(file)
        McpServerCore.CallResult(
            JSONArray().put(
                McpToolShared.text(
                    "[path: $normalized | size: ${encoded.size} | sha256: ${encoded.sha256}]\n${encoded.base64}"
                )
            ),
        )
    }.getOrElse {
        McpToolShared.error("Error reading file: ${it.message}")
    }
}

private fun fileTransferPush(deps: McpToolDeps, arguments: JSONObject): McpServerCore.CallResult {
    val b64 = arguments.optString("content_base64", "")
    if (b64.isEmpty()) {
        return McpToolShared.error("Error: 'content_base64' is required")
    }
    // Reject before decode: base64 length maps 4:3 to bytes, so the byte
    // cap can be enforced on the encoded length without allocating.
    if (b64.length > (MAX_TRANSFER_BYTES * 4 / 3 + 4)) {
        return McpToolShared.error(
            "Error: content exceeds $MAX_TRANSFER_BYTES byte limit (base64 length ${b64.length})"
        )
    }
    val (file, resolveErr) = resolveFileArg(deps, arguments)
    resolveErr?.let { return it }
    val normalized = McpToolShared.normalizePath(arguments.optString("path", ""))
    if (file!!.isDirectory) {
        return McpToolShared.error("Error: path is a directory: $normalized")
    }
    return runCatching {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        if (bytes.size > MAX_TRANSFER_BYTES) {
            return McpToolShared.error(
                "Error: decoded content is ${bytes.size} bytes, exceeds $MAX_TRANSFER_BYTES limit"
            )
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        McpServerCore.CallResult(
            JSONArray().put(McpToolShared.text("[wrote $normalized | ${bytes.size} bytes | sha256: $sha]")),
        )
    }.getOrElse {
        McpToolShared.error("Error writing file: ${it.message}")
    }
}
