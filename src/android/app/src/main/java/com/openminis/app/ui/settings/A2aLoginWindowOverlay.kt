package com.openminis.app.ui.settings

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.R
import com.openminis.app.browser.WebViewEngine
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-android-a2a-login-popup] The login windows the gateway's web shim
 * opens via `window.open` during a provider login flow. The shim's
 * runLoginFlow opens `about:blank`, drives it to the provider's auth URL,
 * polls the gateway for the completed state and closes the window when
 * the account lands — exactly the desktop "embedded window" semantics,
 * hosted in-app instead of a browser tab.
 *
 * The window lives on top of the panel as a full-screen overlay with an
 * iOS-style chrome bar (close button, live page title, hairline load
 * progress). Its navigation policy is the panel's own
 * ([A2aPanelNav]): http(s) loads in place — the auth page AND the
 * gateway's loopback callback must both load — custom/system schemes
 * dispatch externally, `javascript:`/`file:` stay confined.
 *
 * One subtlety: the host (A2aPopupHostState) publishes the window the
 * moment `window.open` arrives, so the overlay's AndroidView mounts a
 * frame later while the shim may already be driving `location.href` — a
 * detached WebView still performs its loads, so nothing is lost, and the
 * view attaches as soon as the composition lands.
 */
@Composable
internal fun A2aLoginWindowOverlay(
    popup: A2aPopupWindowState,
    onClose: () -> Unit,
    onExternalLink: (String) -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    if (showLog) {
        A2aGatewayLogDialog(onDismiss = { showLog = false })
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Hairline Safari-style load progress under the chrome bar.
            if (popup.progress in 1..99) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(onClick = onClose) {
                    Text(
                        stringResource(R.string.a2a_close),
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = popup.title ?: stringResource(R.string.a2a_login_window_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // [T-android-a2a-login-capture] The engine's [Login] log is the
                // only place a stuck capture explains itself — surface it here,
                // right where the login is happening.
                MinisTextButton(onClick = { showLog = true }) {
                    Text(
                        stringResource(R.string.a2a_view_gateway_log),
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
                AndroidView(
                    factory = { popup.webView },
                    // The host already dropped this window from the board
                    // before the overlay left the composition, so release
                    // destroys the WebView exactly once — never reuse a
                    // destroyed WebView.
                    onRelease = {
                        try {
                            it.destroy()
                        } catch (_: Throwable) {
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                when {
                    !popup.loadStarted && !popup.loadFailed -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    popup.loadFailed -> Box(
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
                            Button(onClick = {
                                popup.loadFailed = false
                                popup.loadStarted = false
                                popup.webView.reload()
                            }) {
                                Text(stringResource(R.string.builtin_plugin_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Build and wire a login-popup WebView: same engine recipe as the panel
 * (JS + DOM storage on, WebViewEngine compat), panel navigation policy,
 * and per-window state callbacks (title / progress / load state) feeding
 * [A2aPopupHostState]. The host registers the window BEFORE the clients
 * are wired so the overlay exists by the time the shim drives it.
 */
internal fun createA2aLoginWebView(
    context: Context,
    host: A2aPopupHostState,
    onExternalLink: (String) -> Unit,
): WebView {
    val webView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // [T-android-a2a-login-capture] Provider OAuth/SSO frequently runs in
        // third-party frames — WebView ships with third-party cookies OFF.
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // Google's OAuth policy refuses embedded webviews by the `; wv`
        // marker in the default UA ("disallowed_useragent"); strip just that
        // token so the page still sees an honest Android Chrome UA.
        runCatching {
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
        }
        WebViewEngine.applyCompat(this)
    }
    val state = host.open(webView)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            state.loadStarted = true
            state.loadFailed = false
            // [T-android-a2a-incognito] Remember the provider origins this
            // login touched, so the NEXT login window starts with exactly
            // those cookies expired (loopback is excluded inside).
            com.openminis.app.a2a.A2aLoginCookies.rememberOrigin(context, url)
            // [T-android-a2a-login-capture] Login-window diagnostics: host and
            // scheme only (the query string can carry codes/tokens), so a
            // stuck capture can be traced from the in-app log viewer.
            url?.let { u ->
                val parsed = runCatching { java.net.URI(u) }.getOrNull()
                com.openminis.app.logging.AppLogger.info(
                    "A2aLogin",
                    "login window load: ${parsed?.scheme}://${parsed?.host}",
                )
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) state.loadFailed = true
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val url = request?.url?.toString() ?: return false
            return when (A2aPanelNav.resolve(url, baseUrl = null, isMainFrame = request.isForMainFrame)) {
                // The auth page, its redirects and the gateway's loopback
                // callback all load here — the login must complete inside
                // this window.
                A2aPanelNavAction.LOAD_IN_PLACE -> false
                A2aPanelNavAction.EXTERNAL_SCHEME -> {
                    onExternalLink(url)
                    true
                }
                A2aPanelNavAction.CONFINED -> true
            }
        }
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            state.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            state.title = title
        }

        override fun onCloseWindow(window: WebView?) {
            // window.close() from inside the popup — the shim closes the
            // login window when the account lands.
            host.closeFor(window)
        }
    }
    return webView
}
