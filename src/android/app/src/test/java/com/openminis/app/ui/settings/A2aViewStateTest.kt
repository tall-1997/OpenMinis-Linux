package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import com.openminis.app.R
import com.openminis.app.a2a.A2aStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-a2a-native] Pure-logic guards for the a2a gateway page:
 * the status ladder (a2aViewState), the loopback URL / display-address
 * helpers, the status-label mapping, and the panel WebView's origin
 * confinement (A2aPanelNav — the "every action stays inside this app"
 * guarantee). Plus a source drift guard: the panel WebView must never
 * launch an external browser or activity.
 */
class A2aViewStateTest {

    private fun status(
        installed: Boolean = false,
        running: Boolean = false,
        port: Int = 0,
        panelUrl: String? = null,
        lastError: String? = null,
    ) = A2aStatus(
        installed = installed,
        running = running,
        port = port,
        panelUrl = panelUrl,
        lastError = lastError,
    )

    // -- status ladder (a2aViewState) --

    @Test
    fun installingOutranksEverything() {
        // Mid-deploy the engine still reports installed=false; the
        // installing flag must keep the badge from flickering back to
        // "not installed".
        val vs = a2aViewState(status(port = 3065), installing = true)
        assertEquals(A2aUiState.INSTALLING, vs.state)
    }

    @Test
    fun errorOverlaysServiceState() {
        val vs = a2aViewState(
            status(installed = true, running = true, port = 3065, lastError = "boom"),
            installing = false,
        )
        assertEquals(A2aUiState.ERROR, vs.state)
        assertEquals("boom", vs.lastError)
    }

    @Test
    fun runningBeatsStopped() {
        assertEquals(
            A2aUiState.RUNNING,
            a2aViewState(status(installed = true, running = true, port = 3065), false).state,
        )
        assertEquals(
            A2aUiState.STOPPED,
            a2aViewState(status(installed = true, port = 3065), false).state,
        )
    }

    @Test
    fun notInstalledWhenEngineAbsent() {
        assertEquals(
            A2aUiState.NOT_INSTALLED,
            a2aViewState(status(), false).state,
        )
    }

    // -- port / URL helpers --

    @Test
    fun portHiddenWhenEngineReportsNone() {
        assertNull(a2aViewState(status(), false).port)
        assertEquals(3065, a2aViewState(status(port = 3065), false).port)
    }

    @Test
    fun endpointUrlPinsLoopbackHost() {
        // 127.0.0.1, never "localhost" — an IPv6-preferring resolver can
        // answer ::1 where nothing listens.
        assertEquals("http://127.0.0.1:3065/", a2aEndpointUrl(3065))
    }

    @Test
    fun panelUrlFallsBackToLoopbackRootOnlyWhileRunning() {
        // Contract URL wins when the engine reports one.
        val withContract = a2aViewState(
            status(running = true, port = 3065, panelUrl = "http://127.0.0.1:3065/ui/"),
            false,
        )
        assertEquals("http://127.0.0.1:3065/ui/", withContract.panelUrl)
        // No contract URL + running → loopback root fallback.
        val running = a2aViewState(status(running = true, port = 3065), false)
        assertEquals("http://127.0.0.1:3065/", running.panelUrl)
        // Not running → no fallback: the panel tab must show guidance,
        // never point a WebView at a port nothing listens on.
        val stopped = a2aViewState(status(port = 3065), false)
        assertNull(stopped.panelUrl)
    }

    @Test
    fun displayAddressFormatsHostPort() {
        assertEquals("127.0.0.1:3065", a2aDisplayAddress(3065))
    }

    // -- status-label mapping --

    @Test
    fun labelMappingCoversEveryState() {
        assertEquals(R.string.dev_suite_installing, a2aStatusLabelRes(A2aUiState.INSTALLING))
        assertEquals(R.string.dev_suite_not_installed, a2aStatusLabelRes(A2aUiState.NOT_INSTALLED))
        assertEquals(R.string.backup_history_stopped, a2aStatusLabelRes(A2aUiState.STOPPED))
        assertEquals(R.string.a2a_status_running, a2aStatusLabelRes(A2aUiState.RUNNING))
        assertEquals(R.string.filebrowser_error_title, a2aStatusLabelRes(A2aUiState.ERROR))
    }

    // -- panel origin confinement (A2aPanelNav) --

    @Test
    fun panelOriginLoadsInPlace() {
        assertEquals(
            A2aPanelNavAction.LOAD_IN_PLACE,
            A2aPanelNav.resolve("http://127.0.0.1:3065/", "http://127.0.0.1:3065/"),
        )
        assertEquals(
            A2aPanelNavAction.LOAD_IN_PLACE,
            A2aPanelNav.resolve("http://127.0.0.1:3065/setup?key=k", "http://127.0.0.1:3065/"),
        )
    }

