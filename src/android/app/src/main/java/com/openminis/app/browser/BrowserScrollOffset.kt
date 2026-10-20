package com.openminis.app.browser

import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Reads the page's CSS scroll offset via JS so a software draw can translate
 * the canvas to match what the user sees. A detached WebView's `scrollY` is 0
 * even when the page is scrolled (the scroll lives in the renderer, not the
 * View), so relying on `webView.scrollY` alone produces a screenshot of the
 * top of the page regardless of where the user scrolled.
 */
internal object BrowserScrollOffset {
    private const val TAG = "BrowserScrollOffset"

    /**
     * Reads CSS scroll offset from the page. Returns (x, y) pair.
     * Falls back to (0, 0) on any error.
     */
    suspend fun read(webView: WebView): Pair<Float, Float> = withContext(Dispatchers.Main) {
        try {
            val raw = suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(
                    "JSON.stringify({sx:window.scrollX||0,sy:window.scrollY||0})",
                ) { result -> cont.resume(result) }
            }
            if (raw.isNullOrEmpty()) return@withContext 0f to 0f
            // evaluateJavascript returns a JSON string **wrapped in quotes**
            // (e.g. `"{\"sx\":0,\"sy\":120}"`) — strip the outer quotes before
            // manual field extraction to avoid pulling in org.json which has
            // stub-signature issues on the unit-test android.jar.
            val inner = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1).replace("\\\"", "\"")
            } else {
                raw
            }
            val sx = extractDouble(inner, "sx")
            val sy = extractDouble(inner, "sy")
            sx.toFloat() to sy.toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "read failed: ${e.message}")
            0f to 0f
        }
    }

    /** Extracts a numeric field value from a simple JSON string without org.json. */
    private fun extractDouble(json: String, field: String): Double {
        val key = "\"$field\":"
        val idx = json.indexOf(key)
        if (idx < 0) return 0.0
        val afterKey = json.substring(idx + key.length).trimStart()
        // Read until a non-numeric character (comma, brace, space).
        val end = afterKey.indexOfFirst { !it.isDigit() && it != '.' && it != '-' }
        val numStr = if (end < 0) afterKey else afterKey.substring(0, end)
        return numStr.toDoubleOrNull() ?: 0.0
    }
}
