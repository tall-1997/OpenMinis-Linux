// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The browser sign-in to the built-in Tailscale node, one tap from start to
 * finish (Settings > Network > Log In with Browser).
 *
 * Why a flow of its own: tsnet only produces the sign-in URL a moment after
 * the node reaches NeedsLogin (StartLoginInteractive, then a round trip to
 * the control server), and Settings polled the status every 2 s. The row was
 * shown as soon as the state said NeedsLogin but had no click handler until a
 * later poll brought the URL, so the first tap did nothing and only the
 * second one opened the browser. Now a tap always starts the flow: the row
 * shows that it is getting the page, the status is polled quickly until the
 * URL is there, and [opens] hands it to the screen, which opens it in a
 * Custom Tab (the default browser's cookies, so an existing Google / Apple /
 * GitHub / Microsoft sign-in or passkey is reused).
 *
 * While the browser is open the node is polled until it is Running; then
 * [returnToApp] brings Minis back (which also closes the tab, see
 * NetworkSettingsScreen). Leaving the browser without signing in returns to
 * the not-signed-in state silently, and a new tap starts over.
 *
 * [FIX-261021-11] A sign-in URL is opened at most once. tailscale never
 * replaces one by itself: when the round trip that should deliver the
 * result is lost (the browser said "success", the node got
 * "register request: http 410: auth path not found"), it keeps waiting on
 * that dead URL, and StartLoginInteractive hands the same URL out again for
 * 7 days - so every retry opened a page that could no longer complete. A
 * tap that finds only an already-opened URL, or a login error, restarts
 * the node ([freshLogin]) and opens the new URL it brings.
 *
 * The URL itself is never logged.
 */
