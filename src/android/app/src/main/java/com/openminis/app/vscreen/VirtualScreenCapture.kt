package com.openminis.app.vscreen

import android.graphics.Bitmap
import android.media.Image
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * [T-android-vscreen] The layered screenshot PNG pipeline, split out of
 * [VirtualScreenManager] (architecture ratchet): pure functions over the
 * seams, touching NO manager-private state.
 *
 *  - [captureViaImageReader] — layer 1 (persistent reader, app-uid, no Shizuku).
 *  - [readChunked] — layer 3 (Shizuku screencap + chunked base64 through
 *    stdout); the shell is injected so JVM tests keep driving it.
 *  - [writePng] — terminal write into the offloads dir, returning the
 *    manager's [VirtualScreenManager.CommandOutcome.Ok] with the guest path.
 *
 * [imageToPng] is the shared Image → PNG codec.
 */
internal object VirtualScreenCapture {
    private const val TAG = "VirtualScreenCapture"

    /** base64 of one chunk stays inside the 64KB BoundedOutputBuffer head cap. */
    private const val CHUNK_BYTES = 48_000

    internal const val PNG_PREFIX = "vscreen_"
    internal const val PNG_SUFFIX = ".png"

    /**
     * Layer 1. Reads the latest frame from the display's OWN persistent
     * ImageReader (attached at create — the [VirtualScreenManager] surface
     * contract, never detached) and converts it to PNG. No surface churn:
     * the display keeps input focus across captures. Returns null when no
     * frame is available yet (fresh display, app not drawn), so the caller
     * falls through to the screencap layers.
     */
    fun captureViaImageReader(handle: VirtualScreenManager.DisplayHandle?): ByteArray? {
        if (handle == null) return null
        val img = handle.acquireLatestFrame() ?: return null
        return try {
            imageToPng(img)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ImageReader capture failed: ${t.message}")
            null
        } finally {
            runCatching { img.close() }
        }
    }

    /**
     * Layer 3. Reads [tmp] in [CHUNK_BYTES] pieces — each chunk's base64 stays
     * inside the 64KB BoundedOutputBuffer head cap — and decodes the
     * concatenation. Returns null on the first failed chunk (the caller
     * reports honestly instead of handing back a corrupt PNG). [shell] is the
     * manager's current shell seam (override-aware).
     */
    fun readChunked(
        tmp: String,
        displayId: Int,
        shell: (Array<String>, Long) -> ShizukuManager.ProcessResult,
    ): ByteArray? {
        val size = shell(arrayOf("sh", "-c", "wc -c < $tmp"), 5_000L)
        val total = size.stdout.trim().toIntOrNull()
        if (total == null || total <= 0) {
            AppLogger.warning(TAG, "screencap output for display $displayId unreadable: ${size.combined.ifBlank { "no size" }}")
            return null
        }
        val b64 = StringBuilder()
        var offset = 1 // tail -c +1 is 1-based
        while (offset <= total) {
            val chunk = shell(
                arrayOf("sh", "-c", "tail -c +$offset $tmp | head -c $CHUNK_BYTES | base64"),
                10_000L,
            )
            if (chunk.exitCode != 0) return null
            b64.append(chunk.stdout.filter { !it.isWhitespace() })
            offset += CHUNK_BYTES
        }
        return runCatching { Base64.getDecoder().decode(b64.toString()) }.getOrNull()
    }

    /** Writes the capture under [dir] and reports the guest path (or the failure). */
    fun writePng(dir: File, displayId: Int, bytes: ByteArray, via: String): VirtualScreenManager.CommandOutcome {
        val file = File(dir, "$PNG_PREFIX${displayId}_${System.currentTimeMillis()}$PNG_SUFFIX")
        return runCatching { file.writeBytes(bytes) }
            .map {
                VirtualScreenManager.CommandOutcome.Ok(
                    displayId,
                    "screenshot ${bytes.size} bytes via $via",
                    VirtualScreenManager.guestPathFor(file.name),
                )
            }
            .getOrElse {
                VirtualScreenManager.CommandOutcome.Failed("could not write the screenshot file: ${it.message}")
            }
    }

    private fun imageToPng(img: Image): ByteArray? = runCatching {
        val plane = img.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val rowStridePx = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(rowStridePx, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        val scaled = if (full.width == img.width) {
            full
        } else {
            Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        scaled.recycle()
        out.toByteArray()
    }.getOrNull()
}
