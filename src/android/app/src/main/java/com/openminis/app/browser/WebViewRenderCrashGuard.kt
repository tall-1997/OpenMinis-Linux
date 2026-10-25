package com.openminis.app.browser

import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView

/**
 * 统一的渲染进程死亡处理。
 *
 * Android 的 WebView 渲染进程在 OOM / 沙箱崩溃时会独立死掉。默认行为是
 * **整个应用进程被系统一并杀死** —— 用户看到的是"用着用着闪退"，而 logcat
 * 里只有一条 `Render process gone`。返回 true 是唯一能阻止这个连锁的途径：
 * 它告诉系统的"我已经处理了"，从而保留应用进程存活。
 *
 * 处理策略：
 *  - 返回 true，应用继续活着
 *  - 把死掉的 WebView 从视图树摘掉并 destroy，避免继续向它派发绘制指令
 *    （对着已死渲染进程的 View 做 invalidate 会立刻再崩一次）
 *  - 交给调用方决定后续：预览页显示可重试的错误、agent 浏览器重建一个 tab
 */
object WebViewRenderCrashGuard {

    private const val TAG = "WebViewCrashGuard"

    /**
     * 装到任何 WebViewClient 上：`override fun onRenderProcessGone(...) =
     *     WebViewRenderCrashGuard.handle(view, detail, onRecover)`
     *
     * @param onRecover 渲染进程已确认死亡且该 WebView 已销毁后调用，用于
     *   重建视图或提示用户。在主线程调用。
     */
    fun handle(
        view: WebView,
        detail: RenderProcessGoneDetail?,
        onRecover: (() -> Unit)? = null,
    ): Boolean {
        val didCrash = detail?.didCrash() ?: false
        Log.e(
            TAG,
            "WebView render process gone — didCrash=$didCrash " +
                "rendererPriorityAtExit=${detail?.rendererPriorityAtExit()}",
        )
        // 先把自己从父容器摘掉，再 destroy。顺序不能反：destroy 一个仍在
        // 视图树里的 WebView 会让下一次 measure/layout 摸到空 renderer。
        runCatching {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }.onFailure { Log.w(TAG, "detach/destroy failed: ${it.message}") }
        runCatching { onRecover?.invoke() }
            .onFailure { Log.w(TAG, "onRecover failed: ${it.message}") }
        // true = 已处理，系统不要杀本进程。
        return true
    }
}
