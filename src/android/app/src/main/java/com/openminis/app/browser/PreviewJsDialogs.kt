package com.openminis.app.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout

/**
 * [T-web-preview-js-dialogs] 网页预览的原生 JS 弹窗。
 *
 * 现象：预览一个本地 HTML 时，页面里的 `alert()` / `confirm()` / `prompt()`
 * 毫无反应；依赖 `confirm()` 的确认流程永远拿不到结果，看起来像页面卡死。
 *
 * 根因：预览侧的 WebView **从未设置过 WebChromeClient**。没有它，WebView 用
 * 默认实现静默吞掉这些回调 —— 不弹窗、不返回、JS 执行停在原地。agent 浏览器
 * （BrowserUseManager）有自己的 client，但那套是给无人值守自动化用的保守策略
 * （alert=true / confirm=false / prompt=null），不适合给人看的预览页。
 *
 * 这里覆盖三个回调，用真正的系统对话框把结果显示给用户、把用户的选择交回给
 * 页面。找不到 Activity 时安全降级为"立即应答"，绝不把页面卡死。
 */
internal object PreviewJsDialogs {

    fun chromeClient(host: Context): WebChromeClient = object : WebChromeClient() {

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            val activity = host.findActivity()
            if (activity == null) {
                result?.confirm()
                return true
            }
            AlertDialog.Builder(activity)
                .setMessage(message.orEmpty())
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            val activity = host.findActivity()
            if (activity == null) {
                result?.cancel()
                return true
            }
            AlertDialog.Builder(activity)
                .setMessage(message.orEmpty())
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean {
            val activity = host.findActivity()
            if (activity == null) {
                result?.cancel()
                return true
            }
            val input = EditText(activity).apply {
                setText(defaultValue.orEmpty())
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setSelection(text.length)
            }
            // 留出边距，否则输入框会紧贴对话框边缘。
            val pad = (activity.resources.displayMetrics.density * 20).toInt()
            val container = FrameLayout(activity).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            AlertDialog.Builder(activity)
                .setMessage(message.orEmpty())
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }
    }

    /** Compose/预览的 Context 常是包装过的，逐层解包直到拿到 Activity。 */
    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
