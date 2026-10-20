package com.openminis.app.browser

/**
 * Result/URL formatting helpers for [BrowserTabPool], extracted so the pool
 * stays under its architecture ceiling. Pure functions — no pool state.
 */

/**
 * Stamp the tab id a result came from: append the human-readable
 * `tab_id: N` line the model uses for follow-up calls, and set [tabId] on
 * the structured side. Idempotent — a result that already names its tab is
 * not stamped twice.
 */
internal fun stampTabId(result: BrowserActionResult, targetId: Int): BrowserActionResult {
    val needsLine = !result.text.contains("tab_id:")
    val text = if (needsLine) {
        val needsNewline = result.text.isNotEmpty() && !result.text.endsWith("\n")
        result.text + (if (needsNewline) "\n" else "") + "  tab_id: $targetId"
    } else {
        result.text
    }
    return result.copy(text = text, tabId = targetId)
}

/**
 * Canonical form used to match "is this tab already showing that URL":
 * scheme://host/path with the scheme and host lowercased and the trailing
 * slash dropped. Query and fragment are ignored on purpose — the same page
 * with a different anchor is the same tab.
 */
internal fun normalizeUrlForMatch(raw: String): String {
    if (raw.isEmpty()) return ""
    return try {
        val uri = android.net.Uri.parse(raw)
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""
        val path = (uri.path ?: "").trimEnd('/')
        "$scheme://$host$path"
    } catch (_: Exception) {
        raw.trimEnd('/')
    }
}
