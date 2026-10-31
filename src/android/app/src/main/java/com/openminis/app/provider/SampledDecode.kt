package com.openminis.app.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.InputStream

/**
 * [T-android-canvas-large-bitmap-crash-followup] Sampled two-pass decoding for
 * every path that decodes an UNTRUSTED image (user attachment, tool result,
 * file preview, gateway screenshot).
 *
 * Why a shared decoder: the first fix for the Canvas crash
 * ([com.openminis.app.ui.DisplayBitmapLimits]) only guarded the Coil path used
 * by markdown image blocks. Every non-Coil path still ran a raw
 * `BitmapFactory.decodeFile/decodeStream/decodeByteArray` and handed the
 * FULL-RESOLUTION bitmap to `Image(bitmap=…)`. Compose draws that bitmap at
 * its native allocation size, so `RecordingCanvas.throwIfCannotDraw` throws
 * for anything above ~100MB REGARDLESS of how small the visual layout is:
 *
 *     java.lang.RuntimeException: Canvas: trying to draw too large(322109440bytes) bitmap.
 *         at android.graphics.RecordingCanvas.throwIfCannotDraw
 *
 * 322,109,440 bytes / 4 (ARGB_8888) = 80.5 Mpx, e.g. an 8192x9830 photo a user
 * attached; `read_image` decoded it raw and the chat tool block crashed the
 * process — twice in four minutes (2026-10-31, Redmi myron).
 *
 * Two-pass shape mirrors ImageBudget.compressBytes (which this object now
 * backs): bounds-only first, power-of-two inSampleSize second, exact-scale
 * last — so the giant bitmap is NEVER allocated. A decode failure returns
 * null; callers show their existing error placeholder instead of crashing.
 *
 * This object is display-agnostic: [com.openminis.app.ui.DisplayBitmapLimits]
 * pins it to its display ceiling, and [ImageBudget] to its model ceiling.
 */
object SampledDecode {

    private const val TAG = "SampledDecode"

    /**
     * Power-of-two sample size for a [width]x[height] image whose decoded
     * bitmap must not exceed [maxEdge] on its longest edge by more than 2x
     * (the exact-scale pass in [decode] lands it precisely on [maxEdge]).
     * Returns 1 when the image already fits.
     */
    fun computeSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (width / sample > maxEdge * 2 || height / sample > maxEdge * 2) {
            sample *= 2
        }
        return sample
    }

    /**
     * Decode via [decoder] (bound to a file, byte array, or stream) and
     * return a bitmap whose longest edge is at most [maxEdge]. Never throws;
     * returns null on decode failure or unreadable dimensions.
     */
    fun decode(decoder: (BitmapFactory.Options) -> Bitmap?, maxEdge: Int): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                decoder(boundsOpts)
            } catch (_: Throwable) {
                return null
            }
            val w0 = boundsOpts.outWidth
            val h0 = boundsOpts.outHeight
            if (w0 <= 0 || h0 <= 0) return null
            val sample = computeSampleSize(w0, h0, maxEdge)
            val decoded = try {
                decoder(BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Throwable) {
                null
            } ?: return null
            val scale = minOf(
                maxEdge.toFloat() / decoded.width,
                maxEdge.toFloat() / decoded.height,
                1f,
            )
            if (scale >= 1f) {
                decoded
            } else {
                try {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true,
                    ).also { decoded.recycle() }
                } catch (_: Throwable) {
                    decoded
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "decode failed (maxEdge=$maxEdge): ${t.message}")
            null
        }
    }

    fun decodeFile(path: String, maxEdge: Int): Bitmap? =
        decode({ opts -> BitmapFactory.decodeFile(path, opts) }, maxEdge)

    fun decodeBytes(data: ByteArray, maxEdge: Int): Bitmap? =
        decode({ opts -> BitmapFactory.decodeByteArray(data, 0, data.size, opts) }, maxEdge)

    fun decodeStream(input: InputStream, maxEdge: Int): Bitmap? =
        decode({ opts -> BitmapFactory.decodeStream(input, null, opts) }, maxEdge)

    /**
     * Read intrinsic dimensions without decoding pixels. Returns null when
     * the source is not decodable — callers fall back to the decoded bitmap's
     * dimensions for metadata display.
     */
    fun readBounds(decoder: (BitmapFactory.Options) -> Bitmap?): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                decoder(opts)
            } catch (_: Throwable) {
                return null
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) null
            else opts.outWidth to opts.outHeight
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "readBounds failed: ${t.message}")
            null
        }
    }

    fun readBoundsFile(path: String): Pair<Int, Int>? = readBounds { BitmapFactory.decodeFile(path, it) }

    fun readBoundsBytes(data: ByteArray): Pair<Int, Int>? =
        readBounds { BitmapFactory.decodeByteArray(data, 0, data.size, it) }

    /** Sampled decode plus the source's intrinsic size in one call. */
    fun decodeFileWithSourceSize(path: String, maxEdge: Int): Pair<Bitmap?, Pair<Int, Int>?> {
        val size = readBoundsFile(path)
        return decodeFile(path, maxEdge) to size
    }
}
