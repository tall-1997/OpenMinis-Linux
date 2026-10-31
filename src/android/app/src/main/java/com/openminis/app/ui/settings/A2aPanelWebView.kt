package com.openminis.app.ui.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import com.openminis.app.R
import com.openminis.app.a2a.A2aGateway
import com.openminis.app.a2a.A2aLoginCallback
import com.openminis.app.a2a.A2aLoginCookies
import com.openminis.app.a2a.A2aLoginProfiles
import com.openminis.app.a2a.A2aPanelMobileShell
import com.openminis.app.browser.WebViewEngine
import com.openminis.app.ui.theme.m3Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-app WebView hosting the a2a gateway's local web panel.
 *
 * [T-android-a2a-native] The gateway binds 127.0.0.1 only, so the panel is
 * reached inside this app — never via ACTION_VIEW / an external browser.
 * Hard rules encoded here:
 *
 *  - JS + DOM storage on (the panel is a bundled SPA backed by
 *    localStorage), WebViewEngine.applyCompat keeps the engine current
 *    with the repo's shared WebView recipe.
 *  - Navigation policy ([A2aPanelNav]): the embedded view IS the panel's
 *    in-app browser — every top-frame http/https load happens in place, so
 *    the panel's own flows (adding an account, its web-login hop to a
 *    provider's page, an upstream docs link) complete inside this app.
 *    System/custom schemes (tel:/mailto:/intent:/office-raccoon:) dispatch
 *    to the system via [onExternalLink]; `javascript:`/`file:` and
 *    non-main-frame loads are confined and never navigate.
 *  - Login windows ([T-android-a2a-login-popup]): the gateway's web shim
 *    opens `window.open` during provider logins; multi-window support
 *    routes each to an in-app full-screen login window
 *    ([A2aLoginWindowOverlay]) hosted on [popupHost], completing the login
 *    without any browser tab — the shim's desktop embedded-window flow,
 *    in-app.
 *  - Load-state ladder: Loading → (Loaded | Error). The gateway process
 *    may be starting while this view is already composed, so the first
 *    connection attempt legitimately fails with ERR_CONNECTION_REFUSED;
 *    the error state carries a Retry button instead of leaving a blank
 *    frame.
 *  - `onWebViewReady` hands the raw WebView back so the screen's
 *    BackHandler can walk WebView history before leaving the screen
 *    (same pattern as MinisSkillsBrowserScreen).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun A2aPanelWebView(
    baseUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onExternalLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    reloadToken: Int = 0,
    popupHost: A2aPopupHostState,
    incognitoLogin: Boolean = true,
) {
    var loadFailed by remember(baseUrl, reloadToken) { mutableStateOf(false) }
    var loadStarted by remember(baseUrl, reloadToken) { mutableStateOf(false) }
    // Retry bump: the AndroidView update lambda only reacts to identity
    // changes, so a plain boolean flip would never reach the WebView. The
    // retry counter is threaded through the tag key so each tap issues a
    // fresh loadUrl against the (now up) gateway.
    var retryCount by remember(baseUrl, reloadToken) { mutableStateOf(0) }
    val loadKey = reloadToken * 1_000 + retryCount
    val context = LocalContext.current
    val popups by popupHost.popups.collectAsStateWithLifecycle()
    // Loopback port the gateway listens on — login-window callbacks that the
    // shell has to deliver are rewritten onto it (see A2aLoginCallback).
    val gatewayPort = remember(baseUrl) {
        runCatching { java.net.URI(baseUrl).port }.getOrNull()
            ?.takeIf { it > 0 }
            ?: A2aGateway.PORT
    }
    // [T-android-a2a-incognito] A login round exists exactly as long as its
    // windows: once the last one leaves the board, the round's throwaway
    // profile is deleted off-main (cookies, storage, cache). WebViews are
    // destroyed one frame later in the overlay's onRelease — endRound waits
    // that frame out and retries.
    var hadLoginWindow by remember { mutableStateOf(false) }
    LaunchedEffect(popups.isEmpty()) {
        if (popups.isNotEmpty()) {
            hadLoginWindow = true
        } else if (hadLoginWindow) {
            hadLoginWindow = false
            A2aLoginProfiles.endRound()
            // [T-android-a2a-incognito] The round takes its provider state
            // with it: profile-bound logins leave nothing behind, and a round
            // that ran on the default profile (unsupported or silently
            // refused binding) is swept here instead of at the next login, so
            // a stale session can never answer the next round's redirect.
            // Off the UI thread: setCookie is a synchronous binder round trip.
            withContext(Dispatchers.IO) {
                A2aLoginCookies.clearProviderCookies(context)
                A2aLoginCookies.clearProviderStorage(context)
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // [T-android-a2a-login-popup] The gateway's web shim
                    // runs its provider logins through window.open — the
                    // multi-window support routes those to in-app login
                    // windows (see webChromeClient below) instead of the
                    // shim's browser-tab fallback.
                    settings.setSupportMultipleWindows(true)
                    @Suppress("DEPRECATION")
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    // [T-android-a2a-login-capture] Several providers' login
                    // pages run their SSO inside third-party frames; WebView
                    // ships with third-party cookies OFF.
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    WebViewEngine.applyCompat(this)
                    // [T-android-a2a-mobile-fouc] The full mobile wiring
                    // runs from onPageFinished (it needs the rendered
                    // shell), which is after the first paint — so every cold
                    // start flashes the desktop grid before snapping into
                    // the card layout. The stylesheet and scrollbar patch
                    // are render-blocking: injected at document start (the
                    // earliest WebView hook) they are applied to the very
                    // first frame. The origin rule keeps vendor SSO pages,
                    // opened as in-app login windows, from running it.
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        A2aPanelMobileShell.START_SCRIPT,
                        setOf(A2aPanelMobileShell.START_ORIGIN_RULES),
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loadStarted = true
                            // [T-android-a2a-incognito] While a login round is
                            // open this view is part of it — the shim's link
                            // fallback completes the login right here — so its
                            // provider origins are tracked for the round-end
                            // sweep.
                            if (A2aLoginProfiles.isRoundActive()) {
                                A2aLoginCookies.rememberOrigin(context, url)
                            }
                        }

                        /**
                         * [T-android-a2a-mobile] The panel ships a phone layout
                         * (`css/mobile.css` + `mobile-columns.js`) that its own
                         * `index.html` never references, so a phone gets the
                         * desktop grid: a 68px sidebar rail and a table whose
                         * inline `min-width` (1313px) hides four fifths of every
                         * row behind a horizontal scroll. Wiring it in on each
                         * document load is additive and idempotent — see
                         * [A2aPanelMobileShell].
                         */
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            A2aPanelMobileShell.applyTo(view)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            // Only a failed TOP-FRAME main-document load flips
                            // the error state; subresource (favicon, XHR)
                            // failures must not cover an already-rendered
                            // panel with an error page.
                            if (request?.isForMainFrame == true) loadFailed = true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // [T-android-a2a-login-capture] A shell-delivered
                            // callback that reaches the panel (rather than a
                            // login window) still has to end at the gateway;
                            // see A2aLoginCallback. Runs before the policy so
                            // it never leaves the app.
                            if (request?.isForMainFrame == true) {
                                A2aLoginCallback.rewrite(url, gatewayPort)?.let { rewritten ->
                                    view?.loadUrl(rewritten)
                                    return true
                                }
                            }
                            val action = A2aPanelNav.resolve(url, baseUrl, isMainFrame = request?.isForMainFrame == true)
                            return when (action) {
                                // Top-frame http/https: load in place — the
                                // embedded view is the panel's in-app browser.
                                A2aPanelNavAction.LOAD_IN_PLACE -> false
                                // tel:/mailto:/intent:/office-raccoon:… — hand
                                // to the system so the panel never dead-ends
                                // on ERR_UNKNOWN_URL_SCHEME.
                                A2aPanelNavAction.EXTERNAL_SCHEME -> {
                                    onExternalLink(url)
                                    true
                                }
                                // javascript:/file:/subframe loads: confined,
                                // nothing opens.
                                A2aPanelNavAction.CONFINED -> true
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?,
                        ): Boolean {
                            // Without a result message the WebView cannot
                            // hand the window handle back to JS; falling
                            // through lets the shim's own link fallback run
                            // instead of opening an orphan window.
                            if (resultMsg == null || view == null) return false
                            // [T-android-a2a-incognito] Incognito login: the
                            // whole round runs in a THROWAWAY WebView profile
                            // (cookies + DOM storage + IndexedDB + service
                            // workers + cache), deleted when its windows close
                            // — a cookie sweep alone cannot keep an SPA from
                            // restoring the previous account out of storage.
                            // Loopback (the panel's own session) is never
                            // touched either way.
                            val loginProfile =
                                if (incognitoLogin) A2aLoginProfiles.currentOrBegin() else null
                            if (incognitoLogin) {
                                // [T-android-a2a-incognito] Unconditional: the
                                // profile is the strong boundary, but this round
                                // must not start pre-authenticated even when the
                                // platform can't provide one (or accepts the
                                // binding without applying it).
                                A2aLoginCookies.clearProviderCookies(context)
                            }
                            val popup = createA2aLoginWebView(
                                context,
                                popupHost,
                                onExternalLink,
                                gatewayPort,
                                loginProfile,
                                incognitoLogin,
                            )
                            // WebViewTransport() takes no arguments — the
                            // only ctor param is the implicit outer WebView;
                            // the popped window is handed over via setWebView.
                            val transport = view.WebViewTransport()
                            transport.webView = popup
                            resultMsg.obj = transport
                            resultMsg.sendToTarget()
                            return true
                        }

                        override fun onCloseWindow(window: WebView?) {
                            // popup.close() runs in the PANEL's JS context,
                            // so the panel WebView's chrome client receives
                            // it — idempotent with the popup's own handler.
                            popupHost.closeFor(window)
                        }
                    }
                    onWebViewReady(this)
                }
            },
            // First load AND every reload (Retry after a failed first
            // contact, or the gateway coming up while this tab is already
            // visible) go through update, keyed on loadKey — the
            // container+update pattern (BrowserWebView) so a remount never
            // leaves a stale WebView on screen and the first mount loads
            // exactly once.
            update = { container ->
                if (container.tag != loadKey) {
                    container.tag = loadKey
                    container.loadUrl(baseUrl)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        when {
            // First connection attempt still in flight.
            !loadStarted && !loadFailed -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            // Gateway not up yet (typical: ERR_CONNECTION_REFUSED while it
            // boots) — retry instead of a dead white frame.
            loadFailed -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = m3Icon(Icons.Filled.CloudOff),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.a2a_panel_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { loadFailed = false; loadStarted = false; retryCount++ }) {
                        Text(stringResource(R.string.builtin_plugin_retry))
                    }
                }
            }
        }

        // Login popups render above everything, in z-order — the newest
        // window is on top.
        popups.forEach { popup ->
            A2aLoginWindowOverlay(
                popup = popup,
                onClose = { popupHost.close(popup.id) },
                onExternalLink = onExternalLink,
            )
        }
    }
}

