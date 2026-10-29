package com.openminis.app.ui.settings

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * Full-screen host for the a2a gateway's local web panel.
 *
 * Runs in the `:a2a` process (declared in the manifest): Android's WebView
 * cookie jar, localStorage and caches are per-process, so everything the
 * panel does — including provider web logins — is fully isolated from this
 * app's main process (agent browser, webapp shortcuts) and from the device's
 * browser. Accounts never cross-contaminate, and a panel crash cannot take
 * down the chat. The panel itself is stateless: it loads from the gateway's
 * loopback port and renders there.
 *
 * [T-android-a2a-native]
 */
class A2aPanelActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra(EXTRA_PORT, 0).takeIf { it > 0 }
            ?: error("A2aPanelActivity requires a port")
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                A2aPanelWebView(
                    baseUrl = "http://127.0.0.1:$port/",
                    onWebViewReady = { webView = it },
                    onExternalLink = { /* non-http(s): confined, nothing opens */ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            BackHandler {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_PORT = "a2a.port"
    }
}