    @Test
    fun providerLoginHopsLoadInPlaceToo() {
        // The panel's own flows — a provider web-login hop to a foreign
        // origin, a docs link — stay inside the embedded view: the panel
        // WebView IS the panel session's in-app browser.
        assertEquals(
            A2aPanelNavAction.LOAD_IN_PLACE,
            A2aPanelNav.resolve("https://auth.provider.example/login?next=1", "http://127.0.0.1:3065/"),
        )
        assertEquals(
            A2aPanelNavAction.LOAD_IN_PLACE,
            A2aPanelNav.resolve("https://github.com/OpenMinis", "http://127.0.0.1:3065/"),
        )
    }

    @Test
    fun systemSchemesDispatchExternally() {
        // tel:/mailto:/intent:/market:/office-raccoon: are links to OTHER
        // apps, not pages — the panel hands them to the system so they
        // reach their handlers instead of dying inside the WebView.
        assertEquals(
            A2aPanelNavAction.EXTERNAL_SCHEME,
            A2aPanelNav.resolve("tel:+15551234567", "http://127.0.0.1:3065/"),
        )
        assertEquals(
            A2aPanelNavAction.EXTERNAL_SCHEME,
            A2aPanelNav.resolve("mailto:hi@example.com", "http://127.0.0.1:3065/"),
        )
        assertEquals(
            A2aPanelNavAction.EXTERNAL_SCHEME,
            A2aPanelNav.resolve("intent://scan/#Intent;scheme=zxing;end", "http://127.0.0.1:3065/"),
        )
        assertEquals(
            A2aPanelNavAction.EXTERNAL_SCHEME,
            A2aPanelNav.resolve("market://details?id=com.example", "http://127.0.0.1:3065/"),
        )
        assertEquals(
            A2aPanelNavAction.EXTERNAL_SCHEME,
            A2aPanelNav.resolve("office-raccoon://login/callback?code=x", "http://127.0.0.1:3065/"),
        )
    }

    @Test
    fun javascriptAndFileStayConfined() {
        assertEquals(
            A2aPanelNavAction.CONFINED,
            A2aPanelNav.resolve("javascript:alert(1)", "http://127.0.0.1:3065/"),
        )
        assertEquals(
            A2aPanelNavAction.CONFINED,
            A2aPanelNav.resolve("file:///data/local/tmp/x.html", "http://127.0.0.1:3065/"),
        )
    }

    @Test
    fun subframesAndUnparseableTargetsFallClosed() {
        assertEquals(
            A2aPanelNavAction.CONFINED,
            A2aPanelNav.resolve("http://evil.example/", "http://127.0.0.1:3065/", isMainFrame = false),
        )
        assertEquals(
            A2aPanelNavAction.CONFINED,
            A2aPanelNav.resolve("not a url \\", "http://127.0.0.1:3065/"),
        )
        // The resolver no longer consults the base: a valid http(s) top-frame
        // target loads in place even with a null base (the embedded view is
        // the panel session's browser, whatever the panel navigates to).
        assertEquals(
            A2aPanelNavAction.LOAD_IN_PLACE,
            A2aPanelNav.resolve("http://127.0.0.1:3065/", null),
        )
    }

    // -- source drift guards: the panel never leaves the app --

    @Test
    fun panelWebViewNeverLaunchesExternally() {
        val src = ProductionSources.read("ui/settings/A2aPanelWebView.kt")
        // The embedded view loads everything http(s) itself; nothing in the
        // panel path may hand a URL to an external browser (ACTION_VIEW).
        assertFalse(src, src.contains("Intent(ACTION_VIEW") || src.contains("Intent(Intent.ACTION_VIEW"))
        assertTrue(src, src.contains("domStorageEnabled = true"))
        assertTrue(src, src.contains("javaScriptEnabled = true"))
    }

    @Test
    fun panelRunsInItsOwnProcessActivity() {
        val manifest = ProductionSources.read("../../../../AndroidManifest.xml")
        assertTrue(manifest, manifest.contains("""android:name=".ui.settings.A2aPanelActivity""""))
        assertTrue(manifest, manifest.contains("""android:process=":a2a""""))
        val screen = ProductionSources.read("ui/settings/A2aScreen.kt")
        // The panel opens as the dedicated activity, never embedded and
        // never via an implicit ACTION_VIEW hand-off.
        assertTrue(screen, screen.contains("A2aPanelActivity::class.java"))
        assertFalse(screen, screen.contains("ACTION_VIEW"))
    }
}
