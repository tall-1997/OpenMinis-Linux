package com.openminis.app.browser

import android.graphics.Bitmap
import java.io.File

/**
 * Small file-ish helpers the agent browser needs, extracted from
 * [BrowserUseManager] to keep that file under its architecture ceiling.
 * Behaviour is unchanged.
 */

/** Content type for a local file the browser is about to hand back. */
internal fun guessMimeType(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "xml" -> "text/xml"
        else -> "application/octet-stream"
    }
}

/**
 * Encode [bitmap] as a JPEG under [dir] and return the file. Callers run this
 * on IO; a failed encode is a hard error, not an empty screenshot.
 */
internal fun saveScreenshotBitmap(
    bitmap: Bitmap,
    dir: File,
    prefix: String,
    quality: Int,
): File {
    val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Screenshot encoding failed" }
    }
    return file
}
