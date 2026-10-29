package com.openminis.app.ui.settings

import android.webkit.WebChromeClient
import android.webkit.WebView
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-a2a-login-popup] Compose host for the login windows the a2a
 * panel's web shim opens through `window.open`.
 *
 * The panel WebView's [WebChromeClient.onCreateWindow] creates the popup
 * WebView and hands the handle back to the shim via [android.os.Message];
 * this host publishes it as a full-screen overlay above the panel. The
 * shim then drives the window (auth URL, gateway poll-wait, close) with
 * zero app-side scripting — the app only hosts the window the shim asked
 * for, which is what makes the desktop "embedded window" login flow work
 * in-app.
 *
 * State model:
 *  - [open] is idempotent per live window: a duplicate `onCreateWindow`
 *    round for a window already on the board returns its existing state
 *    instead of stacking a second overlay. A window that has been closed
 *    (and therefore left the board) may be rehosted by a later login
 *    round.
 *  - [closeFor] is the idempotent close: the shim's `window.close()` runs
 *    in the PANEL's JS context, so the panel's chrome client receives it
 *    AND the popup's own chrome client receives it — the second call is a
 *    no-op.
 *  - [closeTop] backs the system back gesture: newest window first, then
 *    panel history, then the screen finishes.
 *
 * The published list is a [StateFlow]; the panel screen collects it and
 * stacks one [A2aLoginWindowOverlay] per window in z-order. All mutation
 * is main-thread Compose state — no locks.
 */
class A2aPopupHostState {

    private val _popups = MutableStateFlow<List<A2aPopupWindowState>>(emptyList())
    val popups: StateFlow<List<A2aPopupWindowState>> = _popups.asStateFlow()

    /**
     * Publish [webView] as a full-screen login window. Returns the live
     * state for [webView] if it is already on the board (duplicate
     * `onCreateWindow` round), otherwise appends a fresh window whose
     * [initialUrl] the shim navigates on its own.
     */
    fun open(webView: WebView, initialUrl: String = "about:blank"): A2aPopupWindowState {
        val existing = _popups.value.firstOrNull { it.webView === webView }
        if (existing != null) return existing
        val state = A2aPopupWindowState(webView = webView, initialUrl = initialUrl)
        _popups.value = _popups.value + state
        return state
    }

    /** Close a window by identity — idempotent with [close]. */
    fun closeFor(window: WebView?) {
        if (window == null) return
        _popups.value = _popups.value.filterNot { it.webView === window }
    }

    /** Close a window by id (the overlay's chrome close button). */
    fun close(id: String) {
        _popups.value = _popups.value.filterNot { it.id == id }
    }

    /**
     * Back gesture: close the newest window. Returns true when a window
     * was closed, false when the board is empty (the panel history takes
     * over).
     */
    fun closeTop(): Boolean {
        val current = _popups.value
        if (current.isEmpty()) return false
        _popups.value = current.dropLast(1)
        return true
    }
}

/**
 * One login window: the immutable window identity plus the per-window
 * chrome state the popup's clients write (title, progress, load state)
 * and the overlay renders.
 */
class A2aPopupWindowState(
    val webView: WebView,
    val initialUrl: String,
) {
    val id: String = UUID.randomUUID().toString()
    var title: String? = null
    var progress: Int = 0
    var loadStarted: Boolean = false
    var loadFailed: Boolean = false
}
