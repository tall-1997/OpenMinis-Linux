package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.provider.ImageBudget
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object ReadImageTool {
    const val NAME = "read_image"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read an image file from the Linux filesystem and return it for visual analysis. Supports PNG, JPEG, GIF, WEBP, and other common image formats. Use this to inspect generated charts, downloaded images, screenshots, or any visual output. If you natively support vision the image is returned directly for your analysis; if you do not, it is routed through a configured Vision Group that returns a text description — in that case pass a `prompt` to focus the description on what you actually need (you cannot see the pixels yourself, so this is how you 'ask' about the image). Metadata (dimensions, file size) is always included.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'View generated bar chart', 'Inspect downloaded screenshot'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Linux path (e.g. /var/minis/attachments/chart.png) or minis:// URL (e.g. minis://attachments/chart.png)"),
            "prompt" to AgentToolParam("string", "Optional. A custom instruction describing what you want to understand from the image (e.g. 'transcribe the table text', 'describe the people and their expressions', 'what error message is in this screenshot'). Most useful when you lack native vision and the image is described by a Vision Group — it steers that description toward your question. If omitted, a generic 'describe this image in detail' instruction is used."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "prompt"),
    )

    /**
     * T178: when the caller knows the owning session, prefer
     * [PRootKernel.resolveSessionHostPath] so per-session subdirs
     * (`/var/minis/{attachments,workspace,offloads,browser}/...`) resolve
     * directly against this session's host dir instead of consulting the
     * global, last-writer-wins `bindMounts` map. Without this, an agent
     * loop in session A that calls `read_image` after session B booted
     * its PRoot reads from session B's host dir — confirmed leak per
     * docs/parity/cross-session-isolation-audit.md.
     *
     * The legacy single-arg overload is preserved for callers that don't
     * know the session id (and falls back to the global map). Mirror iOS
     * `ReadImageTool` which carries `sessionId` through its tool-call
     * pipeline.
     */
    fun execute(argsJson: String, sessionId: String? = null, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)

            if (rawPath.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            val path = if (rawPath.startsWith("minis://")) {
                "/var/minis/" + java.net.URLDecoder.decode(rawPath.removePrefix("minis://"), "UTF-8")
            } else rawPath

            val file = (
                if (sessionId != null && context != null) {
                    PRootKernel.resolveSessionHostPath(sessionId, path, context)
                } else null
            ) ?: PRootKernel.resolveHostPath(path)
                ?: return ToolExecutionResult(failureText(path, resolved = false, file = null), false, toolTitle = toolTitle)

            if (!file.exists() || file.isDirectory) {
                return ToolExecutionResult(failureText(path, resolved = true, file = file), false, toolTitle = toolTitle)
            }

            imageResult(file, path, toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading image: ${e.message}", false)
        }
    }

    /**
     * [T-readimage-fail-visible] The tool result (and card text) for a
     * read_image that could not load its file, saying WHICH failure it was
     * (iOS readImageFailureText, 1eb3e2bef). The old "File not found" /
     * "Cannot decode image" gave no size and no hint, and sent models off
     * guessing at caches and delays when the file simply did not exist yet.
     * [file] is the resolved host file (null when [resolved] is false).
     *
     * Differences from iOS, as on Windows: the format list is what
     * BitmapFactory decodes here (HEIF only from Android 9, no TIFF), and a
     * folder gets its own line instead of "not a decodable image".
     */
    internal fun failureText(
        path: String,
        resolved: Boolean,
        file: java.io.File?,
        heif: Boolean = android.os.Build.VERSION.SDK_INT >= 28,
    ): String {
        if (!resolved || file == null) {
            return "Error: '$path' is not a readable Minis Ultra path. Use a path under /var/minis/ (e.g. /var/minis/workspace/chart.png) or a minis:// URL."
        }
        if (!file.exists()) {
            return "Error: No file at '$path'. If a command in this same turn creates it, read it in the next turn after that command has finished."
        }
        if (file.isDirectory) return "Error: '$path' is a folder, not an image file."
        val size = file.length()
        if (size == 0L) return "Error: '$path' exists but is empty (0 bytes) - it may still be being written."
        val formats = if (heif) "PNG, JPEG, GIF, WebP, BMP, HEIF" else "PNG, JPEG, GIF, WebP, BMP"
        return "Error: '$path' exists ($size bytes) but is not a decodable image. Supported: $formats."
    }

    /**
     * [T-image-real-tests] The "file → model image" half of [execute], split
     * from path resolution so it can be called on a plain file. Unchanged:
     * full decode, floor-fit within 2000 px, JPEG q85, metadata with the
     * original pixel size. Graphics calls go through [ImageBudget.codec].
     * Exceptions propagate to [execute]'s catch, as before.
     */
    internal fun imageResult(file: java.io.File, path: String, toolTitle: String): ToolExecutionResult {
            val codec = ImageBudget.codec
            val original = codec.decodeFile(file.absolutePath)
                ?: return ToolExecutionResult(failureText(path, resolved = true, file = file), false, toolTitle = toolTitle)

            val maxEdge = 2000
            val scaled = if (codec.width(original) > maxEdge || codec.height(original) > maxEdge) {
                val scale = maxEdge.toFloat() / maxOf(codec.width(original), codec.height(original))
                val w = (codec.width(original) * scale).toInt()
                val h = (codec.height(original) * scale).toInt()
                codec.scale(original, w, h)
            } else {
                original
            }

            val out = ByteArrayOutputStream()
            codec.compress(scaled, png = false, quality = 85, out = out)
            val imageBytes = out.toByteArray()

            if (scaled !== original) codec.recycle(scaled)
            codec.recycle(original)

            val metadata = "[$path | ${codec.width(original)}x${codec.height(original)} | ${file.length()} bytes]"
            return ToolExecutionResult(
                output = metadata,
                success = true,
                imageData = imageBytes,
                imageMimeType = "image/jpeg",
                toolTitle = toolTitle,
                // Surface the source file for inline preview in the tool result UI
                // (mirrors iOS ToolLiveSheet.readImageTool case).
                imageFilePath = file.absolutePath,
            )
    }
}