class TailscaleLoginFlow(
    private val scope: CoroutineScope,
    /** A fresh node status (calls into Go; run on [scope]). */
    private val status: () -> TailscaleStatus,
    /** Brings the app's activity to the front after the sign-in completed. */
    private val returnToApp: () -> Unit,
    /** Publishes a status to Settings at once, without waiting for its poll. */
    private val publish: (TailscaleStatus) -> Unit = {},
    /** Restarts the node so tsnet asks the control server for a new sign-in URL. */
    private val freshLogin: suspend () -> Unit = {},
    private val urlPollMs: Long = 400,
    private val urlTimeoutMs: Long = 20_000,
    private val loginPollMs: Long = 1_000,
    private val loginTimeoutMs: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Idle : State
        /** Tapped; waiting for tsnet's sign-in URL. */
        data object OpeningPage : State
        /** The sign-in page is open in the browser; waiting for Running. */
        data object InBrowser : State
        data class Failed(val reason: Reason) : State
    }

    enum class Reason {
        /** tsnet produced no sign-in URL in time (control server unreachable…). */
        NO_SIGN_IN_PAGE,
        /** No app could open the URL. The copy-link fallback is offered. */
        NO_BROWSER,
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _opens = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** URLs the screen must open now. */
    val opens: SharedFlow<String> = _opens.asSharedFlow()

    /** The last URL handed out, for the copy-link fallback. Never logged. */
    @Volatile var lastUrl: String? = null
        private set

    private var job: Job? = null
    private var leftApp = false

    /** URLs already handed to the browser (never logged); see the class doc. */
    private val opened = LinkedHashSet<String>()

    /** The user tapped Log In with Browser. */
    @Synchronized
    fun begin() {
        if (_state.value == State.OpeningPage) return // a double tap: already on its way
        job?.cancel()
        leftApp = false
        _state.value = State.OpeningPage
        AppLogger.info(TAG, "[Net][TS] login: requested, waiting for the sign-in page")
        job = scope.launch {
            var started = now()
            var restarted = false
            while (true) {
                val st = status()
                if (st.isLoggedIn) {
                    AppLogger.info(TAG, "[Net][TS] login: already signed in")
                    publish(st)
                    _state.value = State.Idle
                    return@launch
                }
                val url = st.authURL
                val stale = url != null && synchronized(opened) { url in opened }
                val failed = st.loginError
                if (!restarted && (stale || failed != null)) {
                    AppLogger.info(TAG, "[Net][TS] login: the last sign-in page can no longer complete (" +
                        (failed?.replace(LOGIN_LINK, "$1<redacted>") ?: "already opened once") + "); restarting the node for a new one")
                    restarted = true
                    freshLogin()
                    started = now()
                    continue
                }
                if (url != null && !stale) {
                    synchronized(opened) {
                        opened += url
                        if (opened.size > 8) opened.remove(opened.first())
                    }
                    lastUrl = url
                    AppLogger.info(TAG, "[Net][TS] login: sign-in page ready after ${now() - started}ms, opening the browser")
                    _state.value = State.InBrowser
                    _opens.tryEmit(url)
                    waitForSignIn()
                    return@launch
                }
                if (now() - started >= urlTimeoutMs) {
                    AppLogger.warning(TAG, "[Net][TS] login: no sign-in page after ${urlTimeoutMs}ms (state=${st.backendState.ifEmpty { "none" }}${st.error?.let { ", error" } ?: ""})")
                    _state.value = State.Failed(Reason.NO_SIGN_IN_PAGE)
                    return@launch
                }
                delay(urlPollMs)
            }
        }
    }

    private suspend fun waitForSignIn() {
        val started = now()
        while (true) {
            delay(loginPollMs)
            val st = status()
            if (st.isLoggedIn) {
                AppLogger.info(TAG, "[Net][TS] login: authorized after ${now() - started}ms, returning to the app")
                publish(st)
                _state.value = State.Idle
                returnToApp()
                return
            }
            if (now() - started >= loginTimeoutMs) {
                AppLogger.warning(TAG, "[Net][TS] login: timed out after ${loginTimeoutMs}ms in the browser")
                // [FIX-R2-7, review §三 失败处理] This used to drop back to Idle,
                // so a sign-in that never completed was indistinguishable from
                // no attempt: the user came back to nothing. Surface a Failed
                // state instead - it survives [appShown] (that path only resets
                // an InBrowser state) and the next begin() clears it. The copy
                // reuses Reason.NO_SIGN_IN_PAGE's string resource ("did not
                // return a sign-in page - check the connection and try
                // again"), whose advice is what a timeout amounts to; a
                // dedicated tailscale_login_timeout string would need a new
                // reason branch in NetworkSettingsScreen's mapping, which is
                // outside this fix batch's file set.
                _state.value = State.Failed(Reason.NO_SIGN_IN_PAGE)
                return
            }
        }
    }

    /** The screen could not open the URL in any app. */
    @Synchronized
    fun browserUnavailable() {
        job?.cancel()
        AppLogger.warning(TAG, "[Net][TS] login: no browser could open the sign-in page; offering the link to copy")
        _state.value = State.Failed(Reason.NO_BROWSER)
    }

    /** The app went to the background (the browser came up). */
    fun appHidden() {
        if (_state.value == State.InBrowser) leftApp = true
    }

    /**
     * The app is in front again. After the browser, without a completed
     * sign-in, the user closed it: back to not signed in, no error.
     */
    @Synchronized
    fun appShown() {
        if (_state.value != State.InBrowser || !leftApp) return
        leftApp = false
        job?.cancel()
        AppLogger.info(TAG, "[Net][TS] login: user returned without completing the sign-in")
        _state.value = State.Idle
        // A sign-in completed in the last second shows at the next poll.
        scope.launch { publish(status()) }
    }

    /** Clears a failure message (e.g. when the screen goes away). */
    fun reset() {
        job?.cancel()
        _state.value = State.Idle
    }

    private fun now(): Long = clock()

    private companion object {
        const val TAG = "Network"
        val LOGIN_LINK = Regex("""(login\.tailscale\.com/a/)[A-Za-z0-9]+""")
    }
}
