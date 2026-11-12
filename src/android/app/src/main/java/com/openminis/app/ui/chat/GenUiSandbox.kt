package com.openminis.app.ui.chat

/**
 * [#41] Gen UI — pure rules for rendering agent-emitted ```gen-ui fenced
 * code blocks as sandboxed interactive cards.
 *
 * Everything in this file is deliberately JVM-testable: no Android types,
 * no Compose. The WebView wiring that consumes [GenUiWebViewSpec] and the
 * wrapped HTML lives in GenUiCard.kt; WebView interaction itself is
 * instrumented territory (verified on a real device, not by unit tests).
 *
 * Threat model: the HTML comes from the agent (semi-trusted). It may run
 * inline JS for local interaction, but must never reach the network, the
 * local filesystem, or device content providers. Three independent layers
 * enforce that:
 *   1. [GenUiWebViewSpec] — the WebView settings profile (file/content
 *      access off, universal file access off, media gated on gestures);
 *   2. the per-request interceptor in GenUiCard.kt — every http/https
 *      sub-resource is answered with an empty 404 before it leaves the app;
 *   3. [wrapHtml] — a strict Content-Security-Policy injected into the page
 *      itself, so even a WebView-level bypass finds every remote channel
 *      (external scripts, fetch/XHR/WebSocket) refused by the page.
 */
internal object GenUi {

    /** Fence info-string tag. Recognized case-insensitively. */
    const val FENCE_TAG = "gen-ui"

    private val WHITESPACE = Regex("\\s+")

    /**
     * True when a fenced code block's info string selects Gen UI.
     *
     * Behavior contract (pinned by GenUiSandboxTest):
     *  - the FIRST whitespace-separated token decides. `gen-ui html` and
     *    `gen-ui v2` ARE Gen UI — the tail is a free-form authoring hint the
     *    renderer currently ignores (it exists so agents can document the
     *    intended flavor without breaking the card);
     *  - matching is case-insensitive (`GEN-UI`, `Gen-UI`);
     *  - a token that merely starts with the tag is NOT Gen UI —
     *    `gen-ui-html` / `gen_uix` fall through to the ordinary code block;
     *  - empty or whitespace-only info strings are ordinary code blocks.
     */
    fun isGenUiFence(language: String): Boolean =
        language.trim().split(WHITESPACE)
            .firstOrNull()
            ?.equals(FENCE_TAG, ignoreCase = true) == true

    /**
     * Base URL handed to loadDataWithBaseURL. A plain data: URL is an opaque
     * origin where DOM storage throws SecurityError; a fixed localhost
     * origin keeps local interaction (localStorage, inline JS) working while
     * every http/https sub-resource resolves into the interceptor's 404 —
     * the ban stays total without breaking the page's own logic.
     */
    const val BASE_URL = "http://localhost/"

    /**
     * The only settings profile a Gen UI card may use. Defaults ARE the
     * security contract — the test asserts each value so an accidental
     * loosening fails the build:
     *  - JS + DOM storage ON: the entire point of a Gen UI card is local
     *    interactivity (inline scripts, localStorage);
     *  - allowFileAccess / allowContentAccess / *FromFileURLs OFF: no
     *    filesystem reads, no content-provider reads, no file-URL
     *    cross-origin escalation;
     *  - mediaPlaybackRequiresUserGesture ON: no autoplaying media;
     *  - blockNetworkLoads ON: the WebKit-level kill switch — belt to the
     *    interceptor's braces.
     */
    data class Spec(
        val javaScriptEnabled: Boolean = true,
        val domStorageEnabled: Boolean = true,
        val allowFileAccess: Boolean = false,
        val allowContentAccess: Boolean = false,
        val allowFileAccessFromFileURLs: Boolean = false,
        val allowUniversalAccessFromFileURLs: Boolean = false,
        val mediaPlaybackRequiresUserGesture: Boolean = true,
        val blockNetworkLoads: Boolean = true,
    ) {
        companion object {
            /** The sandbox profile. Never hand a card a loosened copy. */
            val SANDBOXED = Spec()
        }
    }

    /**
     * The strict CSP injected into every Gen UI page. Inline script/style
     * stay allowed (the agent's local logic must keep working); everything
     * that could leave the device — external scripts, styles, images,
     * fonts, media, fetch/XHR/WebSocket, form posts, frame navigation —
     * is refused by the page itself.
     */
    private const val CSP_META =
        "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
            "default-src 'none'; " +
            "script-src 'unsafe-inline'; " +
            "style-src 'unsafe-inline'; " +
            "img-src data: blob:; " +
            "font-src data:; " +
            "media-src data: blob:; " +
            "connect-src 'none'; " +
            "form-action 'none'; " +
            "base-uri 'none'>"

    private val HEAD_OPEN = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * Wrap the agent's HTML with [CSP_META]. Injected right after the
     * `<head…>` open tag when one exists (matched case-insensitively, so
     * `<HEAD>` documents are covered); otherwise prepended. Idempotent in
     * effect — the meta tag carries no identifiers, so running it twice on
     * the same input would only duplicate a no-op tag, and callers remember
     * the wrapped string anyway.
     */
    fun wrapHtml(html: String): String {
        val match = HEAD_OPEN.find(html) ?: return CSP_META + html
        val insertAt = match.range.last + 1
        return html.substring(0, insertAt) + CSP_META + html.substring(insertAt)
    }
}
