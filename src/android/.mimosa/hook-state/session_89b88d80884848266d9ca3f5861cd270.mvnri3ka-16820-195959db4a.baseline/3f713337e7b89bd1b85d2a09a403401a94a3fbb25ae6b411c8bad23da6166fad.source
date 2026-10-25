package com.openminis.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** On-device OCR. The recognized text is returned to the model; the image itself is not resent. */
object OcrTool {
    const val NAME = "ocr_image"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read text out of a local image with on-device OCR (Chinese, Japanese, Korean, and Latin). " +
            "Use this when the user wants the literal text, not a description. " +
            "path is a sandbox path such as /var/minis/... or an absolute host path.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "path" to AgentToolParam("string", "Image path to recognize."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path"),
    )

    fun execute(argsJson: String, sessionId: String?, context: Context?): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("invalid arguments", false, toolTitle = NAME)
        }
        val title = args.optString("tool_title", NAME)
        val rawPath = args.optString("path").trim()
        if (rawPath.isEmpty()) return ToolExecutionResult("path is required", false, toolTitle = title)
        val path = if (rawPath.startsWith("minis://")) {
            "/var/minis/" + java.net.URLDecoder.decode(rawPath.removePrefix("minis://"), "UTF-8")
        } else {
            rawPath
        }
        val file = if (sessionId != null && context != null) {
            PRootKernel.resolveSessionHostPath(sessionId, path, context)
        } else {
            PRootKernel.resolveHostPath(path)
        } ?: return ToolExecutionResult("Cannot resolve path: $path", false, toolTitle = title)
        if (!file.isFile) return ToolExecutionResult("File not found: $path", false, toolTitle = title)
        val bitmap = decodeForOcr(file.absolutePath)
            ?: return ToolExecutionResult("Cannot decode image: $path", false, toolTitle = title)
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val text = try {
                Tasks.await(recognizer.process(image), 20, TimeUnit.SECONDS).text.orEmpty().trim()
            } finally {
                recognizer.close()
            }
            if (text.isEmpty()) {
                ToolExecutionResult("OCR found no text in $path", true, toolTitle = title)
            } else {
                ToolExecutionResult("OCR text from $path:\n$text", true, toolTitle = title)
            }
        } catch (e: Exception) {
            ToolExecutionResult("OCR failed: ${e.message ?: e.javaClass.simpleName}", false, toolTitle = title)
        } finally {
            bitmap.recycle()
        }
    }
}

    /** Longest edge 1600px. Full-resolution camera shots OOM the recognizer. */
    internal fun decodeForOcr(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxEdge = 1600
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return BitmapFactory.decodeFile(path, opts)
    }

