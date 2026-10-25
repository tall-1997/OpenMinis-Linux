package com.openminis.app.browser

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 文件/网页预览用的 WebView 钩子，一次装齐。
 *
 * 单独成文件的两个原因：
 *  1. FilePreviewScreen 已顶到架构门禁的行数 ceiling，新增逻辑必须外置；
 *  2. 这两个钩子必须成对出现才有意义 —— 只装 JS 弹窗不装崩溃保护，预览一个
 *     重型页面仍然会把整个应用拖死；只装崩溃保护不装 JS 弹窗，页面里的
 *     `confirm()` 永远拿不到结果，看起来像卡住。
 */
object WebViewPreviewHooks {

    /**
     * 给预览 WebView 装上渲染进程崩溃保护与原生 JS 弹窗。
     *
     * 调用方模板的两处点击（HTML 预览、独立网页预览）都走这一个入口，避免
     * 两处各写一份、日后只改其中一处。
     */
    fun install(webView: WebView, host: Context) {
        installCrashGuard(webView)
        webView.webChromeClient = PreviewJsDialogs.chromeClient(host)
    }

    /**
     * 只装崩溃保护。给禁用 JS 的打印用 WebView 用 —— 它不需要 JS 弹窗，
     * 但渲染进程照样可能 OOM。
     */
    fun installCrashGuard(webView: WebView) {
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail?,
            ): Boolean = WebViewRenderCrashGuard.handle(view, detail)
        }
    }
}
