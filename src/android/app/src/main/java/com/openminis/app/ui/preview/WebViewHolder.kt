package com.openminis.app.ui.preview

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.openminis.app.browser.ChromeUserAgent
import com.openminis.app.logging.AppLogger

/**
 * Holds a single WebView instance + the page-state observed from it.
 * Mirrors iOS [WebPreviewSheet.swift] `WebViewHolder` (L20-160) so the
 * same UX contracts hold:
 *
 *  - The WebView is created lazily but exactly once per holder. Compose
 *    can recompose freely without churning the view (which would lose
 *    scroll position and force a reload).
 *  - [T-android-webview-deferred-create] Construction of the platform
 *    WebView is deferred past the holder's creation: `rememberWebViewHolder`
 *    runs during the preview host's composition, but the WebView itself
 *    (a heavyweight native allocation) is only built on [ensureWebView] /
 *    first `webView` access — which the preview hosts trigger at the
 *    sheet's first visible frame, after the scrim/toolbar have painted.
 *    Hosts observe [webViewReady] to swap a placeholder for the real
 *    view without recomposing anything else.
 *  - `pageTitle` / `currentUrl` / `isLoading` are Compose state, so any
 *    composable reading them recomposes when WebView reports progress.
 *  - The same holder is **shared** across the bottom-sheet preview and
 *    the fullscreen variant — that's how iOS makes the expand toggle
 *    feel instant. Tearing down + recreating the WebView would reload
 *    the page, breaking that illusion. Close semantics unchanged:
 *    [destroy] tears down the WebView exactly once, and is a no-op for
 *    the (never-created) case.
 */
