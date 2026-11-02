package com.openminis.app.a2a

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Contract tests for the [A2aGateway] manager — the first-class a2a feature.
 * The lifecycle actions are fire-and-forget over a real process, so they are
 * not runnable in a JVM unit test; these pin the pure surface the UI reads:
 *  - the port, and the status defaults the flow starts with
 *  - isInstalled = deployed panel bundle + native binary in the APK
 *  - the enable flag persists in a2a's own prefs, decoupled from the
 *    plugin stores
 *
 * Live-probe behavior ([A2aGateway.isRunning] / [A2aGateway.panelUrl] when
 * the gateway answers) is intentionally unpinned: the process contract lives
 * in [A2aGatewayProcessTest], and a JVM shadow of the loopback HTTP call
 * would test the shadow, not the port.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class A2aGatewayTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.applicationInfo.nativeLibraryDir = "/fake/nativeDir"
    }

    @Test fun `port pins the lane contract`() {
        assertEquals(18901, A2aGateway.port())
        assertEquals(18901, A2aGateway.status.value.port)
        // The panel router binds its own loopback port (分端口); the app's
        // WebView and the login callback handling both pin this one.
        assertEquals(18902, A2aGateway.PANEL_PORT)
    }

    @Test fun `panel entry is the full control center, not the lite page`() {
        // The upstream panel is two-layer: /m.html is the light-management
        // entry (no data import/export, no fingerprint/model/routing/log
        // settings) and / is the full control center, mobile-adaptive since
        // 3.3.0 (drawer sidebar, card-layout tables). The app must always
        // open the full entry — this exact line once read "/m.html" and
        // shipped a crippled phone panel.
        assertEquals("http://127.0.0.1:18902/", A2aGateway.panelUrlNow())
    }

    @Test fun `status starts not installed and not running`() {
        val s = A2aGateway.status.value
        assertFalse(s.installed)
        assertFalse(s.running)
        assertNull(s.panelUrl)
        assertNull(s.lastError)
    }

    @Test fun `isInstalled is false when the binary is missing from the APK`() {
        // The fake nativeLibraryDir holds no liba2a.so — the deploy gate must
        // fail on the binary first, whatever the home layout says.
        context.applicationInfo.nativeLibraryDir =
            File(context.cacheDir, "empty-native").apply { mkdirs() }.path
        assertFalse(A2aGateway.isInstalled(context))
    }

    @Test fun `isInstalled is true only with binary and deployed panel`() {
        val nativeDir = File(context.cacheDir, "native").apply { mkdirs() }
        File(nativeDir, "liba2a.so").apply {
            writeText("elf")
            setExecutable(true)
        }
        context.applicationInfo.nativeLibraryDir = nativeDir.path
        // Binary present, panel not deployed yet.
        assertFalse(A2aGateway.isInstalled(context))
        // Full home layout: panel entry, glibc loader, both pack version
        // dirs with their manifest+entry, and the registry — the same
        // gates isDeployed checks.
        val home = File(context.filesDir, "builtin-plugins/${A2aGateway.ID}")
        File(home, "ui/index.html").apply {
            parentFile.mkdirs()
            writeText("<html></html>")
        }
        File(home, "glibc/${A2aPluginShell.LOADER}").apply {
            parentFile.mkdirs()
            writeText("loader")
        }
        packLayout(home, A2aInstaller.PROVIDER_PACK_ID, "bin/agent2api-providers")
        packLayout(home, A2aInstaller.WEB_PACK_ID, "bin/agent2api-web-providers")
        File(home, "data/plugins/plugins.json").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        assertTrue(A2aGateway.isInstalled(context))
    }

    @Test fun `isInstalled is false when a pack entry vanished`() {
        // A half-deployed pack dir (entry missing — the binary was renamed
        // or never landed) must not read as installed forever: install
        // would be skipped and every spawn fails with EACCES/ENOENT.
        val nativeDir = File(context.cacheDir, "native").apply { mkdirs() }
        File(nativeDir, "liba2a.so").apply {
            writeText("elf")
            setExecutable(true)
        }
        context.applicationInfo.nativeLibraryDir = nativeDir.path
        val home = File(context.filesDir, "builtin-plugins/${A2aGateway.ID}")
        File(home, "ui/index.html").apply {
            parentFile.mkdirs()
            writeText("<html></html>")
        }
        File(home, "glibc/${A2aPluginShell.LOADER}").apply {
            parentFile.mkdirs()
            writeText("loader")
        }
        packLayout(home, A2aInstaller.PROVIDER_PACK_ID, "bin/agent2api-providers")
        // Web pack version dir exists but its entry never landed.
        File(home, "data/plugins/${A2aInstaller.WEB_PACK_ID}/${A2aInstaller.VERSION}").mkdirs()
        File(home, "data/plugins/plugins.json").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        assertFalse(A2aGateway.isInstalled(context))
    }

    /** A pack version dir with a manifest that maps the given entry path. */
    private fun packLayout(home: File, id: String, entry: String) {
        File(home, "data/plugins/$id/${A2aInstaller.VERSION}/$entry").apply {
            parentFile.mkdirs()
            writeText("elf")
        }
        File(home, "data/plugins/$id/${A2aInstaller.VERSION}/pack.json").writeText(
            """{"entry": {"aarch64-unknown-linux-gnu": "$entry"}}""",
        )
    }

    @Test fun `enable flag persists in a2a's own prefs, not the plugin stores`() {
        assertFalse(A2aGateway.isEnabled(context))
        A2aGateway.writeEnabled(context, true)
        assertTrue(A2aGateway.isEnabled(context))
        // The key lives in the a2a prefs file — the plugin stores stay out of
        // the loop (the lifecycle is decoupled from the plugin registry).
        assertTrue(
            context.getSharedPreferences("a2a_gateway", Context.MODE_PRIVATE)
                .getBoolean("enabled", false),
        )
        A2aGateway.writeEnabled(context, false)
        assertFalse(A2aGateway.isEnabled(context))
    }
}
