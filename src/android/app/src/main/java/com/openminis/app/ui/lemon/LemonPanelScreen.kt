package com.openminis.app.ui.lemon

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.R
import com.openminis.app.ui.theme.LocalChatPalette
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-lemon-integration] The bundled tool catalog panel.
 *
 * Loads `assets/lemon/panel.html?port=<live port>` in a WebView. The page ships
 * inside the APK, so it needs no network: it talks to the app's own built-in
 * MCP server on loopback (`127.0.0.1:<port>/mcp`), the same server any external
 * client would use. That keeps ONE execution path — the page's "run" button and
 * an MCP client's `tools/call` both land in
 * [com.openminis.app.mcp.server.McpToolDispatcher] behind the same
 * [com.openminis.app.security.SecurityGate].
 *
 * Deliberately no JavaScript bridge: the page uses plain `fetch` against
 * loopback, so nothing inside the WebView can reach app objects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LemonPanelScreen(
    port: Int,
    onBack: () -> Unit,
) {
    val url = remember(port) { "file:///android_asset/lemon/panel.html?port=$port" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mcp_lemon_panel)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            m3Icon(Icons.AutoMirrored.Filled.ArrowBack),
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalChatPalette.current.background)
                .padding(padding),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // The page is packaged in the APK; file access is what
                        // lets it load itself. Content access stays off so a
                        // stray content:// URL cannot be pulled in.
                        settings.allowFileAccess = true
                        settings.allowContentAccess = false
                        loadUrl(url)
                    }
                },
                onRelease = { view ->
                    runCatching {
                        view.stopLoading()
                        view.destroy()
                    }
                },
            )
        }
    }
}
