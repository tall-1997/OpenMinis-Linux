package com.openminis.app.vscreen

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [T-android-vscreen] The layered screenshot PNG pipeline, split out of
 * [VirtualScreenManager] (architecture ratchet): pure functions over the
 * seams, touching NO manager-private state.
 *
 *  - [captureViaImageReader] — layer 1 (ImageReader, app-uid, no Shizuku).
 *  - [readChunked] — layer 3 (Shizuku screencap + chunked base64 through
 *    stdout); the shell is injected so JVM tests keep driving it.
 *  - [writePng] — terminal write into the offloads dir, returning the
 *    manager's [VirtualScreenManager.CommandOutcome.Ok] with the guest path.
 *
 * [imageToPng] is the shared Image → PNG codec.
 */
internal object VirtualScreenCapture {
    private const val TAG = "VirtualScreenCapture"

    private const val FRAME_TIMEOUT_MS = 3_000L

    /** base64 of one chunk stays inside the 64KB BoundedOutputBuffer head cap. */
    private const val CHUNK_BYTES = 48_000

    internal const val PNG_PREFIX = "vscreen_"
    internal const val PNG_SUFFIX = ".png"

    /**
     * Layer 1. Attaches an ImageReader surface to our own display, awaits one
     * frame (bounded), reads it to a PNG and detaches — all app-uid. Returns
     * null on any failure (timeout, no frame, attach refused) so the caller
     * falls through to the screencap layers.
     */
    fun captureViaImageReader(handle: VirtualScreenManager.DisplayHandle?, width: Int, height: Int): ByteArray? {
        if (handle == null) return null
        var reader: ImageReader? = null
        var thread: HandlerThread? = null
        return try {
            val frame = AtomicReference<Image>(null)
            val latch = CountDownLatch(1)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            thread = HandlerThread("minis-vscreen-capture").also { it.start() }
            reader.setOnImageAvailableListener({ r ->
                if (frame.get() != null) return@setOnImageAvailableListener
                runCatching { r.acquireLatestImage() }.getOrNull()?.let {
                    frame.set(it)
                    latch.countDown()
                }
            }, Handler(thread.looper))
            if (!handle.attach(reader.surface)) return null
            latch.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            handle.detach()
            val img = frame.get() ?: return null
            val png = imageToPng(img)
            img.close()
            png
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ImageReader capture failed: ${t.message}")
            null
        } finally {
            runCatching { reader?.close() }
            runCatching { thread?.quitSafely() }
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
