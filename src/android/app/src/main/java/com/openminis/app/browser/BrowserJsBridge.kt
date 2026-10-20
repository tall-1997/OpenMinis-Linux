package com.openminis.app.browser

import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/**
 * The `__minis__` JavaScript bridge exposed to every agent browser page.
 *
 * Extracted from [BrowserUseManager] to keep that file under its architecture
 * ceiling; behaviour is unchanged. Two channels:
 *
 *  - async-JS results ([resolve]/[reject]) complete the deferred that
 *    `executeJS` is awaiting;
 *  - blob downloads ([saveBlobDownload]) decode a `data:` URL that the page's
 *    own FileReader produced, because a `blob:` object URL only exists inside
 *    the page and native code cannot fetch it.
 *
 * Every callback arrives on the WebView's JavaBridge thread: file I/O
 * downstream is fine, touching the WebView from there is not.
 */
internal class BrowserJsBridge(
    /** The async-JS deferred to complete, read at callback time (null = idle). */
    private val pendingDeferred: () -> CompletableDeferred<String>?,
    /** Delivers decoded blob bytes into the download pipeline. */
    private val onBlobDownloadData: (data: ByteArray, filename: String, mimeType: String?) -> Unit,
) {
    @JavascriptInterface
    fun resolve(result: String) {
        pendingDeferred()?.complete(result)
    }

    @JavascriptInterface
    fun reject(error: String) {
        pendingDeferred()?.complete("{\"error\":${JSONObject.quote(error)}}")
    }

    @JavascriptInterface
    fun saveBlobDownload(dataUrl: String, filename: String) {
        val comma = dataUrl.indexOf(',')
        if (comma < 0 || !dataUrl.startsWith("data:")) {
            Log.w(TAG, "blob download: malformed data URL (len=${dataUrl.length})")
            return
        }
        val header = dataUrl.substring(5, comma)
        val mime = header.substringBefore(';').ifEmpty { null }
        val bytes = try {
            if (header.endsWith(";base64")) {
                android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
            } else {
                // Non-base64 data: URL — payload is percent-encoded text.
                java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8")
                    .toByteArray(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "blob download: payload decode failed: ${t.message}")
            return
        }
        Log.i(TAG, "blob download decoded: $filename (${bytes.size} bytes, mime=$mime)")
        onBlobDownloadData(bytes, filename, mime)
    }

    @JavascriptInterface
    fun blobDownloadError(error: String) {
        Log.w(TAG, "blob download failed in page JS: $error")
    }

    private companion object {
        const val TAG = "BrowserJsBridge"
    }
}
