package com.openminis.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.openminis.app.R
import com.openminis.app.a2a.A2aGateway
import com.openminis.app.a2a.A2aLoginProfiles
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.browser.BrowserExternalSchemeHandler
import com.openminis.app.ui.browser.BrowserExternalSchemeHandler.Origin
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.m3Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen host for the a2a gateway's local web panel.
 *
 * Runs in the `:a2a` process (declared in the manifest) AND — on API 28+ —
 * calls [WebView.setDataDirectorySuffix] before creating any WebView, so the
 * panel's cookie jar, localStorage and caches live in a directory of their
 * own. That is what makes the isolation real: without the suffix every
 * process of the package shares one WebView data directory, and panel logins
 * would leak into (and pick up stale state from) the agent browser. With it,
 * accounts never cross-contaminate and a panel crash cannot take down the
 * chat. The panel itself is stateless: it loads from the gateway's loopback
 * port and renders there.
 *
 * [T-android-a2a-login-popup] provider logins complete inside a full-screen
 * in-app login window ([A2aLoginWindowOverlay]): the gateway's web shim
 * opens `window.open`, the panel WebView hosts it, and the shim's own
 * poll-wait/close flow drives it to completion — the reason the panel works
 * in-app at all (in a phone browser the popup becomes a background tab with
 * throttled timers and the 5-minute poll window misses).
 *
 * [T-android-a2a-incognito] "Incognito login" (default ON, carried in the
 * intent — the :a2a process must not re-read SharedPreferences per window):
 * a login round runs in its own throwaway WebView profile — cookies, DOM
 * storage, IndexedDB, service workers and cache — which is deleted when its
 * windows close, so a second account of the same provider can never be
 * pre-authenticated as the first and nothing from the round survives it.
 * Devices whose WebView lacks MULTI_PROFILE fall back to expiring provider
 * cookies plus dropping their web storage.
 *
 * Chrome: a slim iOS-style top bar (Close, panel title, open-in-browser)
 * and a layered BackHandler — open login window first, then panel history,
 * then finish.
 *
 * [T-android-a2a-native]
 */
class A2aPanelActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must run before any WebView is created in this process (one-shot
        // per process). API 28+ only — on 26/27 the panel shares the default
        // data directory with the main process; login isolation then relies
        // on the per-window cookie clearing alone.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("a2a") }
        }
        // Never crash on a missing/garbled extra: the port is a gateway
        // constant, and a crash here would look like the panel "exiting".
        val port = intent.getIntExtra(EXTRA_PORT, A2aGateway.PORT)
            .takeIf { it > 0 }
            ?: A2aGateway.PORT
        val incognitoLogin = intent.getBooleanExtra(EXTRA_INCOGNITO_LOGIN, true)
        val baseUrl = "http://127.0.0.1:$port/"
        AppLogger.info(TAG, "panel opened (port=$port, incognito=$incognitoLogin)")
        // Column's content lambda is a ColumnScope receiver — a bare `this`
        // inside it is the scope, not this Activity. Capture the context
        // once so external-scheme dispatch receives a real Context.
        val activityContext = this
        setContent {
            val popupHost = remember { A2aPopupHostState() }
            // [T-android-a2a-panel-cold-start] Opening the panel must not
            // depend on a status snapshot or on the user tapping the start
            // switch first: the gateway may still be booting after a cold
            // app start (auto-start runs off-main). This screen starts /
            // waits for it itself and only then mounts the WebView, so the
            // first tap lands inside the panel instead of on a connection
            // error.
            var boot by remember { mutableStateOf(PanelBoot.CHECKING) }
            var bootError by remember { mutableStateOf<String?>(null) }
            var bootAttempt by remember { mutableIntStateOf(0) }
            LaunchedEffect(bootAttempt) {
                boot = PanelBoot.CHECKING
                bootError = null
                val error = withContext(Dispatchers.IO) {
                    if (A2aGateway.panelUrl() != null) return@withContext null
                    AppLogger.info(TAG, "gateway not serving — starting it for the panel")
                    A2aGateway.start(activityContext)
                    val status = A2aGateway.status.value
                    if (status.running) null else status.lastError ?: "gateway did not answer"
                }
                bootError = error
                boot = if (error == null) PanelBoot.READY else PanelBoot.FAILED
                if (error != null) AppLogger.warning(TAG, "panel boot failed: $error")
            }
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MinisTextButton(onClick = { finish() }) {
                            Text(
                                text = stringResource(R.string.a2a_close),
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = stringResource(R.string.a2a_panel_page_title),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        MinisTextButton(onClick = {
                            // The loopback panel is only reachable in-app
                            // (the gateway binds 127.0.0.1); opening the
                            // gateway's own host page in a browser shows a
                            // local connection error. Route to the upstream
                            // Agent2API home instead of a dead tab.
                            runCatching {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(UPSTREAM_HOME))
                                )
                            }
                        }) {
                            Icon(
                                imageVector = m3Icon(Icons.Outlined.OpenInBrowser),
                                contentDescription = stringResource(R.string.a2a_open_in_browser),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    when (boot) {
                        PanelBoot.CHECKING -> PanelBootState(
                            title = stringResource(R.string.a2a_panel_starting),
                            showProgress = true,
                        )
                        PanelBoot.FAILED -> PanelBootState(
                            title = stringResource(R.string.a2a_panel_load_failed),
                            reason = bootError,
                            onRetry = { bootAttempt++ },
                        )
                        PanelBoot.READY -> A2aPanelWebView(
                            baseUrl = baseUrl,
                            onWebViewReady = { webView = it },
                            onExternalLink = { url ->
                                // tel:/mailto:/intent:/office-raccoon:… from the
                                // panel or a login window: the user (or the
                                // login flow) opened that link on purpose — hand
                                // it to the system handler.
                                BrowserExternalSchemeHandler.handle(activityContext, url, Origin.USER_INITIATED)
                            },
                            modifier = Modifier.fillMaxSize(),
                            popupHost = popupHost,
                            incognitoLogin = incognitoLogin,
                        )
                    }
                }
            }
            BackHandler {
                when {
                    // The login window is the top layer — close it first
                    // (its close is idempotent with window.close()).
                    popupHost.closeTop() -> Unit
                    webView?.canGoBack() == true -> webView?.goBack()
                    else -> finish()
                }
            }
        }
    }

    override fun onDestroy() {
        // Safety net: a round whose windows never closed (process teardown,
        // activity killed) must not leave its throwaway profile behind.
        A2aLoginProfiles.endRound()
        AppLogger.info(TAG, "panel closed")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PORT = "a2a.port"
        const val EXTRA_INCOGNITO_LOGIN = "a2a.incognito_login"
        private const val TAG = "A2aPanel"
        private const val UPSTREAM_HOME = "https://github.com/aimod-cc/Agent2API"
    }
}

/** Boot gate for the panel activity: the WebView mounts only when the gateway answers. */
private enum class PanelBoot { CHECKING, READY, FAILED }

@Composable
private fun PanelBootState(
    title: String,
    reason: String? = null,
    showProgress: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            if (showProgress) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
            } else {
                Icon(
                    imageVector = m3Icon(Icons.Filled.CloudOff),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!reason.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                MinisTextButton(onClick = onRetry) {
                    Text(
                        stringResource(R.string.builtin_plugin_retry),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
