package com.openminis.app.browser

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * [T-webview-render-crash] 渲染进程死亡时不连坐杀应用的 WebViewClient 基类。
 *
 * 单独成文件：BrowserUseManager 已到架构门禁的行数 ceiling，一行都加不了。
 *
 * Android 的 WebView 渲染进程在 OOM / 沙箱崩溃时独立死掉。默认行为是**整个
 * 应用进程被系统一并杀死** —— 用户看到的是"用着用着闪退"，logcat 里只有一条
 * `Render process gone`。返回 true 是唯一能阻止这个连锁的途径：它告诉系统
 * "我已经处理了"。
 *
 * 处理策略：
 *  - 返回 true，应用进程存活
 *  - 把死掉的 WebView 从视图树摘掉并 destroy。顺序不能反：对着仍在视图树里
 *    的已死 WebView 做 invalidate 会立刻再崩一次
 *  - 交给调用方决定后续（重建视图 / 提示用户）
 */
open class CrashSafeWebViewClient : WebViewClient() {
    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail?,
    ): Boolean = WebViewRenderCrashGuard.handle(view, detail)
}
