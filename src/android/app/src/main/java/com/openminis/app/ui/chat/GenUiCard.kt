package com.openminis.app.ui.chat

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.m3Icon
import java.io.ByteArrayInputStream

// [#41] Gen UI card. A closed ```gen-ui fence renders here instead of the
// generic code block: a sandboxed WebView showing the agent's interactive
// HTML, height-capped so it behaves like any other inline attachment.
//
// Security contract (see GenUiSandbox.kt for the pure rules):
//   - settings profile = GenUi.Spec.SANDBOXED (file/content bridges off,
//     DOM storage on, media gated, WebKit-level network kill switch);
//   - every http/https request the page attempts is intercepted and
//     answered with an empty 404 — nothing leaves the app;
//   - the page itself carries a strict CSP via GenUi.wrapHtml.
// The three layers are independent on purpose: loosening any one of them
// still leaves the other two holding the line.

/**
 * Max card height. Matches the code-block cap family (code uses 400dp); the
 * WebView scrolls internally, so tall Gen UI pages stay contained instead
 * of pushing the rest of the message off-screen.
 */
private val GenUiCardMaxHeight = 300.dp

/** Header/actions row height and icon sizing, aligned with the code-block header. */
private val GenUiIconSize = 16.dp

@Composable
internal fun GenUiCard(block: MdBlock.CodeBlock) {
    var showSource by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val colors = currentMdColors()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBg),
    ) {
        // Header row: label + source toggle + fullscreen — same visual
        // rhythm as the code block's language/copy row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.gen_ui_card_label),
                fontSize = 11.sp,
                color = MdCodeLangColor,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (showSource) m3Icon(Icons.Default.RemoveRedEye) else m3Icon(Icons.Default.Code),
                contentDescription = stringResource(
                    if (showSource) R.string.gen_ui_view_render else R.string.gen_ui_view_source,
                ),
                tint = MdCodeLangColor,
                modifier = Modifier
                    .size(GenUiIconSize)
                    .clickable { showSource = !showSource },
            )
            Box(modifier = Modifier.size(12.dp))
            Icon(
                imageVector = m3Icon(Icons.Outlined.Fullscreen),
                contentDescription = stringResource(R.string.gen_ui_view_fullscreen),
                tint = MdCodeLangColor,
                modifier = Modifier
                    .size(GenUiIconSize)
                    .clickable { fullscreen = true },
            )
        }
        // Body: rendered WebView or raw source, both height-capped. The
        // source view deliberately reuses the code-block styling (monospace
        // text on the same surface) so toggling reads as the same object.
        if (showSource) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = GenUiCardMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = block.code,
                    fontSize = BaseFontSize * 0.85f,
                    fontFamily = FontFamily.Monospace,
                    color = colors.codeText,
                    lineHeight = BaseLineHeight * 0.9f,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        } else {
            GenUiWebView(
                html = block.code,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GenUiCardMaxHeight),
            )
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ChatColors.background),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.gen_ui_card_label),
                        fontSize = 14.sp,
                        color = ChatColors.primaryText,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (showSource) m3Icon(Icons.Default.RemoveRedEye) else m3Icon(Icons.Default.Code),
                        contentDescription = stringResource(
                            if (showSource) R.string.gen_ui_view_render else R.string.gen_ui_view_source,
                        ),
                        tint = ChatColors.secondaryText,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { showSource = !showSource },
                    )
                    Box(modifier = Modifier.size(12.dp))
                    Icon(
                        imageVector = m3Icon(Icons.Default.Close),
                        contentDescription = stringResource(R.string.standard_sheet_close),
                        tint = ChatColors.secondaryText,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { fullscreen = false },
                    )
                }
                if (showSource) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = block.code,
                            fontSize = BaseFontSize * 0.85f,
                            fontFamily = FontFamily.Monospace,
                            color = colors.codeText,
                            lineHeight = BaseLineHeight * 0.9f,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    GenUiWebView(
                        html = block.code,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The sandboxed WebView. Configured strictly from [GenUi.Spec.SANDBOXED] —
 * there is intentionally no parameter to weaken it. The page loads once per
 * composition of a closed block; the HTML is already frozen at that point
 * (the fence closed before this composable exists).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GenUiWebView(
    html: String,
    modifier: Modifier = Modifier,
) {
    val wrapped = remember(html) { GenUi.wrapHtml(html) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.applyGenUiSandbox(GenUi.Spec.SANDBOXED)
                webViewClient = GenUiNetworkBlocker
                // Transparent so the card surface (already themed) shows
                // through instead of a white flash before the page paints.
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                loadDataWithBaseURL(GenUi.BASE_URL, wrapped, "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * Apply the pure sandbox profile to a real WebView. The mirror of
 * [GenUi.Spec] — every field maps 1:1, nothing extra, nothing omitted.
 */
private fun WebSettings.applyGenUiSandbox(spec: GenUi.Spec) {
    javaScriptEnabled = spec.javaScriptEnabled
    domStorageEnabled = spec.domStorageEnabled
    allowFileAccess = spec.allowFileAccess
    allowContentAccess = spec.allowContentAccess
    setAllowFileAccessFromFileURLs(spec.allowFileAccessFromFileURLs)
    setAllowUniversalAccessFromFileURLs(spec.allowUniversalAccessFromFileURLs)
    mediaPlaybackRequiresUserGesture = spec.mediaPlaybackRequiresUserGesture
    blockNetworkLoads = spec.blockNetworkLoads
    blockNetworkImage = spec.blockNetworkLoads
    cacheMode = WebSettings.LOAD_NO_CACHE
}

/**
 * Per-request network ban: every http/https load (scripts, images, XHR,
 * frames, anything) is answered with an empty 404 without leaving the
 * process. Non-http schemes fall through — data:/blob: URIs are inline
 * content, minis:// style handlers are not registered on this WebView.
 */
private object GenUiNetworkBlocker : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = when (request.url.scheme?.lowercase()) {
        "http", "https" -> empty404()
        else -> null
    }

    /** Empty body, 404 status — cheap, and cannot leak anything. */
    private fun empty404(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        mapOf(),
        ByteArrayInputStream(ByteArray(0)),
    )
}