class WebViewHolder(
    private val appContext: Context,
    initialUrl: String,
) {

    var pageTitle by mutableStateOf("")
        private set
    var currentUrl by mutableStateOf(initialUrl)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var desktopMode by mutableStateOf(false)
        private set
    var pageFavicon by mutableStateOf<Bitmap?>(null)
        private set

    private var mobileUserAgent: String = ""
    private var destroyed = false

    /** Host hook for `<input type="file">`; wired by [UrlPreviewSheet]. */
    var onShowFileChooser:
        ((android.webkit.WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>>) -> Unit)? = null

    /**
     * [T-android-webview-deferred-create] True once the platform WebView
     * exists. Preview hosts read this to gate the `AndroidView` that embeds
     * it — the first sheet frame renders the (cheap) placeholder, then the
     * WebView is created and swapped in.
     */
    var webViewReady by mutableStateOf(false)
        private set

    private val webViewLock = Any()

    @Volatile
    private var _webView: WebView? = null

    /** The platform WebView, created on first access (main thread). */
    @get:SuppressLint("SetJavaScriptEnabled")
    val webView: WebView
        get() = ensureWebView()

    /**
     * Create the WebView if it does not exist yet and return it. Exactly-once
     * under the [webViewLock]; every call site runs on the main thread (the
     * preview composables and their click handlers), which is what WebView
     * construction requires.
     */
    fun ensureWebView(): WebView {
        _webView?.let { return it }
        synchronized(webViewLock) {
            _webView?.let { return it }
            val created = createWebView()
            _webView = created
            webViewReady = true
            return created
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(appContext).apply {
        // Same Blink bootstrap as browser_use / BrowserSheet: hardware layer
        // for sheet compositing, cookies, file://, metaviewport, WebViewEngine
        // compat. Preview-only clients stay below.
        com.openminis.app.browser.BrowserUseManager.configureWebView(
            this,
            com.openminis.app.browser.UserAgentProfile.MOBILE_CHROME,
        )
        mobileUserAgent = settings.userAgentString
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean {
                val urlStr = request.url?.toString()
                // T234: Google permanently disallows WebView for sign-in /
                // OAuth. Hand auth-domain navigation to Chrome Custom Tab.
                if (com.openminis.app.browser.GoogleAuthRouter.shouldRouteExternally(urlStr)) {
                    if (urlStr != null) {
                        com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(view.context, urlStr)
                    }
                    return true
                }
                // T134: intent:// / market:// / tel: / mailto: get routed
                // out instead of trying to load as a page.
                // [T-android-user-initiated-scheme-dispatch] This WebView is
                // on screen and the user tapped the link inside it.
                return com.openminis.app.ui.browser
                    .BrowserExternalSchemeHandler
                    .handle(
                        view.context,
                        request.url,
                        com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                            .Origin.USER_INITIATED,
                    )
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                isLoading = true
                currentUrl = url
                AppLogger.debug(TAG, "onPageStarted url=${url.take(160)}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                isLoading = false
                pageTitle = view.title.orEmpty()
                AppLogger.debug(TAG, "onPageFinished title=${pageTitle.take(60)}")
                // T-htmlpreview-resize: WebView commits its first layout
                // against whatever viewport height the container had at
                // loadUrl-time. If that height was a transient pre-animation
                // value (bottom-sheet still expanding, IME inset not yet
                // settled), Blink resolves `100vh` / `height:100%` against
                // it once and never re-evaluates — pages with
                // `overflow:hidden` collapse to a clipped 0-height box
                // (white screen), and `position:fixed` popups that read
                // `window.innerHeight` on first paint cache the wrong
                // coordinates inline. Android WebView, unlike desktop
                // Chrome, does NOT auto-dispatch a viewport `resize` when
                // the container resizes. Mirror what BrowserUseManager
                // does (T-webview-popup-d3c6e10f): post a synthetic
                // `resize` after first commit so vh/innerHeight readers
                // recompute against the stabilised height.
                view.postDelayed({
                    view.evaluateJavascript(
                        "window.dispatchEvent(new Event('resize'));",
                        null,
                    )
                }, 100)
            }
        }
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean = com.openminis.app.browser.dispatchWebFileChooser(
                onShowFileChooser, filePathCallback, fileChooserParams,
            )

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrEmpty()) pageTitle = title
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                if (icon != null) pageFavicon = icon
            }

            // [T-android-js-dialogs-256] Show real alert/confirm/prompt dialogs.
            //
            // WebChromeClient's defaults return false, which makes WebView
            // dismiss the dialog itself and hand the page a canned answer
            // (`false` / `null`) with nothing shown — so a page's alert() simply
            // vanished and confirm() silently took the cancel branch. This is
            // the user-facing preview, where a human IS present, so the fix is
            // to show the dialog and block the page on their answer. The agent
            // browser does the opposite by design; see BrowserUseManager.
            //
            // The dialog context must come from `view?.context`, NOT the
            // holder's stored context: the WebView is constructed with the
            // Application context (see the class doc — it has to outlive any one
            // composition), and an Application context has no window token, so
            // AlertDialog.Builder(appContext) throws at show(). `view.context`
            // is the Activity once attached, which is always the case by the
            // time a page can run script.
            //
            // Every path must settle `result` exactly once: leaving a JsResult
            // unanswered wedges the page's JS thread forever. Hence the
            // setOnCancelListener on each (back button / tap-outside) and the
            // cancel() fallback when no Activity context is available.

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                val ctx = view?.context ?: return false.also { result?.cancel() }
                if (ctx !is android.app.Activity) { result?.cancel(); return true }
                android.app.AlertDialog.Builder(ctx)
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
                result: android.webkit.JsResult?,
            ): Boolean {
                val ctx = view?.context ?: return false.also { result?.cancel() }
                if (ctx !is android.app.Activity) { result?.cancel(); return true }
                android.app.AlertDialog.Builder(ctx)
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
                result: android.webkit.JsPromptResult?,
            ): Boolean {
                val ctx = view?.context ?: return false.also { result?.cancel() }
                if (ctx !is android.app.Activity) { result?.cancel(); return true }
                val input = android.widget.EditText(ctx).apply {
                    setText(defaultValue.orEmpty())
                    setSelection(text.length)
                }
                // Inset the field so it doesn't sit flush against the dialog
                // edges, matching the platform's own prompt styling.
                val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                val wrapper = android.widget.FrameLayout(ctx).apply {
                    setPadding(pad, pad / 2, pad, 0)
                    addView(input)
                }
                android.app.AlertDialog.Builder(ctx)
                    .setMessage(message.orEmpty())
                    .setView(wrapper)
                    // confirm() carries the typed text back to the page; a bare
                    // confirm() would resolve prompt() to "" instead.
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm(input.text?.toString().orEmpty())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
        // T-htmlpreview-resize: sheet→fullscreen toggle, IME open/close,
        // and rotation all change the WebView's height after the page is
        // already loaded. Without a `resize` notification, fixed/vh-based
        // layouts stay anchored to the original height and end up offset
        // or clipped. Forward every container-height change to JS.
        addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val oldH = oldBottom - oldTop
            val newH = bottom - top
            if (newH > 0 && newH != oldH) {
                (v as WebView).evaluateJavascript(
                    "window.dispatchEvent(new Event('resize'));",
                    null,
                )
            }
        }
    }

    private var hasLoaded = false

    /**
     * Trigger the initial load on first use. Calling repeatedly is a no-op
     * — repeats happen because both `WebPreviewBottomSheet` and
     * `WebPreviewFullscreenScreen` invoke this on first composition; the
     * second invocation would otherwise re-fetch the page, defeating the
     * whole "shared holder" trick.
     */
    fun startIfNeeded() {
        if (hasLoaded) return
        // [T-android-webview-deferred-create] First start request is what
        // materializes the WebView (the preview hosts call this from a
        // LaunchedEffect at the sheet's first frame).
        val w = ensureWebView()
        // T-htmlpreview-2d5c4f3d: defer the actual loadUrl until the
        // WebView is attached to a window AND has been laid out with a
        // positive width/height. Pages that compute `100vh` / `height: 100%`
        // against the initial measured viewport otherwise see 0×0 and
        // collapse — exactly the white-screen the user reported on
        // viewport-height + overflow:hidden HTML files.
        //
        // We start the load eagerly when the WebView is already laid out
        // (warm reuse — re-entering a sheet for the same holder), and
        // otherwise post once to the WebView's handler after attach.
        hasLoaded = true
        if (w.isAttachedToWindow && w.width > 0 && w.height > 0) {
            AppLogger.info(TAG, "loadUrl (attached) ${currentUrl.take(160)}")
            w.loadUrl(currentUrl)
            return
        }
        w.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.removeOnAttachStateChangeListener(this)
                // doOnLayout fires after the next layout pass — guarantees
                // width/height > 0 so 100vh resolves against a real viewport.
                v.post {
                    if (v.width > 0 && v.height > 0) {
                        AppLogger.info(TAG, "loadUrl (post-attach) ${currentUrl.take(160)}")
                        w.loadUrl(currentUrl)
                    } else {
                        v.viewTreeObserver.addOnGlobalLayoutListener(object :
                            android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                if (v.width > 0 && v.height > 0) {
                                    v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                    AppLogger.info(TAG, "loadUrl (post-layout) ${currentUrl.take(160)}")
                                    w.loadUrl(currentUrl)
                                }
                            }
                        })
                    }
                }
            }
            override fun onViewDetachedFromWindow(v: android.view.View) {}
        })
    }

    fun reload() {
        AppLogger.info(TAG, "reload")
        _webView?.reload()
    }

    /**
     * Toggle desktop / mobile UA + viewport. Desktop rewrites the platform
     * but keeps the system WebView Chrome version. CSS viewport 1280×800,
     * with `setInitialScale` shrunk so the 1280-wide CSS viewport fits the
     * physical container width — the same shrink-to-fit math used in
     * `BrowserUseManager.applyShrinkToFit`.
     */
    fun toggleDesktopMode() {
        desktopMode = !desktopMode
        // User-initiated toggle before the WebView exists: materialize it now
        // so the mode is applied to a real settings object (same semantics as
        // before, when the view always existed).
        val w = ensureWebView()
        if (desktopMode) {
            w.settings.userAgentString = ChromeUserAgent.desktop(mobileUserAgent)
            w.settings.useWideViewPort = true
            w.settings.loadWithOverviewMode = true
            w.settings.setSupportZoom(true)
            w.settings.builtInZoomControls = true
            w.settings.displayZoomControls = false
            applyShrinkToFit(w, DESKTOP_VIEWPORT_CSS_WIDTH)
        } else {
            w.settings.userAgentString = mobileUserAgent
            w.settings.useWideViewPort = false
            w.settings.loadWithOverviewMode = false
            w.setInitialScale(0)
        }
        AppLogger.info(TAG, "toggleDesktopMode → $desktopMode")
        // Clear cache so the site can't serve a cached variant keyed on the
        // previous UA. Sites like baidu pin their first-paint variant to a
        // cookie + UA combo, so a plain reload often returns the old page.
        w.clearCache(false)
        w.reload()
    }

    /**
     * Compute setInitialScale so a page authored at [cssWidth] CSS pixels
     * fits the WebView's container width. Mirrors `BrowserUseManager`'s
     * applyShrinkToFit.
     */
    private fun applyShrinkToFit(webView: WebView, cssWidth: Int) {
        val containerPx = webView.width.takeIf { it > 0 }
            ?: webView.resources.displayMetrics.widthPixels
        val density = webView.resources.displayMetrics.density
        val cssWidthPx = (cssWidth * density).toInt()
        val scalePct = if (cssWidthPx > containerPx) {
            ((containerPx.toLong() * 100) / cssWidthPx).toInt().coerceAtLeast(1)
        } else {
            0
        }
        webView.setInitialScale(scalePct)
    }

    fun stopLoading() {
        AppLogger.info(TAG, "stopLoading")
        _webView?.stopLoading()
        isLoading = false
    }

    /**
     * Detach the WebView from any parent. Compose's AndroidView re-parents
     * the same view between the sheet and the fullscreen screen — Android
     * throws if a view is added to a new parent while still attached to
     * the old one, so callers should invoke this between handoffs.
     *
     * [T-android-webview-deferred-create] No-op when the WebView was never
     * created (user dismissed the sheet before its first frame settled).
     */
    fun detach() {
        _webView?.let { w -> (w.parent as? android.view.ViewGroup)?.removeView(w) }
    }

    /**
     * Final teardown. Call when the preview path is fully closed (sheet
     * dismissed AND fullscreen popped) so the WebView's renderer process
     * doesn't linger. Safe to call multiple times.
     *
     * [T-android-webview-deferred-create] With a holder that never got its
     * first frame, there is nothing to tear down — the flags flip and the
     * (nonexistent) WebView is skipped. Close semantics unchanged.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        val w = _webView
        if (w == null) {
            AppLogger.info(TAG, "destroyed (never created)")
            return
        }
        try {
            detach()
            w.stopLoading()
            w.loadUrl("about:blank")
            w.destroy()
            AppLogger.info(TAG, "destroyed")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "destroy failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WebViewHolder"
        // Match UserAgentProfile.DESKTOP_CHROME.viewportSize.
        private const val DESKTOP_VIEWPORT_CSS_WIDTH = 1280
    }
}

/**
 * Compose-friendly holder factory. Produces a holder keyed on URL — the
 * holder lives across recompositions but is rebuilt if the caller swaps
 * the URL.
 *
 * [T-android-webview-deferred-create] Cheap by design: only the (state-only)
 * holder is built here; the platform WebView is deferred to the first
 * [WebViewHolder.ensureWebView] / `startIfNeeded()` call, which the preview
 * hosts trigger at the sheet's first visible frame. Disposal semantics
 * unchanged — the DisposableEffect below still destroys the holder (and any
 * WebView it materialized) exactly once when the preview path closes.
 */
@Composable
fun rememberWebViewHolder(url: String): WebViewHolder {
    val context = androidx.compose.ui.platform.LocalContext.current
    val holder = remember(url) { WebViewHolder(context.applicationContext, url) }
    DisposableEffect(holder) {
        onDispose { holder.destroy() }
    }
    return holder
}
