package com.openminis.app.browser

/**
 * Pure helpers for the minis:// interception path of [BrowserUseManager]: the
 * CORS policy for cross-origin reads of local files, viewport meta splicing
 * for served HTML, and MIME-type guessing. None of them touch manager state.
 * Moved verbatim from BrowserUseManager (private members became internal
 * top-level functions) so that file stays inside its architecture-policy
 * line ceiling; pure move, same bodies, same [T-...] tags.
 */

/**
 * [T-android-minis-cors-scope] Which requester may read a local file across
 * origins. `Access-Control-Allow-Origin: *` on every intercepted file meant
 * ANY page the agent navigates to could `fetch("minis://workspace/…")` and
 * read the bytes back — session workspaces hold downloaded user files and
 * staged tool output, so a drive-by page turned the agent's own browser into
 * an exfiltration channel.
 *
 * A web page (http/https origin) gets no CORS header at all: its main-frame
 * navigation still works, its cross-origin READ of local bytes does not.
 * Same-scheme callers (a `minis://` workspace app fetching its own assets)
 * get their exact origin echoed back, and a request with no Origin header
 * (top-level navigation) needs nothing.
 */
internal fun corsHeadersFor(origin: String?): Map<String, String> = when {
    origin.isNullOrBlank() -> emptyMap()
    origin.startsWith("http://", true) || origin.startsWith("https://", true) -> emptyMap()
    else -> mapOf("Access-Control-Allow-Origin" to origin)
}

/**
 * If the HTML lacks a `<meta name="viewport">`, splice one in matching
 * the agent's CSS-px viewport. Leaves pages that already declare a
 * viewport untouched so author intent (e.g. `width=1200`) wins.
 */
internal fun ensureMetaViewport(html: ByteArray, cssWidth: Int): java.io.InputStream {
    val text = String(html, Charsets.UTF_8)
    if (text.contains("name=\"viewport\"", ignoreCase = true) ||
        text.contains("name='viewport'", ignoreCase = true)) {
        return text.toByteArray(Charsets.UTF_8).inputStream()
    }
    // user-scalable=yes is the default but state it explicitly so a
    // future change to WebView defaults can't silently disable
    // pinch-zoom on the agent's auto-injected viewport.
    val meta = "<meta name=\"viewport\" content=\"width=$cssWidth, initial-scale=1.0, user-scalable=yes\">"
    val headIdx = text.indexOf("<head", ignoreCase = true).takeIf { it >= 0 }?.let {
        text.indexOf('>', it).takeIf { gt -> gt >= 0 }?.plus(1)
    }
    val rewritten = if (headIdx != null) {
        text.substring(0, headIdx) + meta + text.substring(headIdx)
    } else {
        // No <head>: prepend the meta so it's still parsed before body.
        "$meta$text"
    }
    return rewritten.toByteArray(Charsets.UTF_8).inputStream()
}

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
