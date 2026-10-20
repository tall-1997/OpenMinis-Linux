package com.openminis.app.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.WebView

/**
 * [T-android-browser-gpu-capture] Captures a WebView bitmap.
 *
 * Why this exists: a detached (pool-owned, never added to a window) WebView
 * renders its content via the GPU compositor. `View.draw(Canvas)` with a
 * software bitmap bypasses that compositor, so GPU-only layers — WebGL
 * canvases, `<video>` frames, hardware-accelerated CSS animations — may come
 * out blank or stale.
 *
 * `PixelCopy` (API 26+) reads back the composited frame from SurfaceFlinger,
 * but it only accepts a [android.view.Surface], [android.view.SurfaceView],
 * or [android.view.Window] — there is **no `View`/`WebView` overload** in any
 * API level. A detached headless WebView has none of those reachable, so the
 * GPU path is structurally unavailable here.
 *
 * We therefore capture via the software [Canvas] path, forcing a hardware
 * layer first so the WebView's internal renderer composites into a bitmap
 * that `draw(Canvas)` can then read. This is the best available approach for
 * a detached WebView and matches what Chromium's own headless capture does
 * when no surface is present.
 *
 * Thread safety: all calls happen on the thread that owns the WebView (the
 * main thread). The caller already wraps this in
 * `withContext(Dispatchers.Main)`.
 */
object HeadlessRenderHost {

    private const val TAG = "HeadlessRenderHost"

    /**
     * Capture [webView] at [width]x[height] physical pixels. Returns null on
     * failure — the caller should then use its own software draw fallback.
     */
    suspend fun capture(webView: WebView, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return try {
            captureViaCanvas(webView, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "capture failed: ${e.message}")
            null
        }
    }

    /**
     * Software-canvas capture with a forced hardware layer.
     *
     * Setting [View.LAYER_TYPE_HARDWARE] makes the WebView composite its
     * GPU layers into an offscreen hardware buffer; `draw(Canvas)` then
     * reads that buffer into the software bitmap. Without the hardware layer
     * a detached WebView's GPU-only layers (WebGL, video) are skipped.
     */
    private fun captureViaCanvas(webView: WebView, width: Int, height: Int): Bitmap? {
        val prevLayerType = webView.layerType
        if (prevLayerType != View.LAYER_TYPE_HARDWARE) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        try {
            // Ensure the WebView has a non-zero layout.
            if (webView.width <= 0 || webView.height <= 0) {
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, width, height)
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            return bitmap
        } finally {
            if (prevLayerType != View.LAYER_TYPE_HARDWARE) {
                webView.setLayerType(prevLayerType, null)
            }
        }
    }
}
