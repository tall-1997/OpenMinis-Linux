package com.openminis.app.ui.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.R
import com.openminis.app.browser.WebViewEngine
import com.openminis.app.ui.theme.m3Icon

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
 *  - Navigation confinement: the embedded view IS the panel's in-app
 *    browser — every top-frame http/https load happens in place, so the
 *    panel's own flows (adding an account, its web-login hop to a
 *    provider's page, an upstream docs link) complete inside this app and
 *    never leave it. Non-http(s) schemes (a `javascript:`/`tel:`/custom
 *    scheme) and non-main-frame (iframe/subframe) loads never navigate.
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
) {
    var loadFailed by remember(baseUrl, reloadToken) { mutableStateOf(false) }
    var loadStarted by remember(baseUrl, reloadToken) { mutableStateOf(false) }
    // Retry bump: the AndroidView update lambda only reacts to identity
    // changes, so a plain boolean flip would never reach the WebView. The
    // retry counter is threaded through the tag key so each tap issues a
    // fresh loadUrl against the (now up) gateway.
    var retryCount by remember(baseUrl, reloadToken) { mutableStateOf(0) }
    val loadKey = reloadToken * 1_000 + retryCount

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
                    WebViewEngine.applyCompat(this)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loadStarted = true
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
                            val action = A2aPanelNav.resolve(url, baseUrl, isMainFrame = request.isForMainFrame)
                            return when (action) {
                                // Top-frame http/https: load in place — the
                                // embedded view is the panel's in-app browser.
                                A2aPanelNavAction.LOAD_IN_PLACE -> false
                                // Non-http(s) schemes and subframe loads:
                                // confined, nothing opens.
                                A2aPanelNavAction.CONFINED -> {
                                    onExternalLink(url)
                                    true
                                }
                            }
                        }
                    }
                    webChromeClient = WebChromeClient()
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
    }
}

/** Where a panel navigation request goes. */
internal enum class A2aPanelNavAction {
    /** Top-frame http/https — load in place; the panel never leaves the app. */
    LOAD_IN_PLACE,

    /** Non-http(s) scheme or non-main-frame load — confined, nothing opens. */
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
     * Every top-frame http/https URL loads in place — the embedded WebView
     * is the panel session's in-app browser, so the panel's own flows
     * (provider web logins, docs links) complete here and never hand off to
     * anything outside the app. The scheme check first: a `javascript:` or
     * custom-scheme URL must never navigate. Subframe (iframe/subresource)
     * loads are confined regardless of origin. A null/blank URL falls
     * closed.
     */
    fun resolve(url: String?, baseUrl: String?, isMainFrame: Boolean = true): A2aPanelNavAction {
        if (!isMainFrame) return A2aPanelNavAction.CONFINED
        val target = runCatching { java.net.URI(url?.trim() ?: "").parseServerAuthority() }.getOrNull()
            ?: return A2aPanelNavAction.CONFINED
        val scheme = target.scheme?.lowercase()
        // An unparseable or scheme-less target (a relative oddity like
        // "not a url \") is not a web page — confined, like tel:/javascript:.
        if (scheme != "http" && scheme != "https") return A2aPanelNavAction.CONFINED
        return A2aPanelNavAction.LOAD_IN_PLACE
    }
}