/** Where a panel navigation request goes. */
internal enum class A2aPanelNavAction {
    /** Top-frame http(s)/about — load in place; the panel never leaves the app. */
    LOAD_IN_PLACE,

    /**
     * A system/custom scheme (`tel:`, `mailto:`, `intent:`, `market:`,
     * `office-raccoon:` and friends) the WebView cannot load — the screen
     * hands it to the system ([onExternalLink]); the panel never dead-ends
     * on `ERR_UNKNOWN_URL_SCHEME`.
     */
    EXTERNAL_SCHEME,

    /** `javascript:`/`file:`/subframe/unparseable — confined, nothing opens. */
    CONFINED,
}

/**
 * Pure navigation-policy resolver for the a2a panel WebView, shared by the
 * production WebViewClient and the JVM tests (no android.* types).
 */
internal object A2aPanelNav {

    /**
     * Classify one navigation request.
     *
     * Every top-frame http(s) URL loads in place — the embedded WebView is
     * the panel session's in-app browser, so the panel's own flows
     * (provider web logins, docs links) complete here and never hand off to
     * anything outside the app. System/custom schemes dispatch externally
     * (they are links to *other* apps, not pages: the panel's `tel:` /
     * `mailto:` / `intent:` / Office-callback links must reach their
     * handlers instead of dying inside the WebView). The hard-safety set
     * stays confined: `javascript:` (script injection surface), `file:`
     * (content-uri permission surface), subframe loads regardless of
     * origin, and anything unparseable falls closed.
     *
     * Login callbacks are pre-empted before this policy runs: the two
     * shapes only a shell can deliver are rewritten onto the gateway by
     * [com.openminis.app.a2a.A2aLoginCallback], so they never reach here.
     */
    fun resolve(url: String?, baseUrl: String?, isMainFrame: Boolean = true): A2aPanelNavAction {
        if (!isMainFrame) return A2aPanelNavAction.CONFINED
        if (url.isNullOrBlank()) return A2aPanelNavAction.CONFINED
        // A scheme-only pass: URI.parseServerAuthority would reject hosts
        // that are perfectly fine for external dispatch (intent://#Intent;…),
        // and about:blank is not server-authority shaped either.
        val scheme = runCatching { java.net.URI(url.trim()).scheme }.getOrNull()?.lowercase()
            ?: return A2aPanelNavAction.CONFINED
        return when (scheme) {
            "http", "https", "about" -> A2aPanelNavAction.LOAD_IN_PLACE
            "javascript", "file" -> A2aPanelNavAction.CONFINED
            else -> A2aPanelNavAction.EXTERNAL_SCHEME
        }
    }
}
