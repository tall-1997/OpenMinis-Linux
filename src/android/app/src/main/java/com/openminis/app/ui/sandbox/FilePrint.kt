package com.openminis.app.ui.sandbox

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 打印预览。独立成文件：FilePreviewScreen 已到架构门禁的行数 ceiling。
 */

/** 500 KB; markdown compose splits at BoundedText.MAX_COMPOSE_BLOCK_CHARS */
internal const val MAX_TEXT_PREVIEW_BYTES = 512_000

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
 * Print a previewable file via the Android print framework. HTML loads
 * directly; text-based files (markdown / plain text / json / csv) are wrapped
 * in an HTML `<pre>` block so a single `WebView.createPrintDocumentAdapter`
 * path covers every case. Mirrors iOS where every preview surface funnels
 * through one print controller.
 *
 * The off-screen WebView must outlive this function: print is async (we kick it
 * off only after `onPageFinished`), so we hold the instance in a captured var
 * and clear it once the adapter is handed to PrintManager.
 */
internal fun printFile(context: Context, item: FileItem) {
    try {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
        }
        // Keep a reference alive until the print job is dispatched.
        var holder: WebView? = webView
        com.openminis.app.browser.WebViewPreviewHooks.installCrashGuard(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val printManager =
                    context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = "${context.getString(R.string.app_name)} - ${item.name}"
                val adapter = view.createPrintDocumentAdapter(jobName)
                printManager.print(
                    jobName,
                    adapter,
                    PrintAttributes.Builder().build(),
                )
                holder = null
            }
        }
        if (item.isHtmlFile) {
            webView.loadUrl("file://${item.file.absolutePath}")
        } else {
            val raw = item.file.readBytes().let { bytes ->
                val cap = if (bytes.size > MAX_TEXT_PREVIEW_BYTES) MAX_TEXT_PREVIEW_BYTES else bytes.size
                String(bytes, 0, cap, Charsets.UTF_8)
            }
            val html = buildString {
                append("<html><head><meta charset=\"utf-8\">")
                append("<style>body{font-family:monospace;font-size:12px;white-space:pre-wrap;word-wrap:break-word;}</style>")
                append("</head><body><pre>")
                append(escapeHtml(raw))
                append("</pre></body></html>")
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        // Silence the unused-assignment warning while documenting intent: the
        // holder keeps `webView` reachable across the async page load.
        @Suppress("UNUSED_VALUE")
        holder = webView
    } catch (e: Exception) {
        AppLogger.warning("FilePreview", "print failed for ${item.name}: ${e.message}")
        Toast.makeText(context, context.getString(R.string.file_print_failed_toast, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}
