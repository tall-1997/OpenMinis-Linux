package com.openminis.app.browser

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.openminis.app.util.Format
import org.json.JSONObject

/**
 * [T-browser-fetch-ext] Page-context fetch + the shared Promise bridge,
 * split from BrowserUseManager (architecture ceiling). `webView`,
 * `asyncJsDeferred` and `jsCallSeq` stay manager-owned state — the
 * extensions run on the same receiver so the seq-keyed slot semantics are
 * unchanged.
 */
internal suspend fun BrowserUseManager.fetch(urlString: String?): BrowserActionResult {
        if (urlString.isNullOrEmpty()) return BrowserActionResult.error("fetch requires 'url' parameter")

        // The fetch JS runs `await fetch(...)` inside an async IIFE, which
        // resolves to a Promise. Android's `WebView.evaluateJavascript` does
        // NOT await Promises, so calling `evaluateJavascript(js)` returns the
        // Promise's `{}` string representation and the caller sees a
        // "No value for base64" parse error. Route through the __minis__
        // bridge so we actually wait for the Promise to resolve.
        val raw = awaitPromiseJs(BrowserUseJS.fetch(urlString))
            ?: return BrowserActionResult.error("fetch timed out")
        return try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                return BrowserActionResult.error("Fetch failed: ${json.getString("error")}")
            }
            val contentType = json.optString("contentType", "")
            val status = json.optInt("status", 0)
            val finalURL = json.optString("url", urlString)

            // `base64` is optional — only present when the JS captured bytes.
            // If missing, fall back to `text` (JSON / plain text) so callers
            // can fetch human-readable payloads without forcing a byte roundtrip.
            val data: ByteArray? = json.optString("base64").takeIf { it.isNotEmpty() }?.let {
                try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
            } ?: json.optString("text").takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

            if (data == null) {
                return BrowserActionResult.error("Fetch returned no body (status=$status)")
            }
            val size = json.optInt("size", data.size)
            val filename = "fetch_${System.currentTimeMillis()}.${extensionForMimeType(contentType)}"

            val text = buildString {
                appendLine("Fetched $finalURL")
                appendLine("  Status: $status")
                appendLine("  Content-Type: $contentType")
                appendLine("  Size: ${Format.formatBytes(size.toLong())}")
                append("  Filename: $filename")
            }

            BrowserActionResult(
                text = text,
                fetchedFileData = data,
                fetchedFileName = filename,
            )
        } catch (e: Exception) {
            BrowserActionResult.error("Fetch parse error: ${e.message}")
        }
    }

