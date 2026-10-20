package com.openminis.app.ui.webview

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-webview-render-process-gone] (GH#341) The renderer process is
 * shared: when it dies, EVERY WebView in the process gets `onRenderProcessGone`,
 * and one client that does not override it (or returns false) takes the whole
 * app down with `SIGTRAP: Render process's crash wasn't handled by all
 * associated webviews`.
 *
 * Counting files instead of name-listing them means a renamed file, a new
 * screen, or a WebView moved between helpers cannot quietly regress this — the
 * build fails instead of the app dying for a user.
 */
class WebViewRenderProcessCoverageTest {

    private fun hosts(): List<File> {
        val root = requireNotNull(ProductionSources.mainRoot()) {
            "production source root not found; this guard cannot verify coverage"
        }
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("WebViewClient()") }
            .toList()
    }

    @Test
    fun `every WebView client in the app survives a dead renderer`() {
        val hosts = hosts()
        // The app has several surfaces that construct WebViewClient themselves.
        // A low count would mean the detector stopped matching (renamed builder,
        // factory wrapper) and the guard silently went blind.
        assertTrue("expected several WebView hosts, found ${hosts.size}", hosts.size >= 6)
        val missing = hosts.filterNot { it.readText().contains("onRenderProcessGone") }
        assertTrue(
            "these clients never handle a dead renderer, so the app dies with it: " +
                missing.joinToString { it.name },
            missing.isEmpty(),
        )
    }

    @Test
    fun `each host routes its death through the shared helper`() {
        // The override alone is not enough — returning false kills the app just
        // as surely as not overriding. Every host must report it handled.
        val notRouted = hosts().filterNot {
            val text = it.readText()
            text.contains("WebViewRenderProcess") || text.contains("onRenderProcessGoneHandled(")
        }
        assertTrue(
            "these clients handle the death without the shared helper: " +
                notRouted.joinToString { it.name },
            notRouted.isEmpty(),
        )
    }

    @Test
    fun `the helper reports the death as handled`() {
        val src = ProductionSources.read("ui/webview/WebViewRenderProcess.kt")
        assertTrue(src.contains("fun handle(owner: String, detail: RenderProcessGoneDetail?): Boolean"))
        // "Handled" is the return value that stops the framework from taking the
        // host process down; `false` here would defeat the whole mechanism.
        assertTrue(src.contains("return true"))
    }
}
