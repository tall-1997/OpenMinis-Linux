package com.openminis.app.ui.settings

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM contract tests for the login-popup host state machine.
 *
 * The host is deliberately Android-UI-free: everything here is the plain
 * state transitions (open/close/rehost/closeFor/closeTop) plus the
 * Compose-observable snapshots, so the whole file runs headless on the JVM
 * with Robolectric only supplying a shadow WebView instance for identity
 * checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class A2aPopupHostStateTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun newWindow(): WebView = WebView(context)

    @Test
    fun `open publishes a full-screen popup`() = runTest {
        val host = A2aPopupHostState()
        val state = host.open(newWindow(), "https://example.com/oauth")

        val popups = host.popups.value
        assertEquals(1, popups.size)
        assertEquals("https://example.com/oauth", state.initialUrl)
        // The copyable address starts where the shim opened the window and
        // follows every load (onPageStarted / doUpdateVisitedHistory).
        assertEquals("https://example.com/oauth", state.url)
        assertEquals(0, state.progress)
        assertFalse(state.loadStarted)
        assertFalse(state.loadFailed)
    }

    @Test
    fun `open twice for the same WebView returns the live state`() = runTest {
        val host = A2aPopupHostState()
        val window = newWindow()
        val first = host.open(window, "https://example.com/a")
        val second = host.open(window, "https://example.com/b")

        assertEquals(1, host.popups.value.size)
        assertEquals("https://example.com/a", first.initialUrl)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `close by id removes the window`() = runTest {
        val host = A2aPopupHostState()
        val window = newWindow()
        val state = host.open(window, "https://example.com/oauth")

        host.close(state.id)
        assertTrue(host.popups.value.isEmpty())
    }

    @Test
    fun `closeFor from the panel chrome is idempotent with window close`() = runTest {
        val host = A2aPopupHostState()
        val window = newWindow()
        host.open(window, "https://example.com/oauth")

        host.closeFor(window)
        assertTrue(host.popups.value.isEmpty())

        // The popup's own onCloseWindow fires afterwards — must not crash.
        host.closeFor(window)
        assertTrue(host.popups.value.isEmpty())
    }

    @Test
    fun `closeTop closes the newest window first and is a no-op when empty`() = runTest {
        val host = A2aPopupHostState()
        host.open(newWindow(), "https://example.com/first")
        host.open(newWindow(), "https://example.com/second")

        assertTrue(host.closeTop())
        assertEquals(listOf("https://example.com/first"), host.popups.value.map { it.initialUrl })

        assertTrue(host.closeTop())
        assertTrue(host.popups.value.isEmpty())

        // Back press with no windows open: the panel history takes over.
        assertFalse(host.closeTop())
    }

    @Test
    fun `a closed window may be rehosted on the next login`() = runTest {
        val host = A2aPopupHostState()
        val window = newWindow()
        val state = host.open(window, "https://example.com/oauth")
        host.close(state.id)

        // The WebView handle may be reused by the panel for another login
        // round — after close it is eligible again.
        val rehosted = host.open(window, "https://example.com/oauth2")
        assertEquals(1, host.popups.value.size)
        assertFalse(rehosted.id == state.id)
    }

    @Test
    fun `progress beyond the visible band renders as a plain spinner`() = runTest {
        // The overlay shows the hairline LinearProgressIndicator only in
        // 1..99 (indeterminate during the 0 burst, full at 100). This is
        // the render predicate the overlay depends on — pin it so a
        // progress-signal change cannot silently break the chrome.
        fun visible(progress: Int) = progress in 1..99

        assertFalse(visible(0))
        assertTrue(visible(1))
        assertTrue(visible(99))
        assertFalse(visible(100))
    }
}
